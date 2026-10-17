package com.thinkmorestupidless.ankka.modelkeeper

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import com.thinkmorestupidless.ankka.core.{ModelServerDocument, ModelState, ModelStatus}
import com.thinkmorestupidless.ankka.modelkeeper.Reconcile.{Action, Backoff, Observed, Outcome}
import org.slf4j.LoggerFactory

import java.nio.file.{Files, NoSuchFileException, Path}
import java.time.Clock
import java.util.concurrent.atomic.AtomicLong
import scala.util.control.NonFatal

/**
 * The keeper's loop (contracts/keeper.md of feature 053): every pass reads the declaration, asks
 * Ollama what it holds and has loaded, plans with `Reconcile`, performs, and publishes the document
 * the door, the status port and the probe serve.
 *
 * A pass runs every `passInterval`, and at once when the declaration file changes, looked at each
 * second: the kubelet swaps a mounted ConfigMap's directory, which a watch would not reliably see.
 * A declaration that does not parse keeps the last good one, and the document says why it is not
 * ready.
 */
final class Keeper(
    settings: KeeperSettings,
    ollama: OllamaClient,
    clock: Clock = Clock.systemUTC()
):
  import Keeper.*

  private val log = LoggerFactory.getLogger("ankka.model-keeper")

  @volatile private var published: ModelServerDocument =
    ModelServerDocument(
      settings.address,
      ready = false,
      detail = Some("the first pass has not run")
    )
  @volatile private var probe: Either[String, Unit]        = Left("the first pass has not run")
  @volatile private var running: Option[Thread]            = None
  @volatile private var telemetry: Option[KeeperTelemetry] = None

  private val loadedCount = new AtomicLong

  // The loop's own state: one pass at a time, so no lock is needed beyond the method's.
  private var declaration: Vector[String]        = Vector.empty
  private var declarationStamp: Option[String]   = None
  private var declarationProblem: Option[String] = None
  private var recorded: Map[String, ModelStatus] = Map.empty
  private var backoff: Map[String, Backoff]      = Map.empty

  /** What the door serves. */
  val source: Door.Source = new Door.Source:
    def document: ModelServerDocument = published
    def ready: Either[String, Unit]   = probe

  /** What the gauges read. */
  val telemetrySource: KeeperTelemetry.Source =
    KeeperTelemetry.Source(() => published, () => loadedCount.get)

  /** Where a fetch failure and a pass's duration are counted. */
  def reportTo(t: KeeperTelemetry): Unit = telemetry = Some(t)

  def document: ModelServerDocument = published

  /** One pass, now. The loop calls it; a test may too. */
  def pass(): Unit = synchronized {
    val started = System.nanoTime()
    readDeclaration()
    ollama.version() match
      case Left(reason) =>
        probe = Left(reason)
        published = published.copy(ready = false, detail = Some(reason))
      case Right(_) =>
        observe() match
          case Left(reason) =>
            probe = Left(reason)
            published = published.copy(ready = false, detail = Some(reason))
          case Right(observed) =>
            val now      = clock.instant()
            val plan     = Reconcile.plan(declaration, observed, recorded, backoff, now)
            val outcomes = perform(plan.actions)
            val digests =
              if plan.actions.exists(_.isInstanceOf[Action.Pull]) then
                ollama.tags().getOrElse(observed.held)
              else observed.held
            val (models, kept) = Reconcile.settle(plan, outcomes, digests, backoff, now)
            say(recorded, models)
            recorded = models.map(m => m.name -> m).toMap
            backoff = kept
            loadedCount.set(ollama.ps().map(_.size.toLong).getOrElse(loadedCount.get))
            probe = Right(())
            val detail = declarationProblem.orElse(plan.detail)
            published = ModelServerDocument(
              server = settings.address,
              ready = declarationProblem.isEmpty,
              pass = Some(now.toString),
              models = models,
              detail = detail
            )
    telemetry.foreach(_.passTook(System.nanoTime() - started))
  }

  /** Runs the loop on a thread of its own until `stop`. */
  def start(): Unit = synchronized {
    require(running.isEmpty, "the keeper is already running")
    val thread = Thread
      .ofVirtual()
      .name("ankka-model-keeper")
      .unstarted(() =>
        var last = 0L
        try
          while !Thread.currentThread().isInterrupted do
            val due = System.nanoTime() - last >= settings.passInterval.toNanos
            if last == 0L || due || declarationChanged then
              try pass()
              catch case NonFatal(e) => log.warn("a pass failed: {}", e.toString)
              last = System.nanoTime()
            Thread.sleep(WatchMillis)
        catch case _: InterruptedException => ()
      )
    thread.start()
    running = Some(thread)
  }

  def stop(): Unit =
    running.foreach { t =>
      t.interrupt()
      t.join(5000)
    }
    running = None

  private def declarationChanged: Boolean =
    stamp(settings.declaration) != declarationStamp

  private def readDeclaration(): Unit =
    val now = stamp(settings.declaration)
    if now != declarationStamp || declarationStamp.isEmpty then
      declarationStamp = now
      read(settings.declaration) match
        case Right(models) =>
          if models != declaration then
            if declaration.nonEmpty || recorded.nonEmpty then
              log.info("the declaration changed: {}", models.mkString(", "))
            // A change to what is declared is a reason to try every failed fetch again at once.
            backoff = Map.empty
          declaration = models
          declarationProblem = None
        case Left(problem) =>
          log.warn("the declaration cannot be read, keeping the last good one: {}", problem)
          declarationProblem = Some(problem)

  private def observe(): Either[String, Observed] =
    for
      held   <- ollama.tags()
      loaded <- ollama.ps()
    yield Observed(held, loaded)

  /** Each action in turn; a tag whose action failed has its later actions skipped. */
  private def perform(actions: Vector[Action]): Vector[Outcome] =
    var failed   = Set.empty[String]
    val outcomes = Vector.newBuilder[Outcome]
    actions.foreach { action =>
      if !failed.contains(action.tag) then
        val result: Either[String, Vector[String]] = action match
          case Action.Pull(tag) =>
            log.info("fetching {}", tag)
            ollama.pull(tag).map(_ => Vector.empty)
          case Action.Load(tag)   => ollama.load(tag).map(_ => Vector.empty)
          case Action.Record(tag) => ollama.show(tag)
          case Action.Delete(tag) => ollama.delete(tag).map(_ => Vector.empty)
        if result.isLeft then
          failed += action.tag
          action match
            case Action.Pull(tag) => telemetry.foreach(_.fetchFailed(tag))
            case _                => ()
        outcomes += Outcome(action, result)
    }
    outcomes.result()

  /** One line per model whose state changed, and one per model that left. */
  private def say(before: Map[String, ModelStatus], after: Vector[ModelStatus]): Unit =
    after.foreach { model =>
      val was = before.get(model.name).map(_.state)
      if !was.contains(model.state) then
        model.state match
          case ModelState.Served =>
            log.info("served {} {}", model.name, model.digest.map(_.take(12)).getOrElse(""))
          case ModelState.Failed =>
            log.warn("failed {}: {}", model.name, model.detail.getOrElse(""))
          case ModelState.Removing => log.info("removing {}", model.name)
          case _                   => ()
    }
    val names = after.map(_.name).toSet
    before.keys.filterNot(names.contains).foreach(name => log.info("removed {}", name))

object Keeper:

  /** How often the declaration file's modification time is looked at. */
  val WatchMillis: Long = 1000L

  final case class Declaration(models: Vector[String] = Vector.empty)

  given JsonValueCodec[Declaration] = JsonCodecMaker.make

  /** The declared tags, or why the file is not a declaration. */
  def read(file: Path): Either[String, Vector[String]] =
    try
      val text = Files.readString(file)
      Right(readFromString[Declaration](text).models.map(_.trim).filter(_.nonEmpty))
    catch
      case _: NoSuchFileException => Left(s"the declaration $file does not exist")
      case e: JsonReaderException =>
        Left(s"the declaration $file is not {\"models\": [...]}: ${e.getMessage}")
      case NonFatal(e) => Left(s"the declaration $file cannot be read: ${e.getMessage}")

  /**
   * What the file holds now, as a fingerprint; none when it is missing. Compared each second rather
   * than its modification time: a mounted ConfigMap's file is a link into a directory the kubelet
   * replaces, and two writes within one tick of the clock look like none.
   */
  def stamp(file: Path): Option[String] =
    try
      val digest =
        java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))
      Some(digest.map(b => f"$b%02x").mkString)
    catch case NonFatal(_) => None
