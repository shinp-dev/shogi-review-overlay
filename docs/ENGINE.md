# Android版やねうら王（リポジトリ同梱）

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
