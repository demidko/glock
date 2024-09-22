package com.github.demidko.glock

import java.lang.Thread.sleep
import java.lang.Thread.startVirtualThread
import java.time.Duration
import java.time.Duration.ofSeconds

fun main() {
  val glockBot = ApplicationFactory().glockBot

  glockBot.startPollingAsync()

  startLoopWithFixedRate(ofSeconds(1), glockBot::processRestrictions)

  startLoopWithFixedRate(ofSeconds(2), glockBot::cleanTempMessages)
}

private fun startLoopWithFixedRate(every: Duration, action: () -> Unit) {
  startVirtualThread {
    while (!Thread.interrupted()) {
      sleep(every)
      action()
    }
  }
}