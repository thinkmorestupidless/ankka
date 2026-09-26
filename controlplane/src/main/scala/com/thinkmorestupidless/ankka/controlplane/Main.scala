package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.auth.AuthConfig
import com.typesafe.config.ConfigFactory

/**
 * Runs the control plane.
 *
 * Needs Postgres for the journal and the view tables and Keycloak for identity: `docker compose up
 * -d`. Needs `ANKKA_AUTH_ISSUER` set (the compose Keycloak's realm is
 * http://localhost:8081/realms/ankka), and a reachable Kubernetes cluster to deploy anything.
 */
@main def runControlPlane(): Unit =
  val config = ConfigFactory.load()
  val auth   = AuthConfig.from(config)
  // Both halves together: the acl answers deploy tokens from the index, and the index only fills
  // because it is registered as an extension here. Taking one without the other is the bug this
  // pair exists to make impossible.
  val (acl, tokens) = ControlPlane.aclWithTokens(auth)
  val service =
    ControlPlane
      .builder(acl, config = config, auth = Some(auth), tokens = Some(tokens))
      .start()

  sys.addShutdownHook(service.terminate())
  scala.concurrent.Await
    .result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit
