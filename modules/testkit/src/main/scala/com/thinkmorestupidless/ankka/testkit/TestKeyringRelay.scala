package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.{
  CommandError,
  DataSubject,
  ErrorCode,
  KeyAnswer,
  Personal
}
import com.thinkmorestupidless.ankka.runtime.keyring.ServiceKeyring
import com.thinkmorestupidless.ankka.runtime.replication.{
  KeyringRelay,
  KeyringRelayProvider,
  LocalInstallations,
  ReplicationSettings
}
import com.typesafe.config.Config
import org.apache.pekko.actor.typed.ActorSystem

import java.util.concurrent.ConcurrentHashMap

/**
 * The keyring relay of installations started in one JVM (feature 042, R17): a follower's fetch is
 * answered by the home's own keyring, reached by its name through `LocalInstallations`, as the
 * runtime's own transport reaches a sibling's sharding. The home admits only an installation its
 * own `ankka.replication.installations` names, as a home admits a forward; any other is refused. A
 * home that is not started in this JVM is `Unavailable`, naming it.
 *
 * Found through `ServiceLoader`, so every group of `AnkkaTestKit.installations` uses it. `block`
 * makes one home's keyring not answer one installation, which a test of the follower's retry asks
 * for; `unblockAll` ends every block.
 */
final class TestKeyringRelay(self: String) extends KeyringRelay:

  def key(home: String, project: String, subject: DataSubject): KeyAnswer =
    if TestKeyringRelay.blocked(self, home) then
      throw unavailable(home, subject, s"the keyring of '$home' does not answer '$self' (blocked)")
    LocalInstallations.lookup(home) match
      case None =>
        throw unavailable(home, subject, s"installation '$home' is not started in this JVM")
      case Some(local) =>
        val admitted = local.system.settings.config
          .hasPath(s"""ankka.replication.installations."$self"""")
        if !admitted then
          KeyAnswer.Refused(s"installation '$home' does not admit '$self' to relay its keys")
        else ServiceKeyring.of(local.system).key(project, subject, create = false)

  private def unavailable(home: String, subject: DataSubject, why: String): CommandError =
    CommandError(
      s"the keyring is unavailable: the key of '${subject.value}' is kept by '$home': $why",
      ErrorCode.Unavailable,
      Map(Personal.ErrorKey -> Personal.Unavailable)
    )

object TestKeyringRelay:

  private val blocks = ConcurrentHashMap.newKeySet[(String, String)]()

  /** `home`'s keyring does not answer `from` until unblocked. */
  def block(from: String, home: String): Unit = blocks.add(from -> home): Unit

  def unblock(from: String, home: String): Unit = blocks.remove(from -> home): Unit

  def unblockAll(): Unit = blocks.clear()

  private def blocked(from: String, home: String): Boolean = blocks.contains(from -> home)

/** Supplies `TestKeyringRelay` to every replicating service started with the test kit. */
final class TestKeyringRelayProvider extends KeyringRelayProvider:
  def relay(
      system: ActorSystem[?],
      config: Config,
      settings: ReplicationSettings
  ): Option[KeyringRelay] =
    settings.installation.map(TestKeyringRelay(_))
