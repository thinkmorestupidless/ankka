package com.thinkmorestupidless.ankka.core.personal

import scala.collection.concurrent.TrieMap

/** A keyring in a map, for core's own suites; the test kit has the one services use. */
final class TestKeyring extends KeyringHandle:
  private val keys           = TrieMap.empty[(String, String), Array[Byte]]
  private val destroyed      = TrieMap.empty[(String, String), String]
  private val lookups        = TrieMap.empty[String, Array[Byte]]
  @volatile var created: Int = 0

  def key(project: String, subject: String, create: Boolean): KeyResult =
    destroyed.get((project, subject)) match
      case Some(id) => KeyResult.Destroyed(id)
      case None =>
        keys.get((project, subject)) match
          case Some(k) => KeyResult.Available(k)
          case None if create =>
            KeyResult.Available(
              keys.getOrElseUpdate((project, subject), { created += 1; PersonalCipher.newKey() })
            )
          case None => KeyResult.Destroyed("never-written")

  def lookupKey(project: String): Array[Byte] =
    lookups.getOrElseUpdate(project, PersonalCipher.newKey())

  def destroy(project: String, subject: String): Unit =
    keys.remove((project, subject)): Unit
    destroyed.put((project, subject), s"erasure-$subject"): Unit
