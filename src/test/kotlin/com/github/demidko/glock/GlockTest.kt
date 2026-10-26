package com.github.demidko.glock

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.Test
import java.time.ZoneOffset.UTC
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicLong

class GlockTest {
  private val tg = FakeTelegram()
  private var now = 1_000L
  private val bans = Bans(tg, STORAGE) { now }
  private var ricochet = Ricochet.HUNTER
  private val glock = Glock(tg, bans, Config(seconds = 300, healingConstant = 7, zone = UTC)) { ricochet }
  private val admin = user(44, "admin")
  private val shield = "<a href=\"tg://user?id=44\">@admin</a>🛡️"
  private val hunter = user(22, "hunter")
  private val victim = user(11, "victim")
  private val victimLink = "<a href=\"tg://user?id=11\">@victim</a>"
  private val hunterLink = "<a href=\"tg://user?id=22\">@hunter</a>"

  @Test
  fun `a shot mutes the sender of the replied message and logs it`() {
    val target = message(1, victim, "hi")
    glock.handle(target)

    glock.handle(message(2, hunter, "/shoot", replyTo = target))

    assertThat(tg.sent.map { it.text }).containsExactly("$victimLink 🤐5m 💥🔫 $hunterLink")
    val restriction = tg.restricted.single()
    assertThat(restriction.userId).isEqualTo(11)
    assertThat(restriction.until).isEqualTo(1_300)
    assertThat(restriction.permissions["can_send_messages"].asBoolean).isFalse()
    assertThat(restriction.permissions["can_react_to_messages"].asBoolean).isTrue()
    assertThat(restriction.permissions["can_pin_messages"].asBoolean).isFalse()
    await { 2L in tg.deleted }
  }

  @Test
  fun `repeated hits stack and the feed shows what was left plus the addition`() {
    val target = message(1, victim, "hi")
    glock.handle(message(2, hunter, "/shoot@glockkobot", replyTo = target))
    now = 1_100

    glock.handle(message(3, hunter, "/shoot", replyTo = target))

    assertThat(tg.sent.last().text).isEqualTo("$victimLink 🤐3m+5m 💥🔫 $hunterLink")
    assertThat(tg.restricted.last().until).isEqualTo(1_600)
  }

  @Test
  fun `seconds are dropped and hours and days are compact`() {
    val target = message(1, victim, "hi")
    Glock(tg, bans, Config(seconds = 5_400, zone = UTC)).handle(message(2, hunter, "/shoot", replyTo = target))
    assertThat(tg.sent.last().text).startsWith("$victimLink 🤐1h30m 💥🔫")

    now += 5_400 - 50
    Glock(tg, bans, Config(seconds = 2 * 86_400 + 3 * 3_600, zone = UTC)).handle(message(3, hunter, "/shoot", replyTo = target))
    assertThat(tg.sent.last().text).startsWith("$victimLink 🤐2d3h 💥🔫")
  }

  @Test
  fun `shooting your own message leaves nobody at the grip`() {
    val own = message(1, hunter, "hi")

    glock.handle(message(2, hunter, "/shoot", replyTo = own))

    assertThat(tg.sent.single().text).isEqualTo("$hunterLink 🤐5m 💥🔫")
  }

  @Test
  fun `armour bounces the bullet back into the hunter and the early line is edited`() {
    tg.refuse = setOf(44)
    val target = message(1, admin, "I am armoured")

    glock.handle(message(2, hunter, "/shoot", replyTo = target))

    assertThat(tg.restricted.single().userId).isEqualTo(22)
    assertThat(tg.restricted.single().until).isEqualTo(1_300)
    assertThat(tg.sent.single().text).isEqualTo("<a href=\"tg://user?id=44\">@admin</a> 🤐5m 💥🔫 $hunterLink")
    val edit = tg.edits.single()
    assertThat(edit.first).isEqualTo(tg.sent.single().id)
    assertThat(edit.second).isEqualTo("$shield💥🔫 $hunterLink 🤐5m")
    glock.handle(message(3, hunter, "/shoot", replyTo = target))
    assertThat(tg.edits.last().second).isEqualTo("$shield💥🔫 $hunterLink 🤐5m+5m")
  }

