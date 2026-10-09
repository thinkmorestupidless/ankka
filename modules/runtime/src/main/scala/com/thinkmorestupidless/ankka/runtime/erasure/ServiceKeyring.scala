package com.thinkmorestupidless.ankka.runtime.erasure

import com.thinkmorestupidless.ankka.core.personal.{KeyResult, KeyringHandle}

/** A service's keyring handle: its connection to the keyring behind its cache. */
final class ServiceKeyring(val connection: KeyringConnection, val cache: KeyCache)
    extends KeyringHandle:

  def key(project: String, subject: String, create: Boolean): KeyResult =
    cache.get(project, subject, () => connection.fetch(project, subject, create)) match
      // A cached "never written" must not stop the first write from making the key.
      case KeyResult.Unknown if create =>
        cache.refresh(project, subject, () => connection.fetch(project, subject, create = true))
      case other => other

  @volatile private var lookupKeys = Map.empty[String, Array[Byte]]

  override def isDestroyed(project: String, subject: String): Boolean =
    cache.isDestroyed(project, subject)

  def lookupKey(project: String): Array[Byte] =
    lookupKeys.getOrElse(
      project, {
        val key = connection.lookupKey(project)
        lookupKeys = lookupKeys.updated(project, key)
        key
      }
    )
