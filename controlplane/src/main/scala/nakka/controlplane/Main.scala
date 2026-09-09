package nakka.controlplane

import com.typesafe.config.ConfigFactory

/**
 * Runs the control plane.
 *
 * Needs Postgres for the journal and the view tables: `docker compose up -d`. Needs
 * `NAKKA_CONTROLPLANE_TOKEN` set, and — once the reconciler lands — a reachable Kubernetes cluster.
 */
@main def runControlPlane(): Unit =
  val service = ControlPlane.builder(ControlPlane.aclFrom(ConfigFactory.load())).start()

  sys.addShutdownHook(service.terminate())
  scala.concurrent.Await
    .result(service.whenTerminated, scala.concurrent.duration.Duration.Inf): Unit