  @Test
  fun `a ricochet can hit a bystander and misses when the bystander is armoured too`() {
    tg.refuse = setOf(44)
    ricochet = Ricochet.BYSTANDER
    val other = user(33, "other")
    glock.handle(message(1, other, "just passing by"))
    val target = message(2, admin, "I am armoured")

    glock.handle(message(3, hunter, "/shoot", replyTo = target))

    assertThat(tg.restricted.single().userId).isEqualTo(33)
    assertThat(tg.edits.last().second).isEqualTo("<a href=\"tg://user?id=33\">@other</a> 🤐5m $shield💥🔫 $hunterLink")

    tg.refuse = setOf(44, 33)
    glock.handle(message(4, hunter, "/shoot", replyTo = target))

    assertThat(tg.restricted).hasSize(1)
    assertThat(tg.edits.last().second).isEqualTo("$shield💨 💥🔫 $hunterLink")
  }

  @Test
  fun `a ricochet can miss everyone`() {
    tg.refuse = setOf(44)
    ricochet = Ricochet.MISS
    glock.handle(message(1, victim, "around"))

    glock.handle(message(3, hunter, "/shoot", replyTo = message(2, admin, "I am armoured")))

    assertThat(tg.restricted).isEmpty()
    assertThat(tg.edits.single().second).isEqualTo("$shield💨 💥🔫 $hunterLink")
  }

  @Test
  fun `an armoured hunter shooting their own message gets a bare shield`() {
    tg.refuse = setOf(44)

    glock.handle(message(2, admin, "/shoot", replyTo = message(1, admin, "mine")))

    assertThat(tg.restricted).isEmpty()
    assertThat(tg.edits.single().second).isEqualTo("$shield💥🔫")
  }

  @Test
  fun `a statuette stepped on by armour ricochets into its placer and is consumed`() {
    tg.refuse = setOf(44)
    ricochet = Ricochet.MISS
    glock.handle(message(1, hunter, "/statuette"))
    val statuette = tg.sent.single()

    glock.handle(message(2, admin, "stomp"))

    assertThat(tg.restricted.single().userId).isEqualTo(22)
    assertThat(tg.edits.single().second).isEqualTo("$shield💥🗿 $hunterLink 🤐5m")
    await { statuette.id in tg.deleted }
    glock.handle(message(3, victim, "safe now"))
    assertThat(tg.restricted).hasSize(1)
  }

  @Test
  fun `the feed line is sent while the restriction is still in flight`() {
    val logged = CountDownLatch(1)
    tg.onSend = { logged.countDown() }
    var parallel = false
    tg.onRestrict = { parallel = logged.await(2, SECONDS) }

    glock.handle(message(2, hunter, "/shoot", replyTo = message(1, victim, "hi")))

    assertThat(parallel).isTrue()
  }

  @Test
  fun `buckshot hits recent authors but never the shooter`() {
    val other = user(33, "other")
    for (id in 1L..4L) glock.handle(message(id, if (id % 2 == 0L) victim else other, "msg $id"))
    glock.handle(message(5, hunter, "me too"))

    glock.handle(message(6, hunter, "/buckshot"))

    val line = tg.sent.single().text
    assertThat(line).endsWith(" 💥🔫 $hunterLink")
    assertThat(line).doesNotContain("@hunter</a> 🤐")
    assertThat(tg.restricted.map { it.userId }.toSet()).isEqualTo(line.split(" · ").map { if ("@victim" in it) 11L else 33L }.toSet())
    assertThat(tg.restricted.none { it.userId == 22L }).isTrue()
    // Two to four pellets of 60..600 seconds each may land on the same victim and stack.
    assertThat(tg.restricted.all { it.until - now in 60..2_400 }).isTrue()
    assertThat(tg.restricted.size).isIn(2..4)
  }

