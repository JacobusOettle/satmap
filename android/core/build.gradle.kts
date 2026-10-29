import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Platform-independent logic, ported from SatMap/Core (Swift): runs and is tested on any JVM.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    // Part of core's API (OfflineArea.toJson/fromJson, GeoPoint, CoverageShape …), so the app compiles against it too.
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    // MBTiles runs on SqlDriver: sqlite-jdbc in the tests, android.database.sqlite in the app.
    testImplementation(libs.sqlite.jdbc)
}

tasks.test {
    // The iOS app's shipped data (Aviation.tsv, KrugerRoads.json) and the Swift tests' fixtures, read in place.
    systemProperty("satmap.resources", rootProject.file("../SatMap/Resources").absolutePath)
    systemProperty("satmap.fixtures", rootProject.file("../Tests/SatMapCoreTests/Fixtures").absolutePath)
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
