package com.github.demidko.glock

import com.github.kotlintelegrambot.entities.ChatId.Companion.fromId
import com.github.kotlintelegrambot.entities.ChatPermissions
import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.addEnvironmentSource
import java.time.Duration
import java.time.Duration.ofMinutes
import java.time.ZoneId

open class ApplicationFactory {
  data class Config(
    val botToken: String,
    val storageId: Long,
    val healingConstant: Long = 7,
    val healingTimeZone: String = "Asia/Jerusalem",
    val restrictionsDuration: Duration = ofMinutes(5)
  )

  open val config by lazy {
    ConfigLoaderBuilder.default()
      .addEnvironmentSource()
      .build()
      .loadConfigOrThrow<Config>()
  }

  init {
    println(config.restrictionsDuration)
  }

  open val restrictions = ChatPermissions(
    canSendMessages = false,
    canSendMediaMessages = false,
    canSendPolls = false,
    canSendOtherMessages = false,
    canAddWebPagePreviews = false,
    canChangeInfo = false,
    canInviteUsers = false,
    canPinMessages = false
  )

  open val glockBot by lazy {
    GlockBot(
      config.botToken,
      fromId(config.storageId),
      restrictions,
      config.restrictionsDuration,
      config.healingConstant,
      ZoneId.of(config.healingTimeZone)
    )
  }
}