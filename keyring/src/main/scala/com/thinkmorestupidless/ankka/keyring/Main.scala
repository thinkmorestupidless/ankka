package com.thinkmorestupidless.ankka.keyring

import com.typesafe.config.ConfigFactory

/**
 * Runs the keyring: `ANKKA_HTTP_PORT` (9020 in its manifest and compose), its own database
 * (`ANKKA_DB_*`), its own secret key (`ANKKA_SECRET_KEY`, which keeps its root key), and, on an
 * installation, the erasure log's two copies (`ANKKA_ERASURE_LOG_URL`, `ANKKA_S3_*`).
 */
@main def runKeyring(): Unit =
  val config = ConfigFactory.load()
  // Machines outside the installation are let in by their issuers' tokens, when the installation
  // lists any (`ANKKA_AUTH_ISSUERS`); and are let do nothing until spec 040 renders their grants.
  val machineAcl =
    if sys.env.get("ANKKA_AUTH_ISSUERS").exists(_.nonEmpty) then
      com.thinkmorestupidless.ankka.auth.oidc.Oidc.authenticate()
    else com.thinkmorestupidless.ankka.http.Acl.DenyAll
  val state   = KeyringState(Grants.none, Keyring.logSources(config), machineAcl = machineAcl)
  val service = Keyring.builder(state).start()
  sys.addShutdownHook(service.terminate())
  scala.concurrent.Await
    .result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit
