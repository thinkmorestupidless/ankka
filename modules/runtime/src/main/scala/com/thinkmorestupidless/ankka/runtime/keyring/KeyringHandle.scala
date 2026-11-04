package com.thinkmorestupidless.ankka.runtime.keyring

import com.thinkmorestupidless.ankka.core.{CommandError, DataSubject, ErrorCode, KeyAnswer, Keyring}

/**
 * The keyring a service's codecs read (feature 042): its project, over the client the service was
 * started with and the instance's cache. Installed as `Keyring.current` when the service starts and
 * bound by each host around its serializer calls, so two services in one JVM never read each
 * other's.
 *
 * A fresh key is answered from the cache. Otherwise the keyring is asked; when it cannot be
 * reached, a key fetched within the outage bound is still answered, and past it the call fails as
 * the keyring's did.
 */
final class KeyringHandle(val project: String, val client: KeyStoreClient, val cache: KeyCache)
    extends Keyring:

  def key(project: String, subject: DataSubject, create: Boolean): KeyAnswer =
    cache.fresh(project, subject).getOrElse {
      try
        val answer = client.key(project, subject, create)
        cache.put(project, subject, answer)
        answer
      catch
        case unavailable: CommandError if unavailable.code == ErrorCode.Unavailable =>
          cache.stale(project, subject).getOrElse(throw unavailable)
    }

  override def toString: String = s"KeyringHandle($project)"
