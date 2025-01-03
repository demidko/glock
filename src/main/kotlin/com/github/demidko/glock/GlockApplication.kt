package com.github.demidko.glock

import java.lang.Thread.*
import java.time.Duration
import java.time.Duration.ofSeconds

fun main(args: Array<String>) {
  val glockBot = ApplicationFactory().glockBot
  startLoopWithFixedRate(ofSeconds(2), glockBot::cleanTempMessages)
  glockBot.startPolling()
}

private fun startLoopWithFixedRate(every: Duration, action: () -> Unit) {
  startVirtualThread {
    while (interrupted().not()) {
      sleep(every)
      action()
    }
  }
}