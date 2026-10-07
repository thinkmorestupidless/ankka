package com.thinkmorestupidless.ankka.core.effect

/** A keyed view's effect, and the one function every host and test kit reads it through. */
class RowChangesSuite extends munit.FunSuite:

  private val effects = KeyedViewEffects[String]()

  test("a later change to a key wins: delete then write writes, write then delete deletes") {
    val moved = effects.deleteRow("a") ++ effects.updateRow("a", "again")
    assertEquals(RowChanges.reduce(moved.changes), Vector("a" -> Some("again")))
    val gone = effects.updateRow("a", "first") ++ effects.deleteRow("a")
    assertEquals(RowChanges.reduce(gone.changes), Vector("a" -> None))
  }

  test("keys come in the order they first appear") {
    val effect =
      effects.updateRow("b", "1") ++ effects.updateRow("a", "2") ++ effects.updateRow("b", "3")
    assertEquals(RowChanges.reduce(effect.changes), Vector("b" -> Some("3"), "a" -> Some("2")))
  }

  test("a row is moved by deleting its old key and writing its new one") {
    val effect = effects.deleteRow("s1") ++ effects.updateRow("s9", "row")
    assertEquals(RowChanges.reduce(effect.changes), Vector("s1" -> None, "s9" -> Some("row")))
  }

  test("nothing is nothing") {
    assertEquals(RowChanges.reduce(effects.ignore().changes), Vector.empty)
    assert(effects.ignore().isEmpty)
    assert(effects.updateRows(Nil).isEmpty)
  }

  test("the builders build what they say") {
    assertEquals(
      effects.updateRows(Seq("a" -> "1", "b" -> "2")).changes,
      Vector(RowChange.Upsert("a", "1"), RowChange.Upsert("b", "2"))
    )
    assertEquals(
      effects.deleteRows(Seq("a", "b")).changes,
      Vector(RowChange.Delete("a"), RowChange.Delete("b"))
    )
  }
