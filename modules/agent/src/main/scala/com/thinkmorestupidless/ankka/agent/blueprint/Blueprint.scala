package com.thinkmorestupidless.ankka.agent.blueprint

import com.github.plokhotnyuk.jsoniter_scala.core.{
  readFromString,
  writeToString,
  JsonReader,
  JsonValueCodec,
  JsonWriter
}
import com.thinkmorestupidless.ankka.agent.Json
import com.thinkmorestupidless.ankka.core.Codecs

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.{DayOfWeek, LocalTime, ZoneId}
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

/**
 * An agent a blueprint defines by data. It is not a component: one platform agent serves every
 * worker, building each turn from the worker's definition.
 */
final case class Worker(
    name: String,
    instructions: String = "",
    model: String = Worker.DefaultModel,
    tools: Vector[String] = Vector.empty,
    guardrails: Vector[String] = Vector.empty,
    budget: Int = 0
):
  def instructions(text: String): Worker = copy(instructions = text)
  def model(name: String): Worker        = copy(model = name)
  def tools(names: String*): Worker      = copy(tools = tools ++ names)
  def guardrails(names: String*): Worker = copy(guardrails = guardrails ++ names)

  /** Model calls for one turn of this worker: an item, a round, or the task of a work step. */
  def budget(modelCalls: Int): Worker = copy(budget = modelCalls)

object Worker:
  /** The runtime's default model, under the one name every service has. */
  val DefaultModel = "default"

/** What passes a draft or returns it with reasons. */
enum Verdict:
  /** Another worker answers with `{ "passed": boolean, "reasons": [string] }`. */
  case Critic(worker: String)

  /** A judgment's yes-or-no question, asked with the draft as its state. */
  case Judgment(question: String)

object Verdict:
  def critic(worker: String): Verdict     = Critic(worker)
  def judgment(question: String): Verdict = Judgment(question)

  /** The shape a critic's answer must have. */
  val shape: Shape = Shape.obj("passed" -> Shape.boolean, "reasons" -> Shape.arr(Shape.string))

/**
 * What a step does once. A worker's name is left out when the step's `over` names the workers.
 */
enum Action:
  /** One worker answers the step's input once, running its tools, as a request agent does. */
  case Ask(worker: Option[String] = None)

  /** One worker iterates on the step's input until it completes, gives up or spends its budget. */
  case Work(worker: Option[String] = None)

  /** The named judgment questions, asked about the step's input. */
  case Judge(questions: Vector[String])

  /** A handler the service registers for blueprints, given what the step reads. */
  case Call(handler: String)

  /** The worker the action names, when it names one. */
  def namedWorker: Option[String] = this match
    case Ask(w)  => w
    case Work(w) => w
    case _       => None

  /** Whether this is a worker's turn, which `over` and `until` may repeat. */
  def isTurn: Boolean = this match
    case Ask(_) | Work(_) => true
    case _                => false

/**
 * How many times a step does its action, and over what. A read is `input`, a step's name for its
 * result, or `step.field` for one field of it.
 */
enum Over:
  /** Once, with what the step reads. */
  case Once

  /** Once per item of the list `read` gives, at most `limit` at once. */
  case Each(read: String, limit: Int = 4, keepGoing: Boolean = false)

  /**
   * Once per worker, each given the same input at once. `chosenBy` reads a list of names from an
   * earlier step; then only those among `workers` run.
   */
  case Workers(workers: Vector[String], chosenBy: Option[String] = None)

  /** `n` times, the same worker given the same input at once. */
  case Times(n: Int)

  /** The reads this makes beyond the step's own. */
  def reads: Vector[String] = this match
    case Each(read, _, _)     => Vector(read)
    case Workers(_, Some(by)) => Vector(by)
    case _                    => Vector.empty

/** Drafts go back with the verdict's reasons until one passes, up to `rounds` rounds. */
final case class Until(verdict: Verdict, rounds: Int, keepLast: Boolean = false)

/**
 * One unit of a run's work: what it does, how many times and over what, until what, what it reads,
 * and the shape of its result. The common shapes have names: an ask step, a work step, a for-each
 * step, a gather step, a judge step, a critique step, a call step.
 */
