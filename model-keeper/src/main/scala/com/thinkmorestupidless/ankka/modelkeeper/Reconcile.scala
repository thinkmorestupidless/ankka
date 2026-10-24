package com.thinkmorestupidless.ankka.modelkeeper

import com.thinkmorestupidless.ankka.core.{ModelState, ModelStatus}

import java.time.{Duration, Instant}

/**
 * What the keeper does in one pass, decided purely from the declaration and what Ollama reports
 * (feature 053's data model): which models to fetch, load, read or remove, and what each is
 * reported as. `Keeper` performs the actions and `settle` folds what came of them into the lines
 * the status document publishes.
 *
 * The rules: models are fetched one at a time, in the declaration's order; a held model is loaded
 * until Ollama reports it loaded; its capabilities are read once; a held model the declaration no
 * longer names is removed; a failed fetch waits a minute, then two, doubling to an hour. A served
 * model is never fetched again.
 */
object Reconcile:

  enum Action:
    /** Fetch the tag from Ollama's registry. Minutes; at most one a pass. */
    case Pull(tag: String)

    /** Load it and keep it loaded for ever (`keep_alive: -1`). */
    case Load(tag: String)

    /** Read its capabilities. */
    case Record(tag: String)

    /** Remove it from the server. */
    case Delete(tag: String)

    def tag: String

  /** What Ollama reports: each held model's digest, and which are loaded, by canonical name. */
  final case class Observed(held: Map[String, String], loaded: Set[String])

  /** A tag that failed: how often in a row, when to try again, and why it failed. */
  final case class Backoff(failures: Int, until: Instant, reason: String)

  /** One pass's actions, in the order to perform them, and each line as it stands before them. */
  final case class Plan(
      actions: Vector[Action],
      models: Vector[ModelStatus],
      detail: Option[String]
  )

  /** What came of one action: done, or why not; and for `Record`, the capabilities read. */
  final case class Outcome(action: Action, result: Either[String, Vector[String]])

  val FirstBackoff: Duration = Duration.ofMinutes(1)
  val LastBackoff: Duration  = Duration.ofHours(1)

  /** Ollama's own form of a tag: `llama3` is `llama3:latest`. */
  def canonical(tag: String): String =
    val name = tag.trim
    val last = name.substring(name.lastIndexOf('/') + 1)
    if last.contains(':') then name else s"$name:latest"

  def plan(
      declaration: Vector[String],
      observed: Observed,
      recorded: Map[String, ModelStatus],
      backoff: Map[String, Backoff],
      now: Instant
  ): Plan =
    val declared     = declaration.map(_.trim).filter(_.nonEmpty)
    val distinct     = declared.distinctBy(canonical)
    val repeated     = declared.diff(distinct).distinct
    val declaredKeys = distinct.map(canonical).toSet
    var pulling      = false
    val perTag       = distinct.map { tag =>
      val key      = canonical(tag)
      val previous = recorded.get(tag)
      val known    = previous.map(_.capabilities).getOrElse(Vector.empty)
      def line(state: String, digest: Option[String] = None, detail: Option[String] = None) =
        ModelStatus(
          name = tag,
          state = state,
          digest = digest,
          capabilities = known,
          detail = detail,
          since = previous.filter(_.state == state).flatMap(_.since).orElse(Some(now.toString))
        )
      observed.held.get(key) match
        case None =>
          backoff.get(tag) match
            case Some(b) if now.isBefore(b.until) =>
              (Vector.empty, line(ModelState.Failed, detail = Some(b.reason)))
            case _ if pulling => (Vector.empty, line(ModelState.Fetching))
            case _            =>
              pulling = true
              (
                Vector(Action.Pull(tag), Action.Load(tag), Action.Record(tag)),
                line(ModelState.Fetching)
              )
        case Some(digest) =>
          val record = Option.when(known.isEmpty)(Action.Record(tag)).toVector
          if observed.loaded.contains(key) then
            (record, line(ModelState.Served, digest = Some(digest)))
          else (Action.Load(tag) +: record, line(ModelState.Fetching, digest = Some(digest)))
    }
    val removals = observed.held.keys.toVector.sorted.filterNot(declaredKeys.contains).map { key =>
      val previous = recorded.get(key)
      (
        Vector(Action.Delete(key)),
        ModelStatus(
          name = key,
          state = ModelState.Removing,
          digest = observed.held.get(key),
          capabilities = previous.map(_.capabilities).getOrElse(Vector.empty),
          since = previous
            .filter(_.state == ModelState.Removing)
            .flatMap(_.since)
            .orElse(Some(now.toString))
        )
      )
    }
    val all = perTag ++ removals
    Plan(
      actions = all.flatMap(_._1),
      models = all.map(_._2),
      detail = Option.when(repeated.nonEmpty)(
        s"declared more than once, the later ignored: ${repeated.mkString(", ")}"
      )
    )

  /**
   * The lines once the pass's actions are done, and the backoff to keep. A model whose every action
   * succeeded is served (a removed one leaves the list); one whose action failed is `failed` with
   * the reason and waits before it is tried again. `digests` is what Ollama reported after the
   * pass.
   */
  def settle(
      plan: Plan,
      outcomes: Vector[Outcome],
      digests: Map[String, String],
      backoff: Map[String, Backoff],
      now: Instant
  ): (Vector[ModelStatus], Map[String, Backoff]) =
    val byTag  = outcomes.groupBy(_.action.tag)
    var kept   = backoff
    val models = plan.models.flatMap { model =>
      byTag.get(model.name) match
        case None =>
          // Nothing was asked of it this pass.
          if model.state == ModelState.Served then kept = kept - model.name
          Some(model)
        case Some(results) =>
          results.collectFirst { case Outcome(_, Left(reason)) => reason } match
            case Some(reason) =>
              val failures = kept.get(model.name).map(_.failures + 1).getOrElse(1)
              kept = kept.updated(model.name, Backoff(failures, now.plus(wait(failures)), reason))
              if model.state == ModelState.Removing then Some(model.copy(detail = Some(reason)))
              else
                Some(
                  model.copy(
                    state = ModelState.Failed,
                    detail = Some(reason),
                    since = Some(now.toString)
                  )
                )
            case None if results.exists(_.action.isInstanceOf[Action.Delete]) =>
              kept = kept - model.name
              None
            case None =>
              kept = kept - model.name
              val capabilities = results
                .collectFirst { case Outcome(Action.Record(_), Right(c)) => c }
                .getOrElse(model.capabilities)
              Some(
                model.copy(
                  state = ModelState.Served,
                  digest = digests.get(canonical(model.name)).orElse(model.digest),
                  capabilities = capabilities,
                  detail = None,
                  since =
                    if model.state == ModelState.Served then model.since else Some(now.toString)
                )
              )
    }
    (models, kept)

  /** One minute after the first failure, doubling, never more than an hour. */
  def wait(failures: Int): Duration =
    val doubled = FirstBackoff.multipliedBy(1L << math.min(failures - 1, 10))
    if doubled.compareTo(LastBackoff) > 0 then LastBackoff else doubled
