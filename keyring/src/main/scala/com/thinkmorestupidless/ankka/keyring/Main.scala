package com.thinkmorestupidless.ankka.keyring

import com.typesafe.config.ConfigFactory

/**
 * Runs the keyring: `ANKKA_HTTP_PORT` (9020 in its manifest and compose), its own database
 * (`ANKKA_DB_*`), its own secret key (`ANKKA_SECRET_KEY`, which keeps its root key), and, on an
 * installation, the erasure log's two copies (`ANKKA_ERASURE_LOG_URL`, `ANKKA_S3_*`).
 */
@main def runKeyring(): Unit =
  val config  = ConfigFactory.load()
  val state   = KeyringState(Grants.none, Keyring.logSources(config))
  val service = Keyring.builder(state).start()
  sys.addShutdownHook(service.terminate())
  scala.concurrent.Await
    .result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit
