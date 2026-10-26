import org.gradle.api.JavaVersion.VERSION_21
import org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21

plugins {
  kotlin("jvm") version "2.4.21"
  kotlin("plugin.serialization") version "2.4.21"
  application
}

group = "com.github.demidko"
version = "0.0.1-SNAPSHOT"

repositories {
  mavenCentral()
  maven("https://jitpack.io")
}

dependencies {
  implementation("com.google.guava:guava:33.7.2-jre")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-cbor:1.12.0")
  implementation("org.apache.commons:commons-collections4:4.6.0")
  implementation("io.github.kotlin-telegram-bot.kotlin-telegram-bot:telegram:10.0.0")
  implementation("org.jetbrains.kotlin:kotlin-reflect:2.4.21")
  implementation("com.sksamuel.hoplite:hoplite-core:3.0.5")
  implementation(platform("com.squareup.okhttp3:okhttp-bom:5.5.1"))
  implementation("com.google.code.gson:gson:2.15.0")
  implementation("com.squareup.retrofit2:retrofit:3.0.0")
  implementation("com.squareup.retrofit2:converter-gson:3.0.0")
  testImplementation("com.google.truth:truth:1.4.5")
  testImplementation("io.mockk:mockk:1.14.11")
  testImplementation("org.junit.jupiter:junit-jupiter-api:6.1.3")
  testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:6.1.3")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
}

application {
  mainClass.set("com.github.demidko.glock.GlockApplicationKt")
  applicationDefaultJvmArgs += "--enable-preview"
}

kotlin {
  jvmToolchain(21)
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
