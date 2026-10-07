package digest

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.agent.blueprint.*
import com.thinkmorestupidless.ankka.agent.judgment.{Answers, TestJudgmentProvider}
import com.thinkmorestupidless.ankka.core.{CommandError, EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.runtime.{ProjectionRuntime, TimerRuntime}
import com.thinkmorestupidless.ankka.testkit.blueprint.Scripted
import com.thinkmorestupidless.ankka.testkit.{
  AnkkaTestKit,
  GherkinSuite,
  LogCapturing,
  MovableClock
}
import digest.application.*
import digest.domain.{Entry, Paper}

import java.time.{DayOfWeek, Instant, LocalDate, LocalTime, ZoneId}
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.*

/**
 * The research digest's living features, run against the whole service with nothing outside it: the
 * sources are scripted functions behind the same tool names the blueprints use, the model answers
 * from a script keyed by the step and the worker, and the judgment provider is scripted. No model
 * key, no network.
 *
 * Each scenario's papers are named after it, and each digest scenario covers a week of its own,
 * since the digest reads by time and the entries of every scenario share one journal.
 */
class ResearchDigestFeatures extends GherkinSuite("features") with LogCapturing:

  override val munitTimeout = 8.minutes

  // ── The fixture ───────────────────────────────────────────────────────────

  val model: TestModelProvider    = TestModelProvider()
  val judge: TestJudgmentProvider = TestJudgmentProvider()

  /** What each scripted source answers a search with, or why it cannot be reached. */
  private val answers = TrieMap[String, Either[String, Vector[Paper]]]()

  private def scripted(sourceName: String): Source = new Source:
    def name = sourceName
    def search(query: String, from: LocalDate, to: LocalDate): Vector[Paper] =
      answers.getOrElse(sourceName, Right(Vector.empty)) match
        case Left(problem) => throw IllegalStateException(problem)
        case Right(papers) => papers

  val sources: Vector[Source] = Vector("europepmc", "biorxiv", "openalex").map(scripted)

  /**
   * When the keeper keeps: a Wednesday in the week ending 18 October 2026. The clock never moves.
   */
  private val london = ZoneId.of("Europe/London")
  private val clock  = MovableClock.at(Instant.parse("2026-10-14T12:00:00Z"))

  private val agents = AgentRuntime
    .withDefaultModel(model)
    .withJudgments(judge)
    .withBlueprints(context => Blueprints.registry(context, sources, clock))

  private var kit: AnkkaTestKit = null

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(
      Seq(PapersEntity.descriptor, EntriesView.descriptor) ++ AgentRuntime.descriptors,
      Seq(agents, TimerRuntime(pollInterval = 1.second, clock = clock), ProjectionRuntime())
    )

  override def afterAll(): Unit = if kit != null then kit.stop()

  override def beforeEach(context: BeforeEach): Unit =
    model.reset()
    judge.reset()
    answers.clear()
    runsById.clear()
    judge.always(Answers.yesNo(Blueprints.namesPaper, 0.95)): Unit
    script()

  // ── Papers, periods and runs ──────────────────────────────────────────────

  private def paper(n: Int): Paper                = Paper(f"$scenarioId-p$n%02d", s"Paper $n")
  private def papers(range: Range): Vector[Paper] = range.toVector.map(paper)
  private val runsById                            = TrieMap[String, RunSnapshot]()

  /** The week ending at 20:00 on a Sunday, as a digest's period. */
  private def weekEnding(date: String): Period =
    val day = LocalDate.parse(date)
    assertEquals(day.getDayOfWeek, DayOfWeek.SUNDAY, s"$date is not a Sunday")
    val to = day.atTime(LocalTime.of(20, 0)).atZone(london).toInstant
    Period(day.minusDays(7).atTime(LocalTime.of(20, 0)).atZone(london).toInstant, to, Vector(to))

  private def noon(date: String): Long =
    LocalDate.parse(date).atTime(LocalTime.NOON).atZone(london).toInstant.toEpochMilli

  private def keep(identifier: String, title: String, source: String, foundAt: Long): Unit =
    kit.componentClient
      .forKeyValueEntity(EntityId(identifier))
      .call(PapersEntity.keep)
      .invoke(PapersEntity.Keep(source, title, foundAt)): Unit

  private def entry(identifier: String): Option[Entry] =
    try
      Some(
        kit.componentClient.forKeyValueEntity(EntityId(identifier)).call(PapersEntity.get).invoke()
      )
    catch case e: CommandError if e.code == ErrorCode.NotFound => None

  /** Waits for the view to hold what was kept in a period: the digest reads from it. */
  private def viewHolds(period: Period, identifiers: Set[String]): Unit =
    kit.eventually(s"the entries view holds ${identifiers.size} entries for the period")(
      Option.when(
        identifiers.subsetOf(
          EntriesView
            .foundBetween(kit.service.viewClient, period.from, period.to)
            .map(_.identifier)
            .toSet
        )
      )(())
    )

  private def complete(blueprint: String, period: Period, id: String): RunSnapshot =
    agents.runs.start(blueprint, period.json, id): Unit
    val run = kit.awaitRun(agents.runs, id, 120.seconds)
    assertEquals(run.status, RunStatus.Completed, s"$id: ${run.reason.getOrElse("")}")
    runsById.put(id, run): Unit
    run

  private def stepResult(run: RunSnapshot, step: String): Json =
    run.stepNamed(step).flatMap(_.result).getOrElse(fail(s"no result for step '$step'"))

  private def identifiersRead(run: RunSnapshot): Vector[String] =
    Tools.papersOf(stepResult(run, "papers")("papers").getOrElse(Json.Null)).map(_.identifier)

  private def statements(run: RunSnapshot): Vector[(String, Option[String])] =
    stepResult(run, "script")("statements")
      .flatMap(_.asArray)
      .getOrElse(fail("no statements"))
      .map(s => (s("text").flatMap(_.asString).getOrElse(""), s("paper").flatMap(_.asString)))

  // ── The scripted model ────────────────────────────────────────────────────

  private def reads(request: ModelRequest): Json =
    Scripted
      .userText(request)
      .flatMap(t => Json.parse(t.dropWhile(_ != '\n')).toOption)
      .getOrElse(Json.obj())

  private def instructions(request: ModelRequest): String = request.systemMessage.getOrElse("")

  private def sourceOf(request: ModelRequest): String =
    "search_(\\w+)".r.findFirstMatchIn(instructions(request)).map(_.group(1)).getOrElse("?")

  private def lastToolResult(request: ModelRequest): ToolResult =
    request.messages.last match
      case ChatMessage.ToolResults(results) => results.last
      case other                            => fail(s"not after a tool: $other")

  private def date(instant: Json): String =
    instant.asString.map(s => LocalDate.ofInstant(Instant.parse(s), london).toString).getOrElse("")

  private def script(): Unit =
    // A source's worker: search, then answer with what came back, or say the source was unreachable.
    model.respondWhen(r => Scripted.forStep(r, "search") && !Scripted.afterTool(r)) { r =>
      val period = reads(r)("input").getOrElse(Json.obj())
      Scripted.call(
        s"search_${sourceOf(r)}",
        Json.obj(
          "query" -> Json.str("cultivated meat"),
          "from"  -> Json.str(date(period("from").getOrElse(Json.Null))),
          "to"    -> Json.str(date(period("to").getOrElse(Json.Null)))
        )
      )
    }
    model.respondWhen(r => Scripted.forStep(r, "search") && Scripted.afterTool(r)) { r =>
      val result = lastToolResult(r)
      if result.isError then
        Scripted.answer(
          Json
            .obj(
              "papers"  -> Json.arr(),
              "problem" -> Json.str(s"${sourceOf(r)} could not be reached: ${result.content}")
            )
            .render
        )
      else
        Scripted.answer(
          Json.obj("papers" -> Json.parse(result.content).getOrElse(Json.arr())).render
        )
    }
    // The keeper: one keep_papers per source that found papers, then the count.
    model.respondWhen(r => Scripted.forStep(r, "keep")) { r =>
      val found = reads(r)("search").flatMap(_.asArray).getOrElse(Vector.empty).flatMap { item =>
        for
          worker <- item("worker").flatMap(_.asString)
          papers <- item("result").flatMap(_("papers")).flatMap(_.asArray)
          if papers.nonEmpty
        yield (worker, papers)
      }
      val done = Scripted.toolResults(r)
      if done < found.size then
        val (worker, papers) = found(done)
        Scripted.call(
          "keep_papers",
          Json.obj("source" -> Json.str(worker), "papers" -> Json.str(Json.Arr(papers).render))
        )
      else Scripted.answer(Json.obj("kept" -> Json.num(found.map(_._2.size).sum)).render)
    }
    // The librarian: the period's papers, as the tool gives them.
    model.respondWhen(r => Scripted.forStep(r, "papers") && !Scripted.afterTool(r)) { r =>
      val period = reads(r)("input").getOrElse(Json.obj())
      Scripted.call(
        "papers_found_between",
        Json.obj(
          "from" -> period("from").getOrElse(Json.Null),
          "to"   -> period("to").getOrElse(Json.Null)
        )
      )
    }
    model.respondWhen(r => Scripted.forStep(r, "papers") && Scripted.afterTool(r)) { r =>
      Scripted.answer(
        Json.obj("papers" -> Json.parse(lastToolResult(r).content).getOrElse(Json.arr())).render
      )
    }
    // The reader: one statement per paper.
    model.respondWhen(r => Scripted.forStep(r, "read")) { r =>
      val item = reads(r)("item").getOrElse(Json.obj())
      val id   = item("identifier").flatMap(_.asString).getOrElse("?")
      Scripted.answer(
        Json
          .obj(
            "identifier" -> Json.str(id),
            "statement" -> Json.str(
              s"$id reports ${item("title").flatMap(_.asString).getOrElse("")}."
            )
          )
          .render
      )
    }
    // The editor: one theme over everything read.
    model.respondWhen(r => Scripted.forStep(r, "relate")) { r =>
      val ids = reads(r)("read").flatMap(_.asArray).getOrElse(Vector.empty).flatMap(_("identifier"))
      Scripted.answer(
        Json
          .obj(
            "themes" -> Json.arr(
              Json.obj("theme" -> Json.str("the week"), "papers" -> Json.Arr(ids))
            )
          )
          .render
      )
    }
    // The writer: a statement per paper read, or one saying the period held none.
    model.respondWhen(r => Scripted.forStep(r, "script")) { r =>
      val read = reads(r)("read").flatMap(_.asArray).getOrElse(Vector.empty)
      val statements =
        if read.isEmpty then
          Vector(Json.obj("text" -> Json.str("No papers were found in the period.")))
        else
          read.map(item =>
            Json.obj(
              "text"  -> item("statement").getOrElse(Json.str("")),
              "paper" -> item("identifier").getOrElse(Json.str(""))
            )
          )
      Scripted.answer(Json.obj("statements" -> Json.Arr(statements)).render)
    }: Unit

  // ── Background ────────────────────────────────────────────────────────────

  Given(
    "the research digest with scripted sources, a scripted model and a scripted judgment provider"
  ) { () =>
    assertEquals(agents.blueprints.versions("watch").size, 1)
    assertEquals(agents.blueprints.versions("digest").size, 1)
  }

  // ── The watch ─────────────────────────────────────────────────────────────

  Given("three sources that return forty papers, ten of them found by two sources") { () =>
    answers.put("europepmc", Right(papers(1 to 15))): Unit
    answers.put("biorxiv", Right(papers(11 to 25))): Unit
    answers.put("openalex", Right(papers(21 to 30))): Unit
  }

  When("a run of the watch is completed") { () =>
    complete("watch", weekEnding("2026-10-18"), s"$scenarioId-watch"): Unit
  }

  Then("thirty entries are kept, one per paper by its identifier") { () =>
    (1 to 30).foreach(n =>
      assert(entry(paper(n).identifier).exists(_.exists), s"paper $n is not kept")
    )
    assertEquals(entry(paper(31).identifier), None)
    assertEquals(stepResult(runsById(s"$scenarioId-watch"), "keep")("kept"), Some(Json.num(40)))
  }

  Then("each entry names every source that found it") { () =>
    def sourcesOf(n: Int) = entry(paper(n).identifier).map(_.sources.toSet).getOrElse(Set.empty)
    (1 to 10).foreach(n => assertEquals(sourcesOf(n), Set("europepmc"), s"paper $n"))
    (11 to 15).foreach(n => assertEquals(sourcesOf(n), Set("europepmc", "biorxiv"), s"paper $n"))
    (16 to 20).foreach(n => assertEquals(sourcesOf(n), Set("biorxiv"), s"paper $n"))
    (21 to 25).foreach(n => assertEquals(sourcesOf(n), Set("biorxiv", "openalex"), s"paper $n"))
    (26 to 30).foreach(n => assertEquals(sourcesOf(n), Set("openalex"), s"paper $n"))
  }

  Given("three sources, one of which cannot be reached") { () =>
    answers.put("europepmc", Right(papers(1 to 15))): Unit
    answers.put("biorxiv", Left("biorxiv could not be reached")): Unit
    answers.put("openalex", Right(papers(21 to 30))): Unit
  }

  Then("the entries from the two other sources are kept") { () =>
    ((1 to 15) ++ (21 to 30)).foreach(n =>
      assert(entry(paper(n).identifier).exists(_.exists), s"paper $n")
    )
    (16 to 20).foreach(n => assertEquals(entry(paper(n).identifier), None, s"paper $n"))
  }

  Then("the run names the source that could not be reached") { () =>
    val search =
      stepResult(runsById(s"$scenarioId-watch"), "search").asArray.getOrElse(Vector.empty)
    val biorxiv = search
      .find(_("worker").flatMap(_.asString).contains("biorxiv"))
      .getOrElse(fail("no result for biorxiv"))
    val problem = biorxiv("result").flatMap(_("problem")).flatMap(_.asString).getOrElse("")
    assert(problem.contains("could not be reached"), problem)
    assertEquals(biorxiv("result").flatMap(_("papers")).flatMap(_.asArray).map(_.size), Some(0))
  }

  // ── The digest ────────────────────────────────────────────────────────────

  private var period: Period = null

  Given("entries found before, during and after the period of a run of the digest") { () =>
    period = weekEnding("2026-11-01")
    keep(s"$scenarioId-before", "Before", "openalex", period.from.minusSeconds(86400).toEpochMilli)
    keep(
      s"$scenarioId-during",
      "During",
      "openalex",
      period.from.plusSeconds(3 * 86400).toEpochMilli
    )
    keep(s"$scenarioId-after", "After", "openalex", period.to.plusSeconds(86400).toEpochMilli)
    viewHolds(period, Set(s"$scenarioId-during"))
  }

  When("the run of the digest is completed") { () =>
    complete("digest", period, s"$scenarioId-digest"): Unit
  }

  Then("the digest read only the entries found during its period") { () =>
    assertEquals(identifiersRead(runsById(s"$scenarioId-digest")), Vector(s"$scenarioId-during"))
  }

  Given("a completed run of the digest that read thirty entries") { () =>
    period = weekEnding("2026-11-22")
    papers(1 to 30).foreach(p =>
      keep(p.identifier, p.title, "europepmc", period.from.plusSeconds(3600).toEpochMilli)
    )
    viewHolds(period, papers(1 to 30).map(_.identifier).toSet)
    val run = complete("digest", period, s"$scenarioId-digest")
    assertEquals(identifiersRead(run).size, 30)
  }

  private var read: Vector[(String, Option[String])] = Vector.empty

  When("a reader reads the script") { () =>
    read = statements(runsById(s"$scenarioId-digest"))
  }

  Then("every statement in the script names a paper") { () =>
    assert(read.nonEmpty)
    read.foreach((text, paper) => assert(paper.exists(_.nonEmpty), s"'$text' names no paper"))
  }

  Then("every paper named is one of the thirty") { () =>
    val thirty = papers(1 to 30).map(_.identifier).toSet
    assertEquals(read.flatMap(_._2).toSet, thirty)
  }

  Given("a paper published on {string} and first found on {string}") {
    (published: String, found: String) =>
      keep(s"$scenarioId-paper", s"Published $published", "biorxiv", noon(found))
  }

  When("the digests for the weeks ending {string} and {string} are completed") {
    (first: String, second: String) =>
      val later = weekEnding(second)
      viewHolds(later, Set(s"$scenarioId-paper"))
      complete("digest", weekEnding(first), s"$scenarioId-digest-$first"): Unit
      complete("digest", later, s"$scenarioId-digest-$second"): Unit
  }

  Then("the paper is read by the digest for the week ending {string} and by no other") {
    (week: String) =>
      val reading =
        runsById.filter((_, run) => identifiersRead(run).contains(s"$scenarioId-paper")).keys.toSet
      assertEquals(reading, Set(s"$scenarioId-digest-$week"))
  }

  Given("no entries found during the period of a run of the digest") { () =>
    period = weekEnding("2026-12-06")
    assertEquals(
      EntriesView.foundBetween(kit.service.viewClient, period.from, period.to),
      Vector.empty
    )
  }

  Then("the script says that no papers were found in the period") { () =>
    val run = runsById(s"$scenarioId-digest")
    assertEquals(identifiersRead(run), Vector.empty)
    val texts = statements(run).map(_._1)
    assert(texts.exists(_.toLowerCase.contains("no papers were found")), texts.toString)
  }
