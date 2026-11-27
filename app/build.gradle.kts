plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.baselineprofile)
    // The Roborazzi Gradle plugin is deliberately not applied: roborazzi 1.79.0 breaks
    // compileDebugKotlin under Kotlin 2.3.0 / AGP 9.4.0 (spurious unresolved references in
    // unrelated files). Its only extra value is the recordRoborazziDebug-style task aliases;
    // the library honours -Proborazzi.* properties on plain testDebugUnitTest (see testOptions).
}

import com.android.build.gradle.internal.tasks.CompileArtProfileTask
import java.security.MessageDigest
import java.util.Properties

// Embeds per-API ART dex metadata (.dm) files into assets/updater_dm/ on non-debuggable
// variants so sideloaded updates via ApkInstaller compile profiles at install time.
abstract class EmbedUpdateDexMetadataTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val dexMetadataDirectory: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun embed() {
        val target = outputDirectory.get().asFile.resolve("updater_dm")
        target.deleteRecursively()
        target.mkdirs()
        val source = dexMetadataDirectory.files.firstOrNull { it.resolve("dex-metadata-map.properties").exists() } ?: return
        val apiToFile = Properties().apply { source.resolve("dex-metadata-map.properties").inputStream().use(::load) }
        val indexLines = apiToFile.stringPropertyNames().sortedBy { it.toInt() }.map { api ->
            val relativePath = apiToFile.getProperty(api)
            val fileName = relativePath.substringBefore('/') + ".dm"
            source.resolve(relativePath).copyTo(target.resolve(fileName), overwrite = true)
            "$api=$fileName"
        }
        target.resolve("index.properties").writeText(indexLines.joinToString("\n", postfix = "\n"))
    }
}

// The decoder AARs are built outside Maven; fail the build if they drift from the recorded hashes.
abstract class VerifyLocalLibsTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val libsDirectory: DirectoryProperty

    @TaskAction
    fun verify() {
        val dir = libsDirectory.get().asFile
        val recorded = dir.resolve("checksums.sha256").readLines().filter { it.isNotBlank() }.associate {
            val (hash, name) = it.split(Regex("\\s+"), limit = 2)
            name to hash
        }
        val mismatches = recorded.mapNotNull { (name, expected) ->
            val file = dir.resolve(name)
            val actual = if (file.exists()) {
                MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            } else "missing"
            if (actual != expected) "$name: expected $expected, found $actual" else null
        }
        check(mismatches.isEmpty()) {
            "Local libs differ from app/libs/checksums.sha256 (update it when rebuilding the AARs):\n" + mismatches.joinToString("\n")
        }
    }
}

val verifyLocalLibs = tasks.register<VerifyLocalLibsTask>("verifyLocalLibs") {
    libsDirectory.set(layout.projectDirectory.dir("libs"))
}
tasks.named("preBuild") { dependsOn(verifyLocalLibs) }

// The mapping task parses every class with a bundled ASM that rejects Java 25 bytecode (class file major 69)
// from a dependency. It only adds Compose group-key names to the R8 mapping for stack-trace deobfuscation.
composeCompiler {
    includeComposeMappingFile = false
}

