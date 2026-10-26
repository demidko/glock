package com.github.demidko.glock

import java.lang.Thread.*
import java.time.Duration
import java.time.Duration.ofSeconds

fun main(args: Array<String>) {
  val glockBot = ApplicationFactory().glockBot
  startLoopWithFixedRate(ofSeconds(2), glockBot::cleanTempMessages)
  startLoopWithFixedRate(ofSeconds(1), glockBot::reconcileRestrictions)
  glockBot.startPolling()
}

internal fun startLoopWithFixedRate(every: Duration, action: () -> Unit): Thread {
  return startVirtualThread {
    while (interrupted().not()) {
      try {
        sleep(every)
        action()
      } catch (e: InterruptedException) {
        currentThread().interrupt()
      } catch (e: Exception) {
        ActionDiagnostics.error("maintenance", "Background cleanup failed; retrying next cycle", e)
      }
    }
  }
}