package com.github.demidko.glock

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.ConcurrentHashMap

/** What a hit reserved: the time that was left before it and the new deadline. */
class Plan(val prior: Long, val until: Long)

/**
 * Mutes live in memory: Telegram lifts timed user restrictions by itself. Channel bans have no timer in the
 * Bot API, so their deadlines are also kept in the storage channel's pinned message and lifted by [maintain].
 */
class Bans(private val tg: Telegram, private val storageChannel: Long, private val now: () -> Long = { System.currentTimeMillis() / 1000 }) {
  private val users = ConcurrentHashMap<String, Long>()
  private val channels = ConcurrentHashMap<String, Long>()
  private val defaults = ConcurrentHashMap<Long, Pair<Long, JsonObject>>()
  @Volatile private var dirty = false
  private var pinned: Long? = null

  init {
    tg.pinned(storageChannel)?.let { (id, text) ->
      pinned = id
      runCatching { JsonParser.parseString(text).asJsonObject.entrySet().forEach { channels[it.key] = it.value.asLong } }
        .onFailure { warn("storage channel $storageChannel holds no readable ban deadlines: $it") }
    }
  }

  /** Adds [seconds] to the target's deadline in memory; the single lock-free step that makes concurrent hits stack. */
  fun reserveUser(chatId: Long, userId: Long, seconds: Long) = reserve(users, "$chatId:$userId", seconds)

  fun reserveChannel(chatId: Long, channelId: Long, seconds: Long) = reserve(channels, "$chatId:$channelId", seconds)

  /** Applies a reserved user mute; a refused one (administrators, absent members) is forgotten. */
  fun restrictUser(chatId: Long, userId: Long, until: Long): Boolean {
    val template = defaults(chatId)
    if (template != null && tg.restrict(chatId, userId, muted(template), until)) return true
    users.remove("$chatId:$userId", until)
    return false
  }

  fun banChannel(chatId: Long, channelId: Long, until: Long): Boolean {
    if (tg.banChannel(chatId, channelId)) {
      dirty = true
      return true
    }
    channels.remove("$chatId:$channelId", until)
    return false
  }

  /** Lifts any individual restriction: the group's own defaults make the member a regular member again. */
  fun heal(chatId: Long, targetId: Long, channel: Boolean): Boolean {
    if (channel) {
      if (!tg.unbanChannel(chatId, targetId)) return false
      if (channels.remove("$chatId:$targetId") != null) dirty = true
      return true
    }
    val template = defaults(chatId) ?: return false
    if (!tg.restrict(chatId, targetId, template, 0)) return false
    users.remove("$chatId:$targetId")
    return true
  }

  /** Reads the group's defaults ahead of the first shot there. */
  fun prefetch(chatId: Long) {
    defaults(chatId)
  }

  /** Unbans channels whose time is up, saves changed deadlines, and drops expired user records. */
  fun maintain() {
    val time = now()
    for ((key, until) in channels) {
      if (until > time) continue
      val (chatId, channelId) = key.split(':').map(String::toLong)
      if (tg.unbanChannel(chatId, channelId) && channels.remove(key, until)) dirty = true
    }
    users.entries.removeIf { it.value <= time }
    if (dirty) {
      dirty = false
      val text = Gson().toJson(channels)
      val saved = pinned?.let { tg.edit(storageChannel, it, text) }
        ?: tg.send(storageChannel, text)?.also { pinned = it }?.let { tg.pin(storageChannel, it) }
      if (saved != true) dirty = true
    }
  }

  private fun reserve(map: ConcurrentHashMap<String, Long>, key: String, seconds: Long): Plan {
    var prior = 0L
    val until = map.compute(key) { _, old ->
      val time = now()
      prior = maxOf((old ?: 0) - time, 0)
      minOf(time + prior + seconds, time + MAX_SECONDS)
    }
    return Plan(prior, until!!)
  }

  private fun defaults(chatId: Long): JsonObject? {
    val time = now()
    defaults[chatId]?.takeIf { it.first > time }?.let { return it.second }
    val loaded = tg.permissions(chatId) ?: return null
    defaults[chatId] = time + DEFAULTS_CACHE_SECONDS to loaded
    return loaded
  }

  private fun muted(template: JsonObject) = template.deepCopy().apply { SEND_PERMISSIONS.forEach { addProperty(it, false) } }

  private companion object {
    const val MAX_SECONDS = 366L * 24 * 60 * 60
    const val DEFAULTS_CACHE_SECONDS = 300L
    val SEND_PERMISSIONS = listOf(
      "can_send_messages", "can_send_audios", "can_send_documents", "can_send_photos", "can_send_videos",
      "can_send_video_notes", "can_send_voice_notes", "can_send_polls", "can_send_other_messages",
      "can_add_web_page_previews"
    )
  }
}
