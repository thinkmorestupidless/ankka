package com.thinkmorestupidless.ankka.agent.blueprint

import com.github.plokhotnyuk.jsoniter_scala.core.{writeToString, JsonValueCodec}
import com.thinkmorestupidless.ankka.core.Codecs

/**
 * V1 for blueprints (research R3): a blueprint version is "the same" when its canonical JSON is, so
 * the canonical form must not depend on anything but the value. This pins what the shared codec
 * config does with field order and with maps, before `Blueprint.canonical` relies on it.
 */
class CanonicalSuite extends munit.FunSuite:

  // Fields deliberately out of alphabetical order.
  final case class Probe(zeta: Int, alpha: String, mid: Boolean)
  object Probe:
    given JsonValueCodec[Probe] = Codecs.make

  final case class Bag(parameters: Map[String, Int])
  object Bag:
    given JsonValueCodec[Bag] = Codecs.make

  final case class SortedBag(parameters: Vector[(String, Int)])
  object SortedBag:
    given JsonValueCodec[SortedBag] = Codecs.make

  test("V1 a case class is written in declaration order, not alphabetical") {
    assertEquals(writeToString(Probe(1, "a", true)), """{"zeta":1,"alpha":"a","mid":true}""")
  }

  test("V1 a map's order is its own, so the canonical form sorts pairs itself") {
    // Above four entries Scala's immutable Map is a hash map, and its iteration order is not the
    // insertion order; two maps with the same entries can still differ in it. The canonical form
    // therefore never writes a Map: it writes the pairs sorted by key.
    val keys     = Vector("f", "e", "d", "c", "b", "a", "g")
    val forward  = Bag(keys.zipWithIndex.toMap)
    val backward = Bag(keys.reverse.zipWithIndex.map((k, i) => k -> (keys.size - 1 - i)).toMap)
    assertEquals(forward.parameters, backward.parameters)
    // Whatever the codec does with the two maps, the sorted-pairs form is one text.
    val sorted = (bag: Bag) => SortedBag(bag.parameters.toVector.sortBy(_._1))
    assertEquals(writeToString(sorted(forward)), writeToString(sorted(backward)))
    assert(writeToString(sorted(forward)).startsWith("""{"parameters":[["a",5],["b",4]"""))
  }
