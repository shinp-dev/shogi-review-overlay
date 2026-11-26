import java.io.File
import java.security.MessageDigest

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

// The ARM64 executable is a tracked, prebuilt artifact. No NDK, upstream
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
