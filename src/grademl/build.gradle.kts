plugins {
    `java-library`
    kotlin("jvm") version "1.6.10" apply false
    kotlin("plugin.serialization") version "1.6.10" apply false
}

allprojects {
    group = "science.atlarge.grademl"
    version = "0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }

    apply(plugin = "java-library")
    java.sourceCompatibility = JavaVersion.VERSION_11
    java.targetCompatibility = JavaVersion.VERSION_11
}