  @Test
  fun `a statuette hits the next eligible message and is then consumed`() {
    glock.handle(message(1, hunter, "/statuette"))
    val statuette = tg.sent.single()
    assertThat(statuette.text).isEqualTo("🗿")
    assertThat(statuette.replyTo).isEqualTo(1)

    glock.handle(message(2, user(99, bot = true), "beep"))
    glock.handle(Msg(JsonParser.parseString("""{"message_id":3,"date":1,"chat":{"id":$CHAT},"new_chat_members":[]}""").asJsonObject))
    assertThat(tg.restricted).isEmpty()

    glock.handle(message(4, victim, "walks in"))

    assertThat(tg.restricted.single().userId).isEqualTo(11)
    assertThat(tg.sent.last().text).isEqualTo("$victimLink 🤐5m 💥🗿 $hunterLink")
    await { statuette.id in tg.deleted }
    glock.handle(message(5, victim, "again"))
    assertThat(tg.restricted).hasSize(1)
  }

  @Test
  fun `healing needs the code of the send time and sends the group defaults`() {
    val target = message(1, victim, "hi")
    glock.handle(message(2, hunter, "/shoot", replyTo = target))
    val sentAt = 10 * 3_600L + 5 * 60
    glock.handle(message(3, hunter, "/heal 1", replyTo = target, date = sentAt))
    assertThat(tg.restricted).hasSize(1)

    glock.handle(message(4, hunter, "/heal ${105 * 7}", replyTo = target, date = sentAt))

    val lift = tg.restricted.last()
    assertThat(lift.until).isEqualTo(0)
    assertThat(lift.permissions).isEqualTo(tg.defaults)
    assertThat(tg.sent.last().text).isIn(listOf("💊", "💉", "🚑"))
    assertThat(tg.sent.last().replyTo).isEqualTo(1)
    glock.handle(message(5, hunter, "/shoot", replyTo = target))
    assertThat(tg.sent.last().text).isEqualTo("$victimLink 🤐5m 💥🔫 $hunterLink")
  }

  @Test
  fun `channel senders are banned with a saved deadline and unbanned when it passes`() {
    val channel = JsonObject().apply { addProperty("id", -100500); addProperty("type", "channel"); addProperty("username", "news") }
    val post = message(1, user(136817688, first = "Channel"), "post", senderChat = channel)

    glock.handle(message(2, hunter, "/shoot", replyTo = post))

    assertThat(tg.banned).containsExactly(-100500L)
    assertThat(tg.sent.last().text).isEqualTo("<a href=\"https://t.me/news\">@news</a> 🤐5m 💥🔫 $hunterLink")
    glock.maintain()
    assertThat(tg.pinnedText).isEqualTo("""{"$CHAT:-100500":1300}""")

    now = 1_300
    Bans(tg, STORAGE) { now }.maintain()

    assertThat(tg.unbanned).containsExactly(-100500L)
    assertThat(tg.pinnedText).isEqualTo("{}")
  }

  @Test
  fun `anonymous administrators cannot be targeted`() {
    val group = JsonObject().apply { addProperty("id", CHAT); addProperty("type", "supergroup") }
    val post = message(1, user(1087968824, first = "Group"), "anon", senderChat = group)

    glock.handle(message(2, hunter, "/shoot", replyTo = post))

    assertThat(tg.sent).isEmpty()
    assertThat(tg.restricted).isEmpty()
    assertThat(tg.banned).isEmpty()
  }

  @Test
  fun `help answers with the rules`() {
    glock.handle(message(1, hunter, "/help"))

    assertThat(tg.sent.single().text).startsWith("Matthew 26:52")
    assertThat(tg.sent.single().replyTo).isEqualTo(1)
  }

