// Plugins go on the root classpath (not per-module `plugins { version }`) so the
// Kotlin plugin and the Android plugin share one classloader. The Android ones are
// skipped in a JVM-only build (-Pturbocal.jvmOnly=true), which needs no Google repo.
buildscript {
    val jvmOnly = project.findProperty("turbocal.jvmOnly") == "true"
    repositories {
        if (!jvmOnly) google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.0")
        if (!jvmOnly) {
            classpath("com.android.tools.build:gradle:8.7.3")
            classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.1.0-1.0.29")
        }
    }
}
