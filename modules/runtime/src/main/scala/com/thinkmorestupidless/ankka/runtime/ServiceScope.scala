package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.personal.{KeyringHandle, PersonalScope}
import org.apache.pekko.actor.typed.{ActorSystem, Extension, ExtensionId}

import scala.concurrent.ExecutionContext

/**
 * The keyring and project of the service an actor system hosts (feature 042), for every place the
 * runtime serializes a domain value on that system's behalf: an entity's adapters and handlers, a
 * projection's handlers and their callbacks, an HTTP handler, a view read. With it set, those
 * places are right whatever else runs in the JVM — two projects in one test JVM, where no default
 * exists.
 *
 * `executionContext` carries the scope to every callback scheduled on it, which is where a
 * projection does most of its work: on whichever thread the database's future completes on.
 */
final class ServiceScope(system: ActorSystem[?]) extends Extension:

  @volatile private var scope: Option[(KeyringHandle, String)] = None

  private[ankka] def set(keyring: KeyringHandle, project: String): Unit = scope = Some(
    (keyring, project)
  )

  def within[T](body: => T): T = scope match
    case Some((keyring, project)) => PersonalScope.within(keyring, project)(body)
    case None                     => body

  /** `underlying`, with every task it runs inside this service's scope. */
  def on(underlying: ExecutionContext): ExecutionContext = new ExecutionContext:
    def execute(runnable: Runnable): Unit     = underlying.execute(() => within(runnable.run()))
    def reportFailure(cause: Throwable): Unit = underlying.reportFailure(cause)

  lazy val executionContext: ExecutionContext = new ExecutionContext:
    private val underlying                    = system.executionContext
    def execute(runnable: Runnable): Unit     = underlying.execute(() => within(runnable.run()))
    def reportFailure(cause: Throwable): Unit = underlying.reportFailure(cause)

object ServiceScope extends ExtensionId[ServiceScope]:
  def createExtension(system: ActorSystem[?]): ServiceScope = new ServiceScope(system)
