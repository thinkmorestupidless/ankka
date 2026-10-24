package com.thinkmorestupidless.ankka.modelkeeper

import com.thinkmorestupidless.ankka.core.{ModelState, ModelStatus}
import com.thinkmorestupidless.ankka.modelkeeper.Reconcile.*

import java.time.{Duration, Instant}

/** The keeper's plan, row by row of the data model's state table, with nothing performed. */
class ReconcileSuite extends munit.FunSuite:

  private val now   = Instant.parse("2026-10-17T15:00:00Z")
  private val small = "qwen3:0.6b"
  private val other = "llama3.2:1b"

  private def plan(
      declaration: Vector[String],
      held: Map[String, String] = Map.empty,
      loaded: Set[String] = Set.empty,
      recorded: Map[String, ModelStatus] = Map.empty,
      backoff: Map[String, Backoff] = Map.empty
  ): Plan = Reconcile.plan(declaration, Observed(held, loaded), recorded, backoff, now)

  private def served(name: String, capabilities: Vector[String] = Vector("completion", "tools")) =
    name -> ModelStatus(name, ModelState.Served, Some("d"), capabilities, since = Some("earlier"))

  private def state(p: Plan, name: String): String =
    p.models.find(_.name == name).map(_.state).getOrElse(fail(s"no line for $name"))

  test("a declared model Ollama does not hold is fetched, loaded and read, and is fetching") {
    val p = plan(Vector(small))
    assertEquals(p.actions, Vector(Action.Pull(small), Action.Load(small), Action.Record(small)))
    assertEquals(state(p, small), ModelState.Fetching)
  }

  test("models are fetched one at a time, in the declaration's order") {
    val p = plan(Vector(small, other))
    assertEquals(p.actions.collect { case a: Action.Pull => a }, Vector(Action.Pull(small)))
    assertEquals(state(p, other), ModelState.Fetching)
  }

  test("a held model that is not loaded is loaded, and is fetching until it is") {
    val p = plan(Vector(small), held = Map(small -> "abc"))
    assertEquals(p.actions, Vector(Action.Load(small), Action.Record(small)))
    assertEquals(p.models.head.state, ModelState.Fetching)
    assertEquals(p.models.head.digest, Some("abc"))
  }

  test("a held, loaded and read model is served with its digest, and nothing is done") {
    val p = plan(
      Vector(small),
      held = Map(small -> "abc"),
      loaded = Set(small),
      recorded = Map(served(small))
    )
    assertEquals(p.actions, Vector.empty)
    assertEquals(p.models.head.state, ModelState.Served)
    assertEquals(p.models.head.digest, Some("abc"))
    assertEquals(p.models.head.since, Some("earlier"))
  }

  test("a served model is never fetched again") {
    val after = plan(
      Vector(small),
      held = Map(small -> "abc"),
      loaded = Set(small),
      recorded = Map(served(small))
    )
    assert(!after.actions.exists(_.isInstanceOf[Action.Pull]), after.actions.toString)
    // A restart finds it held and not loaded: it is loaded, never fetched.
    val restarted = plan(Vector(small), held = Map(small -> "abc"), recorded = Map(served(small)))
    assertEquals(restarted.actions, Vector(Action.Load(small)))
  }

  test("a loaded model whose capabilities are not read yet has them read") {
    val p = plan(Vector(small), held = Map(small -> "abc"), loaded = Set(small))
    assertEquals(p.actions, Vector(Action.Record(small)))
  }

  test("a model removed from the declaration is no longer served") {
    val p = plan(
      Vector(small),
      held = Map(small -> "abc", other -> "def"),
      loaded = Set(small, other),
      recorded = Map(served(small), served(other))
    )
    assertEquals(p.actions, Vector(Action.Delete(other)))
    assertEquals(state(p, other), ModelState.Removing)
    val (models, _) =
      Reconcile.settle(
        p,
        Vector(Outcome(Action.Delete(other), Right(Vector.empty))),
        Map(small -> "abc"),
        Map.empty,
        now
      )
    assertEquals(models.map(_.name), Vector(small))
  }

  test("a failed model waiting out its backoff is reported failed and not tried") {
    val wait = Backoff(1, now.plusSeconds(30), "pull model manifest: file does not exist")
    val p    = plan(Vector("no-such-model:1b"), backoff = Map("no-such-model:1b" -> wait))
    assertEquals(p.actions, Vector.empty)
    assertEquals(p.models.head.state, ModelState.Failed)
    assertEquals(p.models.head.detail, Some("pull model manifest: file does not exist"))
  }

  test("a failed model whose backoff has passed is fetched again") {
    val passed = Backoff(1, now.minusSeconds(1), "busy")
    val p      = plan(Vector(small), backoff = Map(small -> passed))
    assertEquals(p.actions.head, Action.Pull(small))
    assertEquals(p.models.head.state, ModelState.Fetching)
  }

  test("an empty declaration plans nothing and reports nothing") {
    val p = plan(Vector.empty)
    assertEquals(p.actions, Vector.empty)
    assertEquals(p.models, Vector.empty)
    assertEquals(p.detail, None)
  }

  test("a tag declared twice is fetched once, and the document says the second was ignored") {
    val p = plan(Vector(small, other, small))
    assertEquals(p.models.map(_.name), Vector(small, other))
    assertEquals(p.detail, Some(s"declared more than once, the later ignored: $small"))
  }

  test("a tag without a version is Ollama's latest") {
    assertEquals(canonical("llama3"), "llama3:latest")
    assertEquals(canonical("qwen3:0.6b"), "qwen3:0.6b")
    assertEquals(canonical("hf.co/org/model:Q4"), "hf.co/org/model:Q4")
    val p =
      plan(Vector("llama3"), held = Map("llama3:latest" -> "abc"), loaded = Set("llama3:latest"))
    assertEquals(p.models.head.name, "llama3")
    assertEquals(p.models.head.state, ModelState.Served)
  }

  test("a fetch that succeeds settles as served, with the digest and capabilities Ollama reports") {
    val p        = plan(Vector(small))
    val outcomes = Vector(
      Outcome(Action.Pull(small), Right(Vector.empty)),
      Outcome(Action.Load(small), Right(Vector.empty)),
      Outcome(Action.Record(small), Right(Vector("completion", "tools", "thinking")))
    )
    val (models, backoff) = Reconcile.settle(p, outcomes, Map(small -> "abc"), Map.empty, now)
    assertEquals(models.head.state, ModelState.Served)
    assertEquals(models.head.digest, Some("abc"))
    assertEquals(models.head.capabilities, Vector("completion", "tools", "thinking"))
    assertEquals(backoff, Map.empty)
  }

  test(
    "a fetch that fails settles as failed with Ollama's line, and waits a minute, doubling to an hour"
  ) {
    val p               = plan(Vector("no-such-model:1b"))
    val reason          = "pull model manifest: file does not exist"
    val failed          = Vector(Outcome(Action.Pull("no-such-model:1b"), Left(reason)))
    val (models, first) = Reconcile.settle(p, failed, Map.empty, Map.empty, now)
    assertEquals(models.head.state, ModelState.Failed)
    assertEquals(models.head.detail, Some(reason))
    assertEquals(first("no-such-model:1b").until, now.plus(Duration.ofMinutes(1)))
    val (_, second) = Reconcile.settle(p, failed, Map.empty, first, now)
    assertEquals(second("no-such-model:1b").until, now.plus(Duration.ofMinutes(2)))
    assertEquals(Reconcile.wait(20), Duration.ofHours(1))
  }

  test("the other models are reported as they stand while one fails") {
    val p = plan(
      Vector(small, "no-such-model:1b"),
      held = Map(small -> "abc"),
      loaded = Set(small),
      recorded = Map(served(small))
    )
    val (models, _) = Reconcile.settle(
      p,
      Vector(Outcome(Action.Pull("no-such-model:1b"), Left("no"))),
      Map(small -> "abc"),
      Map.empty,
      now
    )
    assertEquals(
      models.map(m => m.name -> m.state),
      Vector(small -> ModelState.Served, "no-such-model:1b" -> ModelState.Failed)
    )
  }
