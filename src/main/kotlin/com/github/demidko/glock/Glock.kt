package com.github.demidko.glock

import java.lang.Thread.sleep
import java.lang.Thread.startVirtualThread
import java.time.Instant.ofEpochSecond
import java.time.LocalTime
import java.time.ZoneId
import com.google.gson.JsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor
import java.util.concurrent.Future
import kotlin.random.Random.Default.nextInt
import kotlin.random.Random.Default.nextLong
import kotlin.time.Duration

/** Where a bullet goes after striking armour. */
enum class Ricochet { HUNTER, BYSTANDER, MISS }

class Config(
  val seconds: Long = 300,
  val healingConstant: Long = 7,
  val zone: ZoneId = ZoneId.of("Asia/Jerusalem"),
  val boringChats: Set<Long> = emptySet()
)

fun main() {
  val env = System.getenv()
  val config = Config(
    seconds = Duration.parse(env["RESTRICTIONS_DURATION"] ?: "5m").inWholeSeconds,
    healingConstant = env["HEALING_CONSTANT"]?.toLong() ?: 7,
    zone = ZoneId.of(env["HEALING_TIME_ZONE"] ?: "Asia/Jerusalem"),
    boringChats = env["BORING_CHAT_IDS"]?.split(',')?.filter(String::isNotBlank)?.map { it.trim().toLong() }?.toSet() ?: emptySet()
  )
  require(config.seconds in 60..366L * 24 * 60 * 60) { "RESTRICTIONS_DURATION must be between 60 seconds and 366 days" }
  val tg = Telegram(env.getValue("BOT_TOKEN"))
  val glock = Glock(tg, Bans(tg, env.getValue("STORAGE_CHANNEL_ID").toLong()), config)
  println("I - using duration ${config.seconds}s")
  startVirtualThread { every(2_000, glock::maintain) }
  startVirtualThread { every(30_000, tg::ping) }
  var offset = 0L
  while (true) {
    val updates = tg.updates(offset)
    if (updates == null) {
      sleep(1_000)
      continue
    }
    for (update in updates) {
      offset = update["update_id"].asLong + 1
      // Every update is handled at once on its own thread; nothing waits for an earlier shot.
      update["message"]?.let { message -> startVirtualThread { glock.handle(Msg(message.asJsonObject)) } }
    }
  }
}

private fun every(millis: Long, action: () -> Unit) {
  while (true) {
    sleep(millis)
    runCatching(action).onFailure { warn("background task failed: $it") }
  }
}

