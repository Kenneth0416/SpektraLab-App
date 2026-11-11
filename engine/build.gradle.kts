import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // Engine data is baked from spektrafilm into the app assets.
    systemProperty("spektra.assets", rootProject.file("app/src/main/assets/spektra").absolutePath)
    systemProperty("spektra.rawdump", System.getProperty("spektra.rawdump") ?: "")
    systemProperty("spektra.gldump", System.getProperty("spektra.gldump") ?: "")
    testLogging { events("passed", "failed"); showStandardStreams = true }
}
