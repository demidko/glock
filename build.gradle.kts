import org.gradle.api.JavaVersion.VERSION_21
import org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21

plugins {
  kotlin("jvm") version "2.1.0-Beta1"
  kotlin("plugin.serialization") version "2.1.0-Beta1"
  application
}

group = "com.github.demidko"
version = "0.0.1-SNAPSHOT"

repositories {
  mavenCentral()
  maven("https://jitpack.io")
}

dependencies {
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-cbor:1.7.3")
  implementation("org.apache.commons:commons-collections4:4.4")
  implementation("io.github.kotlin-telegram-bot.kotlin-telegram-bot:telegram:6.2.0")
  implementation("org.jetbrains.kotlin:kotlin-reflect:2.1.0-Beta1")
  implementation("com.sksamuel.hoplite:hoplite-core:2.8.0")
  implementation("com.squareup.retrofit2:retrofit:2.11.0")
  testImplementation("com.google.truth:truth:1.4.4")
  testImplementation("io.mockk:mockk:1.13.12")
  testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.0")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.0")
}

application {
  mainClass.set("com.github.demidko.glock.GlockApplicationKt")
  applicationDefaultJvmArgs += "--enable-preview"
}

kotlin {
  compilerOptions {
    jvmTarget = JVM_21
    freeCompilerArgs.addAll(
      "-Xjsr305=strict",
      "-Xvalue-classes",
      "-opt-in=kotlin.ExperimentalStdlibApi",
      "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
      "-opt-in=kotlin.time.ExperimentalTime"
    )
  }
}

java {
  sourceCompatibility = VERSION_21
  targetCompatibility = VERSION_21
}

tasks.test {
  useJUnitPlatform()
  jvmArgs("--enable-preview")
}
