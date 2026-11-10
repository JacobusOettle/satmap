import org.gradle.api.tasks.PathSensitivity
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.jacobusoettle.satmap"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jacobusoettle.satmap"
        minSdk = 26
        targetSdk = 36
        // Each CI build a higher number, so it installs over the one before.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Phones (64- and 32-bit ARM) and the x86_64 emulator; MapLibre's native code is the bulk of the APK.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        // The groups invite domain, one value in gradle.properties (docs/groups/SPEC.md §5.1): the App Links host in
        // the manifest and GroupConfig.domain.
        val groupsDomain = providers.gradleProperty("groupsDomain").getOrElse("example.invalid")
        manifestPlaceholders["groupsDomain"] = groupsDomain
        buildConfigField("String", "GROUPS_DOMAIN", "\"$groupsDomain\"")
        // The test-build remote switch (android/docs/spec/test-builds.md): the build channel, "test" or "store".
        // CI's release APK is a test build and leaves this unset, which counts as "test". A future Play Store build
        // sets it to "store" (in gradle.properties, or on the command line with -PsatmapChannel=store) and then never
        // checks or locks. Debug builds never check whatever this says (TestBuildConfig uses BuildConfig.DEBUG).
        val satmapChannel = providers.gradleProperty("satmapChannel").getOrElse("test")
        buildConfigField("String", "SATMAP_CHANNEL", "\"$satmapChannel\"")
    }

    signingConfigs {
        create("release") {
            // A private key from the repository secrets when there is one, else the public test key
            // (signing/README.md), so every build installs over the one before. An empty variable counts as unset.
            fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotEmpty() }
            storeFile = file(env("ANDROID_KEYSTORE_FILE") ?: "../signing/satmap-test.jks")
            storePassword = env("ANDROID_KEYSTORE_PASSWORD") ?: "satmap-test"
            keyAlias = env("ANDROID_KEY_ALIAS") ?: "satmap"
            keyPassword = env("ANDROID_KEY_PASSWORD") ?: "satmap-test"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        // The same shipped data as the iPhone app: aviation places and the Kruger roads.
        getByName("main").assets.srcDirs("../../SatMap/Resources")
        // Where the screenshot walk touches the screen: made on the emulator, checked by the unit tests.
        getByName("test").kotlin.srcDir("src/sharedTest/kotlin")
        getByName("androidTest").kotlin.srcDir("src/sharedTest/kotlin")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // The Swift tests' fixtures (KML files, the iPhone's and Android's backups), read in place. A folder outside
        // android/ is declared as an input, or Gradle would reuse an earlier result when only the fixtures change.
        unitTests.all {
            val fixtures = rootProject.file("../Tests/SatMapCoreTests/Fixtures")
            it.systemProperty("satmap.fixtures", fixtures.absolutePath)
            it.inputs.dir(fixtures).withPropertyName("satmapFixtures").withPathSensitivity(PathSensitivity.RELATIVE)
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.maplibre)
    implementation(libs.okhttp)
    // Android Auto (CarPlay's counterpart): the car app library, and its projected (phone-driven) host support.
    implementation(libs.car.app)
    implementation(libs.car.app.projected)
    // Groups: SatMap Pro through Play Billing, invite QR codes drawn and read with ZXing, scanned with CameraX (no Play
    // services; docs/groups/SPEC.md §5.3, §10.4).
    implementation(libs.billing.ktx)
    implementation(libs.zxing.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // The car screens under Robolectric (TestCarContext, ScreenController), roads and places against a local server,
    // and the Compose screens under Robolectric (MapScreen with no map).
    testImplementation(libs.car.app.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
    // On the compile path too (the Compose rule brings it at run time): the walkthrough's idle timeouts.
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
