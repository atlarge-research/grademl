description = "Parsing for Spark log files"

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    implementation(project(":grademl-core"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.2")
}