/** The commands, their effects, and the kill feed. One instance serves every chat. */
class Glock(
  private val tg: Telegram,
  private val bans: Bans,
  private val config: Config,
  private val ricochet: () -> Ricochet = { Ricochet.entries.random() }
) {
  private val pool = newVirtualThreadPerTaskExecutor()
  private val recent = ConcurrentHashMap<Long, ArrayDeque<Msg>>()
  private val statuettes = ConcurrentHashMap<Long, ConcurrentLinkedQueue<Statuette>>()
  private val chats = ConcurrentHashMap.newKeySet<Long>()

  private class Statuette(val messageId: Long, val placer: Msg)

  /** One reserved hit: [apply] sends the restriction to Telegram. */
  private class Hit(val target: Msg, val targetId: Long, val prior: Long, var added: Long, val apply: () -> Boolean)

  fun handle(m: Msg) {
    try {
      if (chats.add(m.chatId)) {
        println("I - detected new chat ${describe(m.chat)}")
        pool.execute { bans.prefetch(m.chatId) }
      }
      if (m.text != null && m.chatId !in config.boringChats) println("I - ${describe(m.chat)} - ${describe(m.from)} - ${m.text}")
      // An armed statuette sees every message, commands included, before the command runs.
      if (!trap(m)) remember(m)
      val text = m.text ?: return
      if (!text.startsWith('/')) return
      val arg = text.substringAfter(' ', "").trim()
      when (text.substringBefore(' ').substringBefore('@').lowercase()) {
        "/shoot" -> shoot(m)
        "/buckshot" -> buckshot(m)
        "/statuette" -> statuette(m)
        "/heal" -> heal(m, arg)
        "/help", "/start" -> help(m)
      }
    } catch (e: Exception) {
      warn("update ${m.id} in chat ${m.chatId} (${m.text}) failed: $e")
      e.printStackTrace()
    }
  }

  fun maintain() = bans.maintain()

  private fun shoot(m: Msg) {
    delete(m)
    val target = m.replyTo ?: return
    fire(m, listOf(target to config.seconds), "🔫", target.threadId)
  }

  private fun buckshot(m: Msg) {
    delete(m)
    val candidates = recent[m.chatId]?.let { synchronized(it) { it.toList() } }.orEmpty().filter { it.senderId != m.senderId }
    if (candidates.isEmpty()) return
    val shots = if (candidates.size == 1) listOf(candidates.single() to config.seconds)
    else List(nextInt(2, candidates.size + 1)) { candidates.random() to nextLong(60, config.seconds * 2 + 1) }
    fire(m, shots, "🔫", m.threadId, logAfterHits = true)
  }

  private fun statuette(m: Msg) {
    delete(m)
    val id = tg.send(m.chatId, "🗿", m.threadId, replyTo = m.id) ?: return
    statuettes.computeIfAbsent(m.chatId) { ConcurrentLinkedQueue() }.add(Statuette(id, m))
  }

  /** Springs the oldest statuette of the chat on an eligible message; a refused restriction keeps it armed. */
  private fun trap(m: Msg): Boolean {
    val queue = statuettes[m.chatId] ?: return false
    val statuette = queue.peek() ?: return false
    if (m.isService || !(m.isChannelPost || m.senderChat == null && !m.isBot)) return false
    if (!fire(statuette.placer, listOf(m to config.seconds), "🗿", m.threadId, bounceToHunter = true)) return false
    if (queue.remove(statuette)) pool.execute { tg.delete(m.chatId, statuette.messageId) }
    return true
  }

  private fun heal(m: Msg, arg: String) {
    delete(m)
    val target = m.replyTo ?: return
    if (target.senderChat != null && !target.isChannelPost) return
    val targetId = target.senderId ?: return
    val time = LocalTime.ofInstant(ofEpochSecond(m.date), config.zone)
    // Congratulations, you've just found the secret healing code! Don't tell anyone about it, please.
    if (arg.toLongOrNull() != "${time.hour}${time.minute}".toLong() * config.healingConstant) return
    if (!bans.heal(target.chatId, targetId, target.isChannelPost)) return
    val reply = tg.send(m.chatId, listOf("💊", "💉", "🚑").random(), target.threadId, replyTo = target.id) ?: return
    later(3_000) { tg.delete(m.chatId, reply) }
  }

  private fun help(m: Msg) {
    later(20_000) { tg.delete(m.chatId, m.id) }
    val reply = tg.send(m.chatId, "Matthew 26:52 for all they that take the sword shall perish with the sword.", m.threadId, replyTo = m.id)
    if (reply != null) later(20_000) { tg.delete(m.chatId, reply) }
  }

  /**
   * Reserves every shot in memory, then sends the restrictions and the kill feed line at once, so a hit costs
   * one round trip. With [logAfterHits] the line waits for the restrictions and names only the confirmed ones.
   * A refused restriction is armour: the bullet ricochets into the hunter or a bystander, and the line is edited.
   */
  private fun fire(
    hunter: Msg, shots: List<Pair<Msg, Long>>, weapon: String, threadId: Long?,
    logAfterHits: Boolean = false, bounceToHunter: Boolean = false
  ): Boolean {
    val hits = shots.mapNotNull { (target, seconds) -> reserve(target, seconds) }
    if (hits.isEmpty()) return false
    val early = if (logAfterHits) null else pool.submit(Callable { tg.send(hunter.chatId, feed(hunter, hits, emptyList(), null, false, weapon), threadId) })
    val applied: List<Future<Boolean>> = hits.map { pool.submit(Callable(it.apply)) }
    val victims = hits.filterIndexed { index, _ -> applied[index].get() }.toMutableList()
    val armour = hits.filterIndexed { index, _ -> !applied[index].get() }
    var self: Hit? = null
    var missed = false
    for (shield in armour) {
      if (shield.targetId == hunter.senderId) continue
      val outcome = if (bounceToHunter) Ricochet.HUNTER else ricochet()
      val bystander = if (outcome == Ricochet.BYSTANDER) bystander(hunter, shield.targetId) else null
      if (outcome == Ricochet.MISS) {
        missed = true
        continue
      }
      val bounce = reserve(bystander ?: hunter, shield.added) ?: continue
      if (!bounce.apply()) {
        missed = true
        continue
      }
      if (bystander != null) victims += bounce else self = self?.also { it.added += bounce.added } ?: bounce
    }
    val line = feed(hunter, victims, armour.map { it.target }, self, missed, weapon)
    val earlyId = early?.get()
    when {
      earlyId == null -> tg.send(hunter.chatId, line, threadId)
      armour.isNotEmpty() -> tg.edit(hunter.chatId, earlyId, line)
    }
    return true
  }

  /** A random recent author of the chat other than the armoured target and the hunter. */
  private fun bystander(hunter: Msg, armouredId: Long): Msg? = recent[hunter.chatId]?.let { synchronized(it) { it.toList() } }
    ?.filter { it.senderId != null && it.senderId != armouredId && it.senderId != hunter.senderId && (it.senderChat == null || it.isChannelPost) }
    ?.randomOrNull()

  private fun reserve(target: Msg, seconds: Long): Hit? {
    if (target.senderChat != null && !target.isChannelPost) return null
    val id = target.senderId ?: return null
    val chatId = target.chatId
    return if (target.isChannelPost) {
      val plan = bans.reserveChannel(chatId, id, seconds)
      Hit(target, id, plan.prior, seconds) { bans.banChannel(chatId, id, plan.until) }
    } else {
      val plan = bans.reserveUser(chatId, id, seconds)
      Hit(target, id, plan.prior, seconds) { bans.restrictUser(chatId, id, plan.until) }
    }
  }

  /**
   * `@victim 🤐5m+5m 💥🔫 @hunter`: what was left plus what this hit added, minutes only, pellets merged by victim.
   * Armour faces the gun and takes the explosion; to its left is where the bullet went: `@bystander 🤐5m @admin🛡️💥🔫 @hunter`,
   * `💨 @admin🛡️💥🔫 @hunter` for a miss, and `@admin🛡️💥🔫 @hunter 🤐5m` when it came back into the hunter.
   */
  private fun feed(hunter: Msg, hits: List<Hit>, armour: List<Msg>, self: Hit?, missed: Boolean, weapon: String): String {
    val merged = LinkedHashMap<Long, Hit>()
    for (hit in hits) merged[hit.targetId]?.let { it.added += hit.added } ?: merged.put(hit.targetId, Hit(hit.target, hit.targetId, hit.prior, hit.added, hit.apply))
    val victims = merged.values.joinToString(" · ") { "${mention(it.target)} 🤐${time(it)}" }
    val shields = armour.distinctBy { it.senderId }.joinToString(" ") { "${mention(it)}🛡️" }
    // Shooting your own message, or your own armour, leaves nobody at the grip.
    val own = self == null && (merged.isNotEmpty() && merged.keys.all { it == hunter.senderId } || armour.any { it.senderId == hunter.senderId })
    val grip = (if (own) "" else " ${mention(hunter)}") + (self?.let { " 🤐${time(it)}" } ?: "")
    if (armour.isEmpty()) return "$victims 💥$weapon$grip"
    val flight = listOfNotNull(victims.takeIf(String::isNotEmpty), "💨".takeIf { missed }).joinToString(" ")
    return "${if (flight.isEmpty()) "" else "$flight "}$shields💥$weapon$grip"
  }

  private fun time(hit: Hit) = if (hit.prior >= 60) "${minutes(hit.prior)}+${minutes(hit.added)}" else minutes(hit.added)

  private fun minutes(seconds: Long): String = buildString {
    val days = seconds / 86400
    val hours = seconds % 86400 / 3600
    val minutes = seconds % 3600 / 60
    if (days > 0) append(days).append('d')
    if (hours > 0) append(hours).append('h')
    if (minutes > 0 || isEmpty()) append(minutes).append('m')
  }

  private fun mention(m: Msg): String {
    val channel = m.senderChat
    if (channel != null) {
      val username = channel["username"]?.asString?.takeIf(String::isNotBlank)
      if (username != null) return link("https://t.me/$username", "@$username")
      val title = channel["title"]?.asString?.takeIf(String::isNotBlank)
      return escape(if (title != null) "$title (${channel["id"].asLong})" else channel["id"].asString)
    }
    val user = m.from ?: return "?"
    val name = listOfNotNull(user["first_name"]?.asString, user["last_name"]?.asString).filter(String::isNotBlank).joinToString(" ")
    val label = user["username"]?.asString?.takeIf(String::isNotBlank)?.let { "@$it" } ?: name.takeIf(String::isNotBlank) ?: user["id"].asString
    return link("tg://user?id=${user["id"].asLong}", label)
  }

  private fun link(url: String, label: String) = "<a href=\"${escape(url)}\">${escape(label)}</a>"

  private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    .replace("\"", "&quot;").replace('\n', ' ').replace('\r', ' ')

  private fun remember(m: Msg) {
    val deque = recent.computeIfAbsent(m.chatId) { ArrayDeque(RECENT_MESSAGES) }
    synchronized(deque) {
      if (deque.size == RECENT_MESSAGES) deque.removeFirst()
      deque.addLast(m)
    }
  }

  private fun delete(m: Msg) {
    pool.execute { tg.delete(m.chatId, m.id) }
  }

  private fun later(millis: Long, action: () -> Unit) {
    startVirtualThread {
      sleep(millis)
      action()
    }
  }

  /** `id 42 Title First Last @username` for a chat or a user in the console log. */
  private fun describe(subject: JsonObject?): String = if (subject == null) "" else listOfNotNull(
    "id ${subject["id"].asLong}", subject["title"]?.asString, subject["first_name"]?.asString, subject["last_name"]?.asString,
    subject["username"]?.asString?.let { "@$it" }
  ).joinToString(" ")

  private companion object {
    const val RECENT_MESSAGES = 12
  }
}
