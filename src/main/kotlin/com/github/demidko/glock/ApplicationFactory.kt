package com.github.demidko.glock

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.ExperimentalHoplite
import com.sksamuel.hoplite.addMapSource
import java.time.Duration
import java.time.Duration.ofMinutes
import java.time.ZoneId
import kotlin.time.toKotlinDuration

open class ApplicationFactory(private val environment: Map<String, String> = System.getenv()) {
  data class Config(
    val botToken: String,
    val storageChannelId: Long,
    val healingConstant: Long = 7,
    val healingTimeZone: String = "Asia/Jerusalem",
    val restrictionsDuration: Duration = ofMinutes(5),
    val boringChatIds: Set<Long> = emptySet(),
  )

  @OptIn(ExperimentalHoplite::class)
  open val config by lazy {
    ConfigLoaderBuilder.default()
      // Keep flat UPPER_SNAKE_CASE keys: Hoplite 3 treats environment underscores as nested paths.
      .addMapSource(environment)
      .withExplicitSealedTypes()
      .build()
      .loadConfigOrThrow<Config>()
  }

  init {
    val duration = config.restrictionsDuration.toKotlinDuration()
    println("I - using duration $duration")
  }

  open val glockBot by lazy {
    GlockBot(
      config.botToken,
      config.restrictionsDuration,
      config.healingConstant,
      ZoneId.of(config.healingTimeZone),
      config.boringChatIds,
      config.storageChannelId,
    )
  }
}
