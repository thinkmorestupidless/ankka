package com.thinkmorestupidless.ankka.runtime

import org.apache.pekko.actor.typed.{ActorSystem, Extension, ExtensionId}
import org.apache.pekko.actor.ActorSystem as ClassicActorSystem

import scala.concurrent.Future

/**
 * What the runtime's extensions say about being ready, gathered for the node's readiness check.
 *
 * Per actor system, so a test JVM hosting several services in turn keeps them apart.
 */
final class ExtensionsReadiness extends Extension:
  @volatile private var checks: Vector[() => Boolean] = Vector.empty

  private[runtime] def register(more: Vector[() => Boolean]): Unit =
    checks = checks ++ more

  def allReady: Boolean = checks.forall(_())

object ExtensionsReadiness extends ExtensionId[ExtensionsReadiness]:
  def createExtension(system: ActorSystem[?]): ExtensionsReadiness = new ExtensionsReadiness

/**
 * The readiness check Pekko Management runs beside its own cluster-membership one.
 *
 * Registered by the Kubernetes overlay under `pekko.management.health-checks.readiness-checks`,
 * which is why it takes a classic `ActorSystem` and is a `() => Future[Boolean]`: that is the shape
 * management instantiates by reflection. So `/ready` is 200 only once the node is a member AND
 * every extension that has an opinion — the HTTP server, once it has bound — agrees.
 */
final class ExtensionsReadinessCheck(system: ClassicActorSystem) extends (() => Future[Boolean]):
  private val typed =
    org.apache.pekko.actor.typed.scaladsl.adapter.ClassicActorSystemOps(system).toTyped
  def apply(): Future[Boolean] = Future.successful(ExtensionsReadiness(typed).allReady)
