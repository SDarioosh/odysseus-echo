import kotlin.toString

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.roborazzi) // Odysseus fork: JVM screenshot tests (docs/odysseus/screenshots.md)
}

tasks.register("printVersionName") {
    group = "custom"
    description = "Output version name for use in env vars"
    doLast {
        println(android.defaultConfig.versionName)
    }
}

tasks.register("printVersionCode") {
    group = "custom"
    description = "Output version code (Odysseus fork: derived from versionName)"
    doLast {
        println(android.defaultConfig.versionCode)
    }
}

android {
    namespace = "com.msp1974.vacompanion"
    compileSdk = 36

    // Odysseus fork: versionCode is worked out from versionName instead of upstream's
    // version.properties counter (which went up on every Gradle run and had to be committed by
    // hand, so a fresh clone could build a lower code than the one installed and adb would refuse
    // it). major.minor.patch+odysseus.N gives major*10^7 + minor*10^5 + patch*10^3 + N, so a new
    // fork release (N+1) or a rebase onto a newer upstream always goes up, and every build of a
    // commit gets the same code. Starts at 1400016 for 0.14.0+odysseus.16 (above the old 52xx).
    val odysseusVersionName = "0.14.0+odysseus.41"
    val odysseusVersionCode = Regex("""(\d+)\.(\d+)\.(\d+)\+odysseus\.(\d+)""")
        .matchEntire(odysseusVersionName)
        ?.destructured
        ?.let { (major, minor, patch, n) ->
            val parts = listOf(major, minor, patch, n).map { it.toInt() }
            if (parts[1] > 99 || parts[2] > 99 || parts[3] > 999) {
                throw GradleException("versionName $odysseusVersionName out of range for versionCode")
            }
            parts[0] * 10_000_000 + parts[1] * 100_000 + parts[2] * 1_000 + parts[3]
        }
        ?: throw GradleException("versionName must look like 0.14.0+odysseus.N, got $odysseusVersionName")

    defaultConfig {
        // Odysseus fork: own package id, so it never collides with (or is "updated" to) the
        // official Firebase-enabled VACA build. The "+odysseus.N" build metadata is ignored by
        // semver comparisons, so HA's minimum-app-version check still sees 0.14.0.
        applicationId = "com.msp1974.vacompanion.odysseus"
        minSdk = 26
        targetSdk = 36
        versionName = odysseusVersionName
        versionCode = odysseusVersionCode
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")
        // Odysseus fork: Odysseus address prefilled on the phone setup page (odysseus/EchoSetup.kt);
        // build with -PodysseusDefaultUrl=https://odysseus.example.ts.net, blank by default.
        val odysseusDefaultUrl = (findProperty("odysseusDefaultUrl") as String?).orEmpty().filter { it != '"' && it != '\\' }
        buildConfigField("String", "ODYSSEUS_DEFAULT_URL", "\"$odysseusDefaultUrl\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_FILE") ?: "release.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            ndk {
                abiFilters.add("arm64-v8a")
                abiFilters.add("armeabi-v7a")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
            ndk {
                abiFilters.add("arm64-v8a")
                abiFilters.add("armeabi-v7a")
                debugSymbolLevel = "FULL"
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        // Odysseus fork: the screenshot tests (Robolectric) need the app's resources (fonts).
        unitTests.isIncludeAndroidResources = true
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val name = "odysseus-vaca-${variant.outputs.first().versionName.get()}-${variant.name}.apk"
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)?.outputFileName?.set(name)
        }
    }
}

// Odysseus fork: screenshot tests (package ...screenshots, docs/odysseus/screenshots.md) run only
// with -Pscreenshots, and then alone, so the plain unit tests stay fast and never see Robolectric.
// Robolectric runs offline on the framework jar Gradle fetches (configuration robolectricSdk),
// so it never downloads anything itself.
val screenshotTests = providers.gradleProperty("screenshots").isPresent
val robolectricSdk: Configuration by configurations.creating { isTransitive = false }
tasks.withType<Test>().configureEach {
    if (screenshotTests) {
        filter { includeTestsMatching("com.msp1974.vacompanion.screenshots.*") }
        maxHeapSize = "2g"
        // Robolectric on SDK 36 under JDK 17+
        jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED", "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
        inputs.files(robolectricSdk)
        val sdkDir = robolectricSdk.elements.map { it.first().asFile.parentFile.absolutePath }
        doFirst {
            systemProperty("robolectric.offline", "true")
            systemProperty("robolectric.dependency.dir", sdkDir.get())
        }
    } else {
        exclude("com/msp1974/vacompanion/screenshots/**")
    }
}

dependencies {

    implementation(project(":microfeatures"))
    implementation(project(":webrtc-apm"))
    implementation(project(":sendspin"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.zxing.core) // Odysseus fork: QR code for phone setup (odysseus/EchoSetupPanel.kt)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.core.splashscreen)
    implementation (libs.androidx.material.icons.extended)
    implementation (libs.androidx.preference.ktx)
    implementation (libs.timber)
    implementation (libs.onnxruntime.android)
    implementation (libs.semver)
    implementation (libs.androidx.webkit)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.compose)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.litert) {
        // ai-delivery is LiteRT's optional Play Feature Delivery path for downloading models
        // over-the-air - unused here since wake-word models ship in assets/. It drags in
        // asset-delivery -> work-runtime -> Room 2.5.0, whose generated WorkDatabase R8 can't
        // reconstruct, crashing every release build at process start before any app code runs.
        exclude(group = "com.google.android.play", module = "ai-delivery")
    }
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.ktor.network)
    implementation(libs.ktor.client.core) // Ktor-Core
    implementation(libs.ktor.client.okhttp) // Ktor-Engine
    implementation(libs.ktor.serialization.kotlinx.json) // Ktor- To work with Serialization
    implementation(libs.ktor.client.content.negotiation) // Serialization
    testImplementation(libs.junit)
    // Odysseus fork: JVM screenshot tests (docs/odysseus/screenshots.md), test-only
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    robolectricSdk(libs.robolectric.android.all)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
}
