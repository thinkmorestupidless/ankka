package com.thinkmorestupidless.ankka.keyring

import com.thinkmorestupidless.ankka.core.personal.PersonalCipher

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64

/**
 * Keys inside keys (FR-017, R9): a subject key wrapped by its project's key-encryption key, that
 * wrapped by the installation's root key, each with what it is the key of as associated data — so a
 * wrapped key moved to another subject or project does not unwrap. A dump of the keyring's database
 * holds only wrapped keys.
 */
object Wrapping:
  def wrap(key: Array[Byte], owner: String, keyToWrap: Array[Byte]): String =
    Base64.getEncoder.encodeToString(PersonalCipher.encrypt(key, owner.getBytes(UTF_8), keyToWrap))

  def unwrap(key: Array[Byte], owner: String, wrapped: String): Array[Byte] =
    PersonalCipher
      .decrypt(key, owner.getBytes(UTF_8), Base64.getDecoder.decode(wrapped))
      .getOrElse(
        throw IllegalStateException(
          s"the key of $owner does not unwrap: the root key is not the one it was wrapped under"
        )
      )

  def newKey(): Array[Byte] = PersonalCipher.newKey()
