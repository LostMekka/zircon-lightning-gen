plugins {
    kotlin("jvm") version "1.6.10"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val zirconVersion = "2021.1.0-RELEASE"

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.hexworks.zircon:zircon.core-jvm:$zirconVersion")
    implementation("org.hexworks.zircon:zircon.jvm.swing:$zirconVersion")
}
