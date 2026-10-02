package com.thinkmorestupidless.ankka.core.effect

import com.thinkmorestupidless.ankka.core.Metadata

/** A consumer's effects are values: building one publishes nothing. */
class ConsumerEffectSuite extends munit.FunSuite:

  private val effects = new ConsumerEffects[String]

  test("a message of several has no key and no metadata until it is given them") {
    val message = effects.message("a")
    assertEquals(message, Outgoing("a", Metadata.empty, None))
    assertEquals(message.withKey("line:1").key, Some("line:1"))
    assertEquals(message.withKey("line:1").payload, "a")
    val headers = Metadata.empty.set("x-n", "1")
    assertEquals(message.withMetadata(headers).metadata, headers)
    // Naming a key leaves the subject alone.
    val subject = Metadata.empty.withSubject("cart-1")
    assertEquals(message.withMetadata(subject).withKey("line:1").metadata.subject, Some("cart-1"))
  }

  test("an empty key is refused where it is named") {
    val failure = intercept[IllegalArgumentException](effects.message("a").withKey(""))
    assert(failure.getMessage.contains("must not be empty"), failure.getMessage)
  }

  test("produceAll keeps the messages in the order given") {
    val messages =
      Seq(effects.message("a"), effects.message("b").withKey("k"), effects.message("c"))
    assertEquals(effects.produceAll(messages), ConsumerEffect.ProduceAll(messages))
  }

  test("a single produce is the effect it always was") {
    assertEquals(effects.produce("a"), ConsumerEffect.Produce("a", Metadata.empty))
    val headers = Metadata.empty.withSubject("s")
    assertEquals(effects.produce("a", headers), ConsumerEffect.Produce("a", headers))
  }

  test("what an effect asks to have published: one, several, or nothing") {
    val headers = Metadata.empty.set("x-n", "1")
    assertEquals(
      ConsumerEffect.outgoing(effects.produce("a", headers)),
      Seq(Outgoing("a", headers, None))
    )
    val several = Seq(effects.message("a"), effects.message("b").withKey("k"))
    assertEquals(ConsumerEffect.outgoing(effects.produceAll(several)), several)
    assertEquals(ConsumerEffect.outgoing(effects.produceAll(Nil)), Nil)
    assertEquals(ConsumerEffect.outgoing(effects.done()), Nil)
    assertEquals(ConsumerEffect.outgoing(effects.ignore()), Nil)
  }
