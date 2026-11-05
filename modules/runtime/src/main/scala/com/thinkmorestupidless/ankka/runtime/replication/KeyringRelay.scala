package com.thinkmorestupidless.ankka.runtime.replication

import com.thinkmorestupidless.ankka.core.{
  CommandError,
  DataSubject,
  ErrorCode,
  KeyAnswer,
  Keyring,
  Personal
}
import com.thinkmorestupidless.ankka.runtime.keyring.KeyCache
import com.typesafe.config.Config
import org.apache.pekko.actor.typed.ActorSystem

import java.time.Clock
import scala.concurrent.duration.FiniteDuration

/**
 * How a follower of a replicated entity reaches the subject keys its home's keyring keeps (feature
 * 042, R17): only the home's keyring holds a replicated entity's keys, so a follower is given them
 * as a service of the home is, and its own keyring never makes one.
 *
 * A relay is found through `java.util.ServiceLoader`, as a `ReplicationTransportProvider` is: a jar
 * names its provider in
 * `META-INF/services/com.thinkmorestupidless.ankka.runtime.replication.KeyringRelayProvider`. The
 * replication layer supplies one over its peer link; with none, every fetch is `Unavailable` naming
 * the relay, so a follower's recovery fails and is retried and never reads a field as erased.
 */
trait KeyringRelay:

  /**
   * The key of `subject` in `project`, as `home`'s keyring answers it, for this installation: read
   * only, since a follower never makes a key. `Refused` when the home does not admit this
   * installation. Throws `CommandError` with `ErrorCode.Unavailable` when the home's keyring cannot
   * be reached. Blocks its caller, as a keyring fetch does.
   */
  def key(home: String, project: String, subject: DataSubject): KeyAnswer

/** Supplies a relay; see `KeyringRelay`. */
trait KeyringRelayProvider:

  /** `None` when this provider is not configured; the runtime then answers `Unavailable`. */
  def relay(
      system: ActorSystem[?],
      config: Config,
      settings: ReplicationSettings
  ): Option[KeyringRelay]

object KeyringRelay:

  /** No relay: every fetch is `Unavailable`, naming what would supply one. */
  val unavailable: KeyringRelay = (home, _, subject) =>
    throw CommandError(
      s"the keyring is unavailable: the key of '${subject.value}' is kept by the keyring of " +
        s"installation '$home', the home of this replicated entity, and no keyring relay reaches " +
        "it (a KeyringRelayProvider on the classpath, which ankka-replication supplies)",
      ErrorCode.Unavailable,
      Map(Personal.ErrorKey -> Personal.Unavailable)
    )

  /** The first relay a provider on the classpath supplies, else `unavailable`. */
  private[ankka] def resolve(
      system: ActorSystem[?],
      config: Config,
      settings: ReplicationSettings
  ): KeyringRelay =
    import scala.jdk.CollectionConverters.*
    java.util.ServiceLoader
      .load(classOf[KeyringRelayProvider])
      .iterator()
      .asScala
      .flatMap(_.relay(system, config, settings))
      .nextOption()
      .getOrElse(unavailable)

  /**
   * `relay` with each key held for `ttl`, as a key of another project is (decided 2026-11-04,
   * T076): no notice of the home's keyring reaches a follower, so an erasure at the home reaches a
   * follower's read within `ttl`. A refusal is never held.
   */
  private[ankka] def held(
      relay: KeyringRelay,
      ttl: FiniteDuration,
      maxKeys: Int,
      clock: Clock
  ): KeyringRelay =
    // Keyed by the home and the project together, so two homes' keys of one subject never mix.
    val cache = new KeyCache(maxKeys, ttl, clock)
    (home, project, subject) =>
      val at = s"$home/$project"
      cache.fresh(at, subject).getOrElse {
        relay.key(home, project, subject) match
          case refused: KeyAnswer.Refused => refused
          case answer                     =>
            cache.put(at, subject, answer)
            answer
      }

/**
 * The keyring a replicated entity's host binds (feature 042, R17): the service's own while this
 * installation is the entity's home, or while no record has said where it lives; the home's,
 * through the relay, while another installation is. `home` is the home as the entity's journal has
 * said it so far, which the host sets as it folds each record, so a record written before a
 * handover is read with the keys of the home it was written under.
 */
private[ankka] final class RoutedKeyring(own: Keyring, self: String, relay: KeyringRelay)
    extends Keyring:

  @volatile var home: Option[String] = None

  def project: String = own.project

  def key(project: String, subject: DataSubject, create: Boolean): KeyAnswer =
    home match
      case Some(h) if h != self =>
        relay.key(h, project, subject) match
          // A follower writes only what it was given (a snapshot of the home's state): a key the
          // home never made cannot be made here.
          case KeyAnswer.Absent if create =>
            throw CommandError(
              s"the keyring of installation '$h', this entity's home, holds no key for " +
                s"'${subject.value}', and a follower never makes one; nothing was written",
              ErrorCode.Unavailable,
              Map(Personal.ErrorKey -> Personal.Unavailable)
            )
          case answer => answer
      case _ => own.key(project, subject, create)

  override def lookupKey(project: String): IArray[Byte] = own.lookupKey(project)

  override def toString: String = s"RoutedKeyring($self, home ${home.getOrElse("unknown")})"
