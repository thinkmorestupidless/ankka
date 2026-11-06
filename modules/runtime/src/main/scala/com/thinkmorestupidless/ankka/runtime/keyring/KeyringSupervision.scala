package com.thinkmorestupidless.ankka.runtime.keyring

import com.thinkmorestupidless.ankka.core.KeyringUnavailable
import org.apache.pekko.actor.typed.{Behavior, SupervisorStrategy}
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import scala.concurrent.duration.*

/**
 * Restarts a host whose personal fields could not be read because the keyring was out (feature 042,
 * R4), with backoff, rather than stopping it: the command that started it stays in its mailbox, and
 * the recovery is tried again until the keyring answers. Nothing else is restarted: any other
 * failure stops the host as it always has.
 */
object KeyringSupervision:

  val MinBackoff: FiniteDuration = 1.second
  val MaxBackoff: FiniteDuration = 30.seconds

  def apply[T](behavior: Behavior[T]): Behavior[T] =
    Behaviors
      .supervise(behavior)
      .onFailure[KeyringUnavailable](
        SupervisorStrategy.restartWithBackoff(MinBackoff, MaxBackoff, 0.2)
      )
