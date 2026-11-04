package com.thinkmorestupidless.ankka.runtime.keyring

import com.thinkmorestupidless.ankka.core.{DataSubject, KeyAnswer, Keyring}

/**
 * The keyring a service's codecs read (feature 042): its project, over the client the service was
 * started with. Installed as `Keyring.current` when the service starts and bound by each host
 * around its serializer calls, so two services in one JVM never read each other's.
 */
final class KeyringHandle(val project: String, val client: KeyStoreClient) extends Keyring:

  def key(project: String, subject: DataSubject, create: Boolean): KeyAnswer =
    client.key(project, subject, create)

  override def toString: String = s"KeyringHandle($project)"