final case class Step(
    name: String,
    does: Action,
    over: Over = Over.Once,
    until: Option[Until] = None,
    reads: Vector[String] = Vector.empty,
    result: Shape = Shape.string
):
  def reads(names: String*): Step = copy(reads = reads ++ names)
  def result(shape: Shape): Step  = copy(result = shape)
  def each(read: String, limit: Int = 4, keepGoing: Boolean = false): Step =
    copy(over = Over.Each(read, limit, keepGoing))
  def overWorkers(workers: String*): Step = copy(over = Over.Workers(workers.toVector))
  def chosenBy(read: String): Step = over match
    case Over.Workers(ws, _) => copy(over = Over.Workers(ws, Some(read)))
    case _                   => copy(over = Over.Workers(Vector.empty, Some(read)))
  def times(n: Int): Step = copy(over = Over.Times(n))
  def until(verdict: Verdict, rounds: Int, keepLast: Boolean = false): Step =
    copy(until = Some(Until(verdict, rounds, keepLast)))

  /** Every worker this step names. */
  def namedWorkers: Vector[String] =
    does.namedWorker.toVector ++ (over match
      case Over.Workers(ws, _) => ws
      case _                   => Vector.empty) ++ until.toVector.flatMap(_.verdict match
      case Verdict.Critic(c) => Vector(c)
      case _                 => Vector.empty)

  /** Every judgment question this step names. */
  def namedQuestions: Vector[String] =
    (does match
      case Action.Judge(qs) => qs
      case _                => Vector.empty
    ) ++ until.toVector.flatMap(_.verdict match
      case Verdict.Judgment(q) => Vector(q)
      case _                   => Vector.empty)

  /** The handler this step calls, when it does. */
  def namedHandler: Option[String] = does match
    case Action.Call(h) => Some(h)
    case _              => None

  /** Everything this step reads: its own reads and its `over`'s. */
  def allReads: Vector[String] = (reads ++ over.reads).distinct

object Step:
  /** `Step("name").ask("worker")` and the other common shapes. */
  def apply(name: String): Start = Start(name)

  final case class Start(name: String):
    def ask(worker: String): Step       = Step(name, Action.Ask(Some(worker)))
    def work(worker: String): Step      = Step(name, Action.Work(Some(worker)))
    def judge(questions: String*): Step = Step(name, Action.Judge(questions.toVector))
    def call(handler: String): Step     = Step(name, Action.Call(handler))

    /** A for-each step: one worker, once per item. */
    def forEach(worker: String, over: String, limit: Int = 4, keepGoing: Boolean = false): Step =
      Step(name, Action.Ask(Some(worker)), Over.Each(over, limit, keepGoing))

    /** A gather step: several workers, the same input. */
    def gather(workers: String*): Step =
      Step(name, Action.Ask(None), Over.Workers(workers.toVector))
    def gather(workers: Seq[String], chosenBy: String): Step =
      Step(name, Action.Ask(None), Over.Workers(workers.toVector, Some(chosenBy)))

    /** A gather step: one worker several times. */
    def gather(worker: String, times: Int): Step =
      Step(name, Action.Ask(Some(worker)), Over.Times(times))

    /** A critique step: one worker drafts until a verdict passes. */
    def critique(drafter: String, verdict: Verdict, rounds: Int, keepLast: Boolean = false): Step =
      Step(name, Action.Ask(Some(drafter)), Over.Once, Some(Until(verdict, rounds, keepLast)))

/** How often a schedule's due times come. */
enum Cadence:
  case EveryHours(hours: Int)
  case EveryDays(days: Int)

  /** `day` is a `DayOfWeek` name; `time` is `HH:mm`, local to the schedule's zone. */
  case Weekly(day: String, time: String)

/** What due times missed in an outage start: one run covering them all, or one run each. */
enum CatchUp:
  case One, Each

object CatchUp:
  /** A fieldless enum would otherwise be written as `{"type":"One"}`. */
  given JsonValueCodec[CatchUp] = new JsonValueCodec[CatchUp]:
    def decodeValue(in: JsonReader, default: CatchUp): CatchUp = in.readString(null) match
      case "one"  => One
      case "each" => Each
      case other  => in.decodeError(s"catchUp must be 'one' or 'each', not '$other'")
    def encodeValue(x: CatchUp, out: JsonWriter): Unit = out.writeVal(x match
      case One  => "one"
      case Each => "each")
    def nullValue: CatchUp = null

