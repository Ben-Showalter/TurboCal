import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM: everything in here is testable without Android (AGENTS.md §3).
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_1_8) }
}

dependencies {
    api(libs.ical4j)
    implementation(libs.coroutines.core)
    compileOnly(libs.orgjson)

    testImplementation(libs.junit4)
    testImplementation(libs.orgjson)
    testImplementation(libs.coroutines.test)
}

tasks.test {
    // Never let the build machine's zone leak into results; tests pin their own.
    systemProperty("user.timezone", "UTC")
}
