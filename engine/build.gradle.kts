// The engine: upstream Nostalgex's channel filter, daily scheduler and seasons, ported to plain
// Kotlin with no dependencies, plus the Plex and Jellyfin/Emby clients. Tested against upstream's
// golden vectors on a bare JVM (`./gradlew :engine:test`, or tools/check-engine.sh).
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // The checks find app/src/main/assets by walking up from here.
    workingDir = projectDir
    testLogging {
        events("passed", "failed")
        showStandardStreams = true
    }
}
