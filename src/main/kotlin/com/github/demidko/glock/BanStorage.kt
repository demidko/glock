package com.github.demidko.glock

import com.github.demidko.telegram.TelegramStorage.Constructors.TelegramStorage
import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import java.io.Closeable

interface BanStorage {
  fun load(): Map<String, Long>
  fun save(deadlines: Map<String, Long>)
}

class TelegramBanStorage(bot: Bot, channel: ChatId) : BanStorage, Closeable {
  private val storage = TelegramStorage<String, Map<String, Long>>(bot, channel)

  @Synchronized
  override fun load(): Map<String, Long> {
    if (storage.isEmpty()) {
      return emptyMap()
    }
    return checkNotNull(storage[SNAPSHOT_KEY]) { "Ban snapshot is missing from the storage channel" }
  }

  @Synchronized
  override fun save(deadlines: Map<String, Long>) {
    storage[SNAPSHOT_KEY] = deadlines
  }

  @Synchronized
  override fun close() {
    storage.close()
  }

  private companion object {
    const val SNAPSHOT_KEY = "sender-chat-bans"
  }
}
