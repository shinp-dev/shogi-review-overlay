import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.shinpstudio.shogireviewoverlay"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.shinpstudio.shogireviewoverlay"
        minSdk = 29
        targetSdk = 35
        versionCode = 7
        versionName = "0.4.3"
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/libshogi_engine.so"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
}

// Use the upstream Android makefiles directly on all supported hosts.
// MaterialLv1 does not require a separate evaluation network.
val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
val ndkRelease = "23.1.7779620" // r23b, same toolchain as the original CI
val revision = "3bd5141dfd50b3fc6abd9a9db7561d9338a883d0"
val cache = rootProject.file(".native-build")
val source = File(cache, "YaneuraOu")
val engine = file("src/main/jniLibs/arm64-v8a/libshogi_engine.so")
val stamp = File(cache, "material-arm64.sha256")
val sdkDirectoryProvider = androidComponents.sdkComponents.sdkDirectory
val recipe = revision + ":" + ndkRelease + ":MaterialLv1:arm64-v8a:api29:16k:v1"

fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}

fun validArm64Elf(file: File): Boolean {
    if (!file.isFile || file.length() < 64) return false
    val h = file.inputStream().use { it.readNBytes(64) }
    return h.size == 64 &&
        h[0] == 0x7f.toByte() && h[1] == 'E'.code.toByte() &&
        h[2] == 'L'.code.toByte() && h[3] == 'F'.code.toByte() &&
        h[4] == 2.toByte() && h[5] == 1.toByte() &&
        h[16] == 3.toByte() && h[17] == 0.toByte() &&
        h[18] == 183.toByte() && h[19] == 0.toByte()
}

fun runCommand(args: List<String>, directory: File, toolPath: File? = null) {
    logger.lifecycle("YaneuraOu: " + args.joinToString(" "))
    val builder = ProcessBuilder(args).directory(directory).inheritIO()
    if (toolPath != null) {
        val env = builder.environment()
        val key = env.keys.firstOrNull { it.equals("PATH", true) } ?: "PATH"
        env[key] = toolPath.absolutePath + File.pathSeparator + (env[key] ?: "")
    }
    val result = builder.start().waitFor()
    check(result == 0) { "YaneuraOu preparation failed (exit " + result + "): " + args.first() }
}

fun gitHead(directory: File): String {
    val p = ProcessBuilder("git", "rev-parse", "HEAD")
        .directory(directory).redirectErrorStream(true).start()
    val output = p.inputStream.bufferedReader().use { it.readText().trim() }
    check(p.waitFor() == 0) { "Cannot read YaneuraOu source revision: " + output }
    return output
}

fun ensureSource() {
    if (!File(source, ".git").isDirectory) {
        cache.mkdirs()
        val staging = File(cache, "YaneuraOu.partial")
        if (staging.exists()) check(staging.deleteRecursively())
        staging.mkdirs()
        runCommand(listOf("git", "init"), staging)
        runCommand(listOf("git", "remote", "add", "origin", "https://github.com/yaneurao/YaneuraOu.git"), staging)
        runCommand(listOf("git", "fetch", "--depth", "1", "origin", revision), staging)
        runCommand(listOf("git", "checkout", "--detach", "FETCH_HEAD"), staging)
        check(gitHead(staging) == revision) { "Unexpected YaneuraOu revision" }
        Files.move(staging.toPath(), source.toPath())
    }
    check(gitHead(source) == revision) {
        "Unexpected cached YaneuraOu revision. Delete .native-build/YaneuraOu and rebuild."
    }
}

fun ensureNdk(sdk: File): File {
    val ndk = File(sdk, "ndk/" + ndkRelease)
    val tool = if (windows) "ndk-build.cmd" else "ndk-build"
    if (!File(ndk, tool).isFile) {
        val manager = if (windows) "sdkmanager.bat" else "sdkmanager"
        val bins = listOf(File(sdk, "cmdline-tools/latest/bin")) +
            (File(sdk, "cmdline-tools").listFiles()?.sortedByDescending { it.name }
                ?.map { File(it, "bin") } ?: emptyList()) + File(sdk, "tools/bin")
        val bin = bins.firstOrNull { File(it, manager).isFile }
            ?: error("Install Android SDK Command-line Tools via Android Studio SDK Manager.")
        val command = if (windows) listOf("cmd.exe", "/d", "/c", manager)
            else listOf(File(bin, manager).absolutePath)
        runCommand(command + ("ndk;" + ndkRelease), bin)
    }
    check(File(ndk, tool).isFile) {
        "Android NDK r23b is missing. Accept SDK licenses in SDK Manager and retry."
    }
    return ndk
}

val prepareYaneuraOu = tasks.register("prepareYaneuraOu") {
    group = "build"
    description = "Prepare and validate the offline YaneuraOu MaterialLv1 executable"
    notCompatibleWithConfigurationCache("Uses local SDK tools and validates the native executable")
    doLast {
        if (validArm64Elf(engine) && stamp.isFile &&
            stamp.readText() == recipe + "\n" + sha256(engine)) {
            logger.lifecycle("Reusing verified YaneuraOu MaterialLv1 executable.")
            return@doLast
        }
        val ndk = ensureNdk(sdkDirectoryProvider.get().asFile)
        ensureSource()
        val tool = if (windows) "ndk-build.cmd" else "ndk-build"
        val command = if (windows) listOf("cmd.exe", "/d", "/c", tool)
            else listOf(File(ndk, tool).absolutePath)
        // Relative paths avoid shell expansion and mirror upstream android_build.sh.
        runCommand(command + listOf(
            "NDK_PROJECT_PATH=.", "APP_BUILD_SCRIPT=tools/build/jni/Android.mk",
            "NDK_APPLICATION_MK=tools/build/jni/Application.mk",
            "YANEURAOU_EDITION=YANEURAOU_ENGINE_MATERIAL", "MATERIAL_LEVEL=1",
            "APP_ABI=arm64-v8a", "APP_PLATFORM=android-29",
            "APP_LDFLAGS=-Wl,-z,max-page-size=16384", "-j2"
        ), source, ndk)
        val compiled = File(source, "libs/arm64-v8a/YaneuraOu_MaterialLv1_arm64-v8a")
        check(validArm64Elf(compiled)) { "NDK did not produce an ARM64 PIE YaneuraOu executable." }
        engine.parentFile.mkdirs()
        val partial = File(engine.parentFile, engine.name + ".partial")
        compiled.copyTo(partial, overwrite = true)
        Files.move(partial.toPath(), engine.toPath(), StandardCopyOption.REPLACE_EXISTING)
        check(validArm64Elf(engine))
        stamp.writeText(recipe + "\n" + sha256(engine))
        logger.lifecycle("Offline YaneuraOu engine verified.")
    }
}

tasks.configureEach {
    if (name == "preBuild" ||
        (name.startsWith("merge") && (name.endsWith("JniLibFolders") ||
            name.endsWith("NativeLibs")))) {
        dependsOn(prepareYaneuraOu)
    }
}
