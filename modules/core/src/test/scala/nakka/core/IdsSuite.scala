package nakka.core

class IdsSuite extends munit.FunSuite:

  test("ComponentId accepts conventional names") {
    List("counter", "shopping-cart", "session_memory", "v2.orders", "A1").foreach { raw =>
      assertEquals(ComponentId.parse(raw).map(id => id: String), Right(raw), s"should accept '$raw'")
    }
  }

  test("ComponentId rejects empty, over-long and oddly-punctuated names") {
    assert(ComponentId.parse("").isLeft)
    assert(ComponentId.parse("-leading-dash").isLeft)
    assert(ComponentId.parse("has space").isLeft)
    assert(ComponentId.parse("has/slash").isLeft)
    assert(ComponentId.parse("a" * 129).isLeft)
    assertEquals(ComponentId.parse("a" * 128).map(_.length), Right(128))
  }

  test("ComponentId.apply throws so a bad declaration fails at startup") {
    intercept[IllegalArgumentException](ComponentId("bad id"))
  }

  test("EntityId rejects the persistence-id separator") {
    // Pekko builds persistence ids as `entityType|entityId`; allowing '|' through would
    // let one entity address another's journal.
    assert(EntityId.parse("cart|1").isLeft)
    assert(EntityId.parse("").isLeft)
    assertEquals(EntityId.parse("cart-1").map(id => id: String), Right("cart-1"))
  }

  test("SessionId reports itself, not EntityId, when invalid") {
    val message = SessionId.parse("a|b").left.getOrElse("")
    assert(message.contains("sessionId"), s"expected sessionId in: $message")
    assert(!message.contains("entityId"), s"leaked entityId in: $message")
  }

  test("ids are usable as Strings without conversion") {
    val id: ComponentId  = ComponentId("counter")
    val asString: String = id
    assertEquals(asString.toUpperCase, "COUNTER")
  }
