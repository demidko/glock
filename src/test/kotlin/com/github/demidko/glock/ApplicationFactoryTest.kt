package com.github.demidko.glock

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

class ApplicationFactoryTest {
  @Test
  fun `documented environment keys load required settings and defaults`() {
    val config = ApplicationFactory(mapOf("BOT_TOKEN" to "test-token", "STORAGE_CHANNEL_ID" to "-999")).config

    assertThat(config.botToken).isEqualTo("test-token")
    assertThat(config.storageChannelId).isEqualTo(-999L)
    assertThat(config.healingConstant).isEqualTo(7L)
    assertThat(config.healingTimeZone).isEqualTo("Asia/Jerusalem")
    assertThat(config.restrictionsDuration).isEqualTo(Duration.ofMinutes(5))
    assertThat(config.boringChatIds).isEmpty()
  }

  @Test
  fun `all uppercase snake case settings override defaults`() {
    assertOverrides(
      mapOf(
        "BOT_TOKEN" to "test-token",
        "STORAGE_CHANNEL_ID" to "-999",
        "HEALING_CONSTANT" to "11",
        "HEALING_TIME_ZONE" to "UTC",
        "RESTRICTIONS_DURATION" to "90s",
        "BORING_CHAT_IDS" to "-10,-20"
      )
    )
  }

  @Test
  fun `camel case environment settings remain supported`() {
    assertOverrides(
      mapOf(
        "botToken" to "test-token",
        "storageChannelId" to "-999",
        "healingConstant" to "11",
        "healingTimeZone" to "UTC",
        "restrictionsDuration" to "90s",
        "boringChatIds" to "-10,-20"
      )
    )
  }

  private fun assertOverrides(environment: Map<String, String>) {
    val config = ApplicationFactory(environment).config

    assertThat(config.botToken).isEqualTo("test-token")
    assertThat(config.storageChannelId).isEqualTo(-999L)
    assertThat(config.healingConstant).isEqualTo(11L)
    assertThat(config.healingTimeZone).isEqualTo("UTC")
    assertThat(config.restrictionsDuration).isEqualTo(Duration.ofSeconds(90))
    assertThat(config.boringChatIds).containsExactly(-10L, -20L)
  }
}
