"""One-time migration, run only after the existing pinned native build succeeds."""
from pathlib import Path
import hashlib
import subprocess
import tarfile
import io

ROOT = Path(__file__).resolve().parents[1]
REV = "3bd5141dfd50b3fc6abd9a9db7561d9338a883d0"
SOURCE = ROOT / ".native-build/YaneuraOu"
VENDOR = ROOT / "third_party/YaneuraOu"
ENGINE = ROOT / "app/src/main/jniLibs/arm64-v8a/libshogi_engine.so"
STAMP = ROOT / ".native-build/material-arm64.sha256"
GRADLE = ROOT / "app/build.gradle.kts"
GITIGNORE = ROOT / ".gitignore"
ATTR = ROOT / ".gitattributes"
DOC = ROOT / "docs/ENGINE.md"
digest = hashlib.sha256(ENGINE.read_bytes()).hexdigest()
recipe = REV + ":23.1.7779620:MaterialLv1:arm64-v8a:api29:16k:v1"
assert STAMP.read_text() == recipe + "\n" + digest, "Unverified build"
assert subprocess.check_output(["git", "-C", str(SOURCE), "rev-parse", "HEAD"], text=True).strip() == REV
header = ENGINE.read_bytes()[:64]
assert header[:6] == bytes([127,69,76,70,2,1]) and header[16:20] == bytes([3,0,183,0])
assert not VENDOR.exists(), "Vendor destination already exists"

# Copy the exact tracked source tree, not the NDK output or .git metadata.
tracked = subprocess.check_output(["git", "-C", str(SOURCE), "ls-files", "-z"]).split(b"\0")
archive = subprocess.check_output(["git", "-C", str(SOURCE), "archive", "--format=tar", REV])
VENDOR.mkdir(parents=True)
with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
    tar.extractall(VENDOR, filter="data")
for item in tracked:
    if item:
        assert (VENDOR / item.decode()).exists(), "Missing upstream tracked file"
assert (VENDOR / "tools/build/jni/Android.mk").is_file()
(VENDOR.parent / "YaneuraOu.REVISION").write_text(REV + "\n")
(VENDOR.parent / "YaneuraOu.arm64.sha256").write_text(digest + "\n")

# Retain the Android configuration and replace the implicit network/native
# preparation with a local integrity check only.
old = GRADLE.read_text()
marker = "// Use the upstream Android makefiles directly on all supported hosts."
assert marker in old
prefix = old.split(marker, 1)[0]
prefix = prefix.replace("import java.nio.file.Files\n", "").replace("import java.nio.file.StandardCopyOption\n", "")
GRADLE.write_text(prefix + """// The ARM64 executable is a tracked, prebuilt artifact. No NDK, upstream
// download, or native compilation is required for ordinary APK builds.
val engine = file("src/main/jniLibs/arm64-v8a/libshogi_engine.so")
val engineDigest = rootProject.file("third_party/YaneuraOu.arm64.sha256")
val verifyYaneuraOu = tasks.register("verifyYaneuraOu") {
    group = "verification"
    description = "Verify the vendored ARM64 YaneuraOu executable"
    inputs.files(engine, engineDigest)
    doLast {
        check(engine.isFile && engineDigest.isFile) { "Vendored engine missing; obtain a complete checkout" }
        val bytes = engine.readBytes()
        check(bytes.size >= 64 &&
            bytes.sliceArray(0..5).contentEquals(byteArrayOf(127, 69, 76, 70, 2, 1)) &&
            bytes.sliceArray(16..19).contentEquals(byteArrayOf(3, 0, 183.toByte(), 0))
        ) { "Vendored engine is not an ARM64 PIE executable" }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        check(digest == engineDigest.readText().trim()) { "Vendored engine SHA-256 mismatch" }
    }
}
tasks.configureEach {
    if (name == "preBuild" ||
        (name.startsWith("merge") && (name.endsWith("JniLibFolders") ||
            name.endsWith("NativeLibs")))) {
        dependsOn(verifyYaneuraOu)
    }
}
""")
ignore = GITIGNORE.read_text()
line = "app/src/main/jniLibs/arm64-v8a/libshogi_engine.so\n"
assert line in ignore
GITIGNORE.write_text(ignore.replace(line, ""))
existing = ATTR.read_text() if ATTR.exists() else ""
ATTR.write_text(existing.rstrip() + "\napp/src/main/jniLibs/arm64-v8a/libshogi_engine.so -text\nthird_party/YaneuraOu/** -text\n")
DOC.write_text("""# Android版やねうら王（リポジトリ同梱）

- 上流: https://github.com/yaneurao/YaneuraOu
- 固定リビジョン: 3bd5141dfd50b3fc6abd9a9db7561d9338a883d0
- 版: YANEURAOU_ENGINE_MATERIAL (MaterialLv1)
- Android ABI: arm64-v8a / NDK r23b (23.1.7779620)
- ライセンス: GPL-3.0 (LICENSE-GPL-3.0 および上流の同梱ライセンス)

実行ファイルは app/src/main/jniLibs/arm64-v8a/libshogi_engine.so に追跡済み。
対応する上流ソースは third_party/YaneuraOu、固定リビジョンと SHA-256 は
third_party/YaneuraOu.REVISION と third_party/YaneuraOu.arm64.sha256。
通常の Gradle ビルドはローカルの実行ファイルを検証して APK に格納するだけで、
上流へのアクセス・NDK・ネイティブコンパイルは不要。通常の SDK/Gradle 依存関係は別途必要。

## 対応ソースからの明示的な再ビルド

NDK r23b をインストールし、third_party/YaneuraOu のディレクトリで
以下のコマンドを実行する（Windows では ndk-build.cmd）。

    ndk-build NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=tools/build/jni/Android.mk NDK_APPLICATION_MK=tools/build/jni/Application.mk YANEURAOU_EDITION=YANEURAOU_ENGINE_MATERIAL MATERIAL_LEVEL=1 APP_ABI=arm64-v8a APP_PLATFORM=android-29 APP_LDFLAGS=-Wl,-z,max-page-size=16384 -j2

出力 libs/arm64-v8a/YaneuraOu_MaterialLv1_arm64-v8a を同梱先へコピーし、
実行形式と SHA-256 を検証し、ハッシュ記録を更新する。通常ビルドでは実行しない。
上流とアプリのGPLライセンス・著作権表示を保持する。

UsiEngine.kt は ProcessBuilder で USI 通信を行う。
NNUE評価関数は未搭載。MaterialLv1 は駒得中心の探索エンジン。
""")
print("Vendor snapshot prepared; engine SHA-256:", digest)
