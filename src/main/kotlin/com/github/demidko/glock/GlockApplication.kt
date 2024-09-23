package com.github.demidko.glock

import java.lang.Thread.sleep
import java.lang.Thread.startVirtualThread
import java.time.Duration
import java.time.Duration.ofSeconds

fun main(args: Array<String>) {
  val glockBot = ApplicationFactory().glockBot
  startLoopWithFixedRate(ofSeconds(2), glockBot::cleanTempMessages)
  glockBot.startPolling()
}

private fun startLoopWithFixedRate(every: Duration, action: () -> Unit) {
  startVirtualThread {
    while (!Thread.interrupted()) {
      sleep(every)
      action()
    }
  }
}