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

  // ── T008: a blueprint's canonical form and digest ───────────────────────────

  private val digest =
    Blueprint("digest")
      .input(Shape.obj("from" -> Shape.string, "to" -> Shape.string))
      .worker(Worker("reader").instructions("Say what this paper finds.").budget(3))
      .worker(
        Worker("writer").instructions("Write a script.").tools("papers_found_between").budget(6)
      )
      .step(Step("papers").ask("writer").reads("input").result(Shape.arr(Shape.string)))
      .step(
        Step("findings")
          .forEach("reader", over = "papers", limit = 8)
          .result(Shape.arr(Shape.string))
      )
      .step(
        Step("script")
          .critique("writer", Verdict.judgment("names-a-paper"), rounds = 3)
          .reads("findings")
      )
      .schedule(
        Schedule
          .weekly(
            java.time.DayOfWeek.SUNDAY,
            java.time.LocalTime.of(20, 0),
            java.time.ZoneId.of("Europe/London")
          )
          .perMissedPeriod
      )

  test("the same blueprint written in another field order has the same canonical form and digest") {
    val reordered =
      """{"steps":[
        |  {"result":{"items":{"type":"string"},"type":"array"},"reads":["input"],"over":{"type":"Once"},"until":null,"does":{"worker":"writer","type":"Ask"},"name":"papers"},
        |  {"result":{"type":"array","items":{"type":"string"}},"name":"findings","until":null,"does":{"type":"Ask","worker":"reader"},"over":{"type":"Each","limit":8,"keepGoing":false,"read":"papers"},"reads":[]},
        |  {"name":"script","reads":["findings"],"result":{"type":"string"},"over":{"type":"Once"},"until":{"keepLast":false,"rounds":3,"verdict":{"type":"Judgment","question":"names-a-paper"}},"does":{"type":"Ask","worker":"writer"}}
        |],
        |"schedule":{"catchUp":"each","zone":"Europe/London","cadence":{"time":"20:00","type":"Weekly","day":"SUNDAY"}},
        |"workers":[
        |  {"budget":3,"guardrails":[],"tools":[],"model":"default","instructions":"Say what this paper finds.","name":"reader"},
        |  {"name":"writer","budget":6,"model":"default","tools":["papers_found_between"],"guardrails":[],"instructions":"Write a script."}
        |],
        |"input":{"required":["from","to"],"properties":{"to":{"type":"string"},"from":{"type":"string"}},"type":"object"},
        |"name":"digest"}""".stripMargin
    val read = Blueprint.fromJson(reordered).fold(fail(_), identity)
    assertEquals(read, digest)
    assertEquals(read.canonical, digest.canonical)
    assertEquals(read.digest, digest.digest)
    assert(
      digest.canonical.startsWith("""{"input":{"properties":{"from":"""),
      digest.canonical.take(60)
    )
  }

  test("changing one worker's instructions changes the digest") {
    val changed =
      digest.copy(workers =
        digest.workers.map(w => if w.name == "reader" then w.instructions("Say more.") else w)
      )
    assertNotEquals(changed.digest, digest.digest)
    assertEquals(digest.digest.length, 64)
  }

  test("the canonical text round-trips") {
    assertEquals(Blueprint.fromJson(digest.canonical), Right(digest))
    assertEquals(Blueprint.fromJson(digest.canonical).map(_.canonical), Right(digest.canonical))
  }

  test("a step is what it does, how many times and over what, and until what") {
    // The named shapes are the common combinations; any action may repeat, and any turn may draft.
    val each = Step("findings").forEach("reader", over = "papers")
    assertEquals(
      (each.does, each.over, each.until),
      (Action.Ask(Some("reader")), Over.Each("papers"), None)
    )
    val gather = Step("views").gather(Seq("a", "b"), chosenBy = "select.names")
    assertEquals(gather.does, Action.Ask(None))
    assertEquals(gather.over, Over.Workers(Vector("a", "b"), Some("select.names")))
    val workEach = Step("deep").work("digger").each("papers", limit = 2)
    assertEquals(
      (workEach.does, workEach.over),
      (Action.Work(Some("digger")), Over.Each("papers", 2))
    )
    val judgeEach = Step("relevant").judge("relevant").each("papers")
    assertEquals(judgeEach.namedQuestions, Vector("relevant"))
    assertEquals(Step("keep").call("keep_paper").namedHandler, Some("keep_paper"))
  }
