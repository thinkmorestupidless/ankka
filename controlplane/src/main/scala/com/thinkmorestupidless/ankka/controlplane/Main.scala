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
  val service =
    ControlPlane.builder(ControlPlane.aclFor(auth), config = config, auth = Some(auth)).start()

  sys.addShutdownHook(service.terminate())
  scala.concurrent.Await
    .result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit
