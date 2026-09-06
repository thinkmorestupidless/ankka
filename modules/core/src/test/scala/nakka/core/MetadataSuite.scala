package nakka.core

class MetadataSuite extends munit.FunSuite:

  test("lookup is case-insensitive but original casing survives") {
    val m = Metadata.of("X-Request-Id" -> "abc")
    assertEquals(m.get("x-request-id"), Some("abc"))
    assertEquals(m.get("X-REQUEST-ID"), Some("abc"))
    assertEquals(m.toSeq.map(_._1), Seq("X-Request-Id"))
  }

  test("add keeps every value, set replaces them") {
    val added = Metadata.empty.add("k", "1").add("K", "2")
    assertEquals(added.getAll("k"), Seq("1", "2"))
    assertEquals(added.get("k"), Some("1"), "get returns the first value")

    val replaced = added.set("k", "3")
    assertEquals(replaced.getAll("k"), Seq("3"))
  }

  test("remove is case-insensitive across all values") {
    val m = Metadata.of("a" -> "1", "A" -> "2", "b" -> "3")
    assertEquals(m.remove("a").toSeq, Seq("b" -> "3"))
  }

  test("insertion order is preserved") {
    val m = Metadata.empty.add("c", "3").add("a", "1").add("b", "2")
    assertEquals(m.toSeq.map(_._1), Seq("c", "a", "b"))
  }

  test("CloudEvents accessors read the ce- attributes") {
    val m = Metadata.empty
      .withSubject("cart-1")
      .set(Metadata.CeType, "ItemAdded")
      .set(Metadata.CeSpecVersion, "1.0")
    assertEquals(m.subject, Some("cart-1"))
    assertEquals(m.eventType, Some("ItemAdded"))
    assertEquals(m.specVersion, Some("1.0"))
    assertEquals(m.source, None)
  }

  test("equality ignores key casing") {
    assertEquals(Metadata.of("Ce-Subject" -> "x"), Metadata.of("ce-subject" -> "x"))
    assertEquals(
      Metadata.of("Ce-Subject" -> "x").hashCode,
      Metadata.of("ce-subject" -> "x").hashCode
    )
    assertNotEquals(Metadata.of("k" -> "a"), Metadata.of("k" -> "A"))
  }

  test("++ appends right-hand entries") {
    val merged = Metadata.of("a" -> "1") ++ Metadata.of("b" -> "2")
    assertEquals(merged.toSeq, Seq("a" -> "1", "b" -> "2"))
  }
