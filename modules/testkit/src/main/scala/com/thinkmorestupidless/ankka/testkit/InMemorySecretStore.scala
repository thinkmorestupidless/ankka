package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.sdk.{SecretRules, SecretStore}

import scala.collection.concurrent.TrieMap

/**
 * A secret store for unit tests: a map, with nothing encrypted and nothing started.
 *
 * It applies the runtime's own rules, so a name or a value the runtime would refuse is refused here
 * too, with the same message; a double kinder than the runtime would let a test pass that a running
 * service fails.
 */
final class InMemorySecretStore extends SecretStore:
  private val values = TrieMap.empty[String, String]

  def put(name: String, value: String): Unit =
    SecretRules.check(name, value)
    values.update(name, value)

  def get(name: String): Option[String] =
    SecretRules.check(name)
    values.get(name)

  def delete(name: String): Unit =
    SecretRules.check(name)
    values.remove(name): Unit

  /** Every secret held, for a test to assert on. */
  def snapshot: Map[String, String] = values.toMap

object InMemorySecretStore:
  def apply(): InMemorySecretStore = new InMemorySecretStore
