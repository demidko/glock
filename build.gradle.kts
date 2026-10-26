import org.gradle.api.JavaVersion.VERSION_21
import org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21

plugins {
  kotlin("jvm") version "2.4.21"
  application
}

group = "com.github.demidko"
version = "0.0.1-SNAPSHOT"

repositories {
  mavenCentral()
}

dependencies {
  implementation(platform("com.squareup.okhttp3:okhttp-bom:5.5.1"))
  implementation("com.squareup.okhttp3:okhttp")
  implementation("com.google.code.gson:gson:2.15.0")
  testImplementation("com.google.truth:truth:1.4.5")
  testImplementation("org.junit.jupiter:junit-jupiter-api:6.1.3")
  testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:6.1.3")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
}

application {
  mainClass.set("com.github.demidko.glock.GlockKt")
}

kotlin {
  jvmToolchain(21)
  compilerOptions {
    jvmTarget = JVM_21
    freeCompilerArgs.addAll("-Xjsr305=strict", "-opt-in=kotlin.time.ExperimentalTime")
  }
}

java {
  sourceCompatibility = VERSION_21
  targetCompatibility = VERSION_21
}

tasks.test {
  useJUnitPlatform()
}