  private fun await(condition: () -> Boolean) {
    val deadline = System.nanoTime() + 2_000_000_000
    while (!condition()) {
      check(System.nanoTime() < deadline) { "Condition not met in time" }
      Thread.sleep(5)
    }
  }

  private fun user(id: Long, username: String? = null, first: String = "User$id", bot: Boolean = false) = JsonObject().apply {
    addProperty("id", id)
    addProperty("is_bot", bot)
    addProperty("first_name", first)
    if (username != null) addProperty("username", username)
  }

  private fun message(
    id: Long, from: JsonObject, text: String? = null, replyTo: Msg? = null, senderChat: JsonObject? = null, date: Long = now
  ): Msg = Msg(JsonObject().apply {
    addProperty("message_id", id)
    addProperty("date", date)
    add("chat", JsonObject().apply { addProperty("id", CHAT); addProperty("type", "supergroup"); addProperty("title", "Range") })
    add("from", from)
    if (senderChat != null) add("sender_chat", senderChat)
    if (text != null) addProperty("text", text)
    if (replyTo != null) add("reply_to_message", replyTo.json)
  })

  private companion object {
    const val CHAT = -10042L
    const val STORAGE = -100900L
  }
}

class FakeTelegram : Telegram("token") {
  class Sent(val id: Long, val chatId: Long, val text: String, val threadId: Long?, val replyTo: Long?)
  class Restricted(val chatId: Long, val userId: Long, val permissions: JsonObject, val until: Long)

  val sent = CopyOnWriteArrayList<Sent>()
  val restricted = CopyOnWriteArrayList<Restricted>()
  val deleted = CopyOnWriteArrayList<Long>()
  val banned = CopyOnWriteArrayList<Long>()
  val unbanned = CopyOnWriteArrayList<Long>()
  val edits = CopyOnWriteArrayList<Pair<Long, String>>()
  var refuse = setOf<Long>()
  var onSend: () -> Unit = {}
  var onRestrict: () -> Unit = {}
  var pinnedId: Long? = null
  var pinnedText: String? = null
  val defaults: JsonObject = JsonObject().apply {
    listOf(
      "can_send_messages", "can_send_audios", "can_send_documents", "can_send_photos", "can_send_videos",
      "can_send_video_notes", "can_send_voice_notes", "can_send_polls", "can_send_other_messages",
      "can_add_web_page_previews", "can_react_to_messages", "can_edit_tag", "can_change_info", "can_invite_users",
      "can_pin_messages", "can_manage_topics"
    ).forEach { addProperty(it, it != "can_pin_messages") }
  }
  private val ids = AtomicLong(1_000)

  override fun updates(offset: Long) = emptyList<JsonObject>()
  override fun ping() = Unit
  override fun send(chatId: Long, html: String, threadId: Long?, replyTo: Long?): Long {
    onSend()
    return ids.incrementAndGet().also { sent += Sent(it, chatId, html, threadId, replyTo) }
  }
  override fun edit(chatId: Long, messageId: Long, html: String): Boolean {
    if (messageId == pinnedId) pinnedText = html else edits += messageId to html
    return true
  }
  override fun pin(chatId: Long, messageId: Long): Boolean {
    pinnedId = messageId
    pinnedText = sent.single { it.id == messageId }.text
    return true
  }
  override fun delete(chatId: Long, messageId: Long) = deleted.add(messageId)
  override fun restrict(chatId: Long, userId: Long, permissions: JsonObject, until: Long): Boolean {
    onRestrict()
    if (userId in refuse) return false
    restricted += Restricted(chatId, userId, permissions, until)
    return true
  }
  override fun banChannel(chatId: Long, senderChatId: Long) = banned.add(senderChatId)
  override fun unbanChannel(chatId: Long, senderChatId: Long) = unbanned.add(senderChatId)
  override fun permissions(chatId: Long): JsonObject = defaults
  override fun pinned(chatId: Long) = pinnedId?.let { it to pinnedText.orEmpty() }
}
