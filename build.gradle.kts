
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
}

group = "com.example"
version = "1.0.0-SNAPSHOT"

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

kotlin {
    jvmToolchain(25)
}

dependencies {

    implementation(libs.ktor.server.config.yaml)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)

    implementation(libs.ktor.server.contentNegotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.ktor.server.callLogging)
    implementation(libs.ktor.server.statusPages)

    implementation(libs.ktor.server.cors)

    testImplementation(libs.ktor.server.testHost)

    implementation(libs.logback.classic)

    implementation("org.xerial:sqlite-jdbc:3.50.3.0")

    implementation("com.zaxxer:HikariCP:6.3.0")

    implementation("org.jetbrains.exposed:exposed-core:0.61.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.61.0")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation(kotlin("test"))
    implementation("org.postgresql:postgresql:42.7.7")
}

