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
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    // The iOS app's shipped data (Aviation.tsv, KrugerRoads.json) and the Swift tests' fixtures, read in place.
    systemProperty("satmap.resources", rootProject.file("../SatMap/Resources").absolutePath)
    systemProperty("satmap.fixtures", rootProject.file("../Tests/SatMapCoreTests/Fixtures").absolutePath)
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