/** A blueprint's cadence and zone, from which its due times follow. */
final case class Schedule(cadence: Cadence, zone: String, catchUp: CatchUp = CatchUp.One):
  /** One run per missed due time after an outage, instead of one covering them all. */
  def perMissedPeriod: Schedule = copy(catchUp = CatchUp.Each)
  def zoneId: Option[ZoneId]    = Try(ZoneId.of(zone)).toOption

object Schedule:
  def weekly(day: DayOfWeek, time: LocalTime, zone: ZoneId): Schedule =
    Schedule(Cadence.Weekly(day.name, time.toString.take(5)), zone.getId)
  def everyHours(hours: Int, zone: ZoneId = ZoneId.of("UTC")): Schedule =
    Schedule(Cadence.EveryHours(hours), zone.getId)
  def everyDays(days: Int, zone: ZoneId = ZoneId.of("UTC")): Schedule =
    Schedule(Cadence.EveryDays(days), zone.getId)

/**
 * A description of workers and the steps between them, which a service registers and the platform
 * runs. It is held, never deployed; a changed blueprint is a new version. Its steps form a graph:
 * each reads the run's input or steps before it, and runs once what it reads has ended. It has no
 * conditions and no loops of its own: every repetition is inside a step and bounded by it.
 */
final case class Blueprint(
    name: String,
    input: Shape = Shape.obj(),
    workers: Vector[Worker] = Vector.empty,
    steps: Vector[Step] = Vector.empty,
    schedule: Option[Schedule] = None,
    runBudget: Option[Int] = None,
    timeLimit: Option[java.time.Duration] = None
):
  def input(shape: Shape): Blueprint        = copy(input = shape)
  def worker(w: Worker): Blueprint          = copy(workers = workers :+ w)
  def step(s: Step): Blueprint              = copy(steps = steps :+ s)
  def schedule(s: Schedule): Blueprint      = copy(schedule = Some(s))
  def runBudget(modelCalls: Int): Blueprint = copy(runBudget = Some(modelCalls))
  def timeLimit(limit: FiniteDuration): Blueprint =
    copy(timeLimit = Some(java.time.Duration.ofMillis(limit.toMillis)))

  /** The blueprint as JSON, as written: field order is the codec's. */
  def json: String = writeToString(this)(using Blueprint.codec)

  /**
   * The one text for this blueprint whatever order it was written in: keys sorted at every level,
   * no insignificant whitespace. Two blueprints are the same version when their canonical texts
   * are.
   */
  def canonical: String =
    Json
      .parse(json)
      .fold(e => throw IllegalStateException(s"a blueprint did not re-read: $e"), Canonical.render)

  /** SHA-256 of `canonical`, in lower-case hex. */
  def digest: String = Blueprint.digestOf(canonical)

object Blueprint:
  given codec: JsonValueCodec[Blueprint] = Codecs.make

  def fromJson(text: String): Either[String, Blueprint] =
    Try(readFromString(text)(using codec)).toEither.left.map(e =>
      s"not a blueprint: ${e.getMessage}"
    )

  /** A blueprint carried in a service's code, as a resource on its class path. */
  def fromResource(path: String): Blueprint =
    val stream = Option(getClass.getClassLoader.getResourceAsStream(path))
      .getOrElse(throw IllegalArgumentException(s"no resource '$path' on the class path"))
    val text =
      try String(stream.readAllBytes(), StandardCharsets.UTF_8)
      finally stream.close()
    fromJson(text).fold(e => throw IllegalArgumentException(s"resource '$path' is $e"), identity)

  private[blueprint] def digestOf(text: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(text.getBytes(StandardCharsets.UTF_8))
      .map(b => f"$b%02x")
      .mkString

/** Renders JSON with object keys sorted at every level: the one text for one value. */
private[blueprint] object Canonical:
  def render(json: Json): String =
    val out = StringBuilder()
    write(json, out)
    out.toString

  private def write(json: Json, out: StringBuilder): Unit = json match
    case Json.Obj(fields) =>
      out.append('{')
      var first = true
      fields.toVector.sortBy(_._1).foreach { (k, v) =>
        if !first then out.append(',')
        first = false
        out.append(Json.Str(k).render).append(':')
        write(v, out)
      }
      out.append('}')
    case Json.Arr(values) =>
      out.append('[')
      var first = true
      values.foreach { v =>
        if !first then out.append(',')
        first = false
        write(v, out)
      }
      out.append(']')
    case scalar => out.append(scalar.render)