android {
    namespace = "com.carteltv.tv"
    compileSdk {
        version = release(37) { minorApiLevel = 1 }
    }
    ndkVersion = libs.versions.ndk.get()
    buildToolsVersion = libs.versions.buildTools.get()

    defaultConfig {
        applicationId = "com.carteltv.tv"
        minSdk = 30
        targetSdk = 36
        versionName = "0.8.2-blend" // x-release-please-version
        // Derived so release-please's versionName bump also raises versionCode; the updater only offers higher codes.
        versionCode = versionName!!.substringBefore('-').split('.').map { it.toInt() }
            .let { (major, minor, patch) -> major * 10_000 + minor * 100 + patch }
        // Universal coverage: arm64 (Shield/modern boxes), armeabi-v7a (Fire TV sticks and
        // 32-bit generic boxes), x86/x86_64 (emulators and x86 TVs). The decoder AARs already
        // ship all four ABIs; splits below emit per-arch APKs plus one universal APK.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64") }
        externalNativeBuild {
            cmake {
                arguments += "-DDOVI_ENABLE_LIBDOVI=ON"
            }
        }
    }

    // Per-ABI APKs plus a universal APK that installs on any TV, stick or box.
    // The universal APK keeps the plain versionCode; per-ABI APKs get bumped codes so
    // an ABI-matched install always wins where more than one APK is offered.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }

    // Native tier: dovi_bridge (DV P7→P8.1 RPU rewrite, links third_party/libdovi).
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }

    // The logo pack is read in place through openFd, which needs it stored uncompressed.
    androidResources {
        noCompress += "pack"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin {
        jvmToolchain(21)
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
    buildFeatures { compose = true; buildConfig = true }

    // Telegram MTProto credentials stay out of the repo: put telegram.apiId / telegram.apiHash
    // into local.properties (from my.telegram.org). Empty defaults keep the build green without them.
    val tgProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { runCatching { load(it) } }
    }
    val tgApiId = tgProps.getProperty("telegram.apiId", "")
    val tgApiHash = tgProps.getProperty("telegram.apiHash", "")
    // YouTube OAuth client secret stays out of the repo (push protection rejects it):
    // from YT_CLIENT_SECRET env var (CI) or youtube.clientSecret in local.properties.
    val ytClientSecret = System.getenv("YT_CLIENT_SECRET")
        ?: tgProps.getProperty("youtube.clientSecret", "")
    defaultConfig {
        buildConfigField("String", "TG_API_ID", "\"$tgApiId\"")
        buildConfigField("String", "TG_API_HASH", "\"$tgApiHash\"")
        buildConfigField("String", "YT_CLIENT_SECRET", "\"$ytClientSecret\"")
    }

    testOptions {
        // Unit tests exercise Vault's keystore-failure paths; Android stub methods (e.g. Log.w)
        // must return defaults instead of throwing "Stub!" so those paths are testable on the JVM.
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Full stack traces for failed tests in the console (default hides the cause).
            it.testLogging { exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
            // Roborazzi README: hardware-style pixel copy renders shadows and elevation accurately.
            it.systemProperties["robolectric.pixelCopyRenderMode"] = "hardware"
            // Without the Roborazzi Gradle plugin (see plugins block), forward -Proborazzi.*
            // properties to the test JVM so record/verify works on plain testDebugUnitTest.
            project.properties.filterKeys { key -> key.startsWith("roborazzi.") }.forEach { (key, value) ->
                it.systemProperties[key] = value.toString()
            }
            // Roborazzi golden tests are recorded and verified on dev machines (Windows font
            // raster); CI renders different glyph metrics so verification is meaningless there,
            // and Robolectric activity resolution fails on the CI JVM. Skip them on CI only.
            if (System.getenv("CI") == "true") {
                it.filter { excludeTestsMatching("com.carteltv.tv.ui.screenshot.*") }
            }
        }
    }
}

androidComponents {
    onVariants { variant ->
        // Stamp every artifact with the brand and ABI: CartelTV-debug.apk,
        // CartelTV-release-universal.apk, CartelTV-release-armeabi-v7a.apk, ...
        variant.outputs.forEach { output ->
            val abi = output.filters.firstOrNull()?.identifier
            output.outputFileName.set(
                if (abi != null) "CartelTV-${variant.name}-$abi.apk"
                else "CartelTV-${variant.name}.apk"
            )
        }
        if (variant.debuggable) return@onVariants
        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val embedTask = tasks.register<EmbedUpdateDexMetadataTask>("embed${variantName}UpdateDexMetadata") {
            dexMetadataDirectory.from(provider {
                tasks.withType<CompileArtProfileTask>()
                    .matching { it.name == "compile${variantName}ArtProfile" }
                    .map { it.dexMetadataDirectory }
            })
        }
        variant.sources.assets?.addGeneratedSourceDirectory(embedTask, EmbedUpdateDexMetadataTask::outputDirectory)
    }
}

tasks.register<Exec>("installGranted") {
    group = "install"
    description = "Builds the debug APK, installs it and grants every permission (see scripts/install-granted.ps1)."
    dependsOn("assembleDebug")
    workingDir = rootProject.projectDir
    commandLine("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "scripts/install-granted.ps1")
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.bundles.coil)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.profileinstaller)
    "baselineProfile"(project(":baselineprofile"))

    // Playback engine: stock Media3 (Apache-2.0) plus CartelTV's own renderers and extractors.
    // The FFmpeg audio and dav1d AV1 decoder modules are local AARs built from Media3 sources.
    // Includes media3-session: publishes the player as a system session (launcher card, remotes, assistants).
    implementation(libs.bundles.media3)
    implementation(libs.guava)
    // Real QR codes on every pairing screen (ZXing encoder, pinned; Apache 2.0).
    implementation(libs.zxing.core)
    compileOnly(libs.checker.qual)

    implementation(files(
        "libs/lib-decoder-av1-release.aar",
        "libs/lib-decoder-ffmpeg-release.aar",
    ))

    // 3D storm view (Filament). Pinned; SceneView 4.72 needs AGP 9.4+, JVM target 21, compileSdk 37.
    implementation(libs.sceneview)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    // SMB recording targets (com.hierynomus classes). Pinned; sole new dependency of the pass.
    implementation(libs.smbj)
    // Telegram MTProto (tdlib-android core). Pinned.
    implementation(libs.tdlib.android.core)
    // Offline voice STT (Vosk). Pinned; Apache 2.0. Adds JNA 5.18.1 transitively per its POM.
    implementation(libs.vosk.android)
    // Backdrop blur for glass surfaces (Haze). Pinned; Apache 2.0. API 31+ RenderEffect, scrim fallback below.
    implementation(libs.haze)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.bundles.roborazzi)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.tooling)
}
