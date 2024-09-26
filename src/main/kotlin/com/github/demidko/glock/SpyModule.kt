package com.github.demidko.glock

import com.github.kotlintelegrambot.entities.Chat
import com.github.kotlintelegrambot.entities.Message

class SpyModule(private val excludedChatsUsernames: Set<String>) {

  fun spy(m: Message) {
    if (isExcludedChat(m)) {
      return
    }
    println(m)
    /*val user = readUser(m)
    val chat = readChat(m)
    val gist = readGist(m)
    println("$user — $chat: $gist")*/
  }

  private fun isExcludedChat(m: Message): Boolean {
    return m.chat.username in excludedChatsUsernames
  }

  private fun readChat(m: Message): String {
    return readChat(m.chat)
  }

  private fun readChat(chat: Chat): String {
    if (chat.username != null) {
      return chat.username!!
    }
    if (chat.inviteLink != null) {
      return chat.inviteLink!!
    }
    if (chat.location != null) {
      val location = chat.location!!
      return "${location.location} ${location.address}"
    }
    return buildString {
      append("Group id ")
      append(chat.id)
      if (chat.firstName != null) {
        append(' ')
        append(chat.firstName)
      }
      if (chat.lastName != null) {
        append(' ')
        append(chat.lastName)
      }
    }
  }

  private fun readUser(m: Message): String {
    if (m.from == null) {

    }
    m.senderChat
    TODO()
  }
}