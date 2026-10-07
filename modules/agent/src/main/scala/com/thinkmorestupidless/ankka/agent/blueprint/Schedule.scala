package com.thinkmorestupidless.ankka.agent.blueprint

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter}
import com.thinkmorestupidless.ankka.agent.Json

import java.time.{
  DayOfWeek,
  Duration,
  Instant,
  LocalDate,
  LocalTime,
  ZoneId,
  ZoneOffset,
  ZonedDateTime
}
import scala.util.Try

/** How often a schedule's due times come. */
enum Cadence:
  /**
   * Within each day of the schedule's zone, from midnight: `6` is 00:00, 06:00, 12:00 and 18:00.
   */
  case EveryHours(hours: Int)

  /** At midnight in the schedule's zone, on every so many days. */
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

/**
 * The span a scheduled run covers, which is its input: from the previous due time to its own, and
 * the due times within it, one unless the run catches up for several.
 */
final case class Period(from: Instant, to: Instant, dueTimes: Vector[Instant]):
  def length: Duration = Duration.between(from, to)

  def json: Json = Json.obj(
    "from"     -> Json.str(from.toString),
    "to"       -> Json.str(to.toString),
    "dueTimes" -> Json.arr(dueTimes.map(d => Json.str(d.toString))*)
  )

object Period:
  /** The shape a scheduled blueprint's input must admit. */
  val shape: Shape = Shape.obj(
    "from"     -> Shape.string,
    "to"       -> Shape.string,
    "dueTimes" -> Shape.arr(Shape.string)
  )

  /** One the check tries a scheduled blueprint's input shape with. */
  val example: Period =
    Period(
      Instant.EPOCH,
      Instant.EPOCH.plus(Duration.ofDays(7)),
      Vector(Instant.EPOCH.plus(Duration.ofDays(7)))
    )

  def fromJson(json: Json): Option[Period] =
    for
      from <- json("from").flatMap(_.asString).flatMap(s => Try(Instant.parse(s)).toOption)
      to   <- json("to").flatMap(_.asString).flatMap(s => Try(Instant.parse(s)).toOption)
      dues <- json("dueTimes").flatMap(_.asArray)
    yield Period(from, to, dues.flatMap(_.asString).flatMap(s => Try(Instant.parse(s)).toOption))

/**
 * A blueprint's cadence and zone, from which its due times follow. Due times are local times in the
 * zone, so a weekly schedule at 20:00 stays at 20:00 across a change of the clocks and the period
 * is an hour longer or shorter that week.
 */
final case class Schedule(cadence: Cadence, zone: String, catchUp: CatchUp = CatchUp.One):
  /** One run per missed due time after an outage, instead of one covering them all. */
  def perMissedPeriod: Schedule = copy(catchUp = CatchUp.Each)
  def zoneId: Option[ZoneId]    = Try(ZoneId.of(zone)).toOption

  private def zoneOrUtc: ZoneId = zoneId.getOrElse(ZoneOffset.UTC)

  /** The due times on one local date, in order. */
  private def dueOn(date: LocalDate): Vector[Instant] =
    val z = zoneOrUtc
    cadence match
      case Cadence.EveryHours(h) =>
        (0 until 24 by h.max(1)).toVector.map(hour =>
          ZonedDateTime.of(date, LocalTime.of(hour, 0), z).toInstant
        )
      case Cadence.EveryDays(d) =>
        if date.toEpochDay % d.max(1) == 0 then
          Vector(ZonedDateTime.of(date, LocalTime.MIDNIGHT, z).toInstant)
        else Vector.empty
      case Cadence.Weekly(day, time) =>
        if date.getDayOfWeek == DayOfWeek.valueOf(day) then
          Vector(ZonedDateTime.of(date, LocalTime.parse(time), z).toInstant)
        else Vector.empty

  /** The first due time after `after`. */
  def next(after: Instant): Instant =
    val date = after.atZone(zoneOrUtc).toLocalDate
    Iterator.iterate(date)(_.plusDays(1)).flatMap(dueOn).find(_.isAfter(after)).get

  /** The last due time before `before`: where the period ending at a due time begins. */
  def previous(before: Instant): Instant =
    val date = before.atZone(zoneOrUtc).toLocalDate
    Iterator
      .iterate(date)(_.minusDays(1))
      .flatMap(d => dueOn(d).reverse)
      .find(_.isBefore(before))
      .get

  /** Every due time after `after` up to and including `upTo`, in order: what an outage missed. */
  def dueTimes(after: Instant, upTo: Instant): Vector[Instant] =
    Iterator.iterate(next(after))(next).takeWhile(!_.isAfter(upTo)).toVector

  /**
   * The periods the runs for `missed` due times cover, from `lastEnd`: one covering them all, or
   * one each, as `catchUp` says. Periods meet.
   */
  def periods(lastEnd: Instant, missed: Vector[Instant]): Vector[Period] =
    if missed.isEmpty then Vector.empty
    else
      catchUp match
        case CatchUp.One => Vector(Period(lastEnd, missed.last, missed))
        case CatchUp.Each =>
          (lastEnd +: missed).zip(missed).map((from, to) => Period(from, to, Vector(to)))

object Schedule:
  def weekly(day: DayOfWeek, time: LocalTime, zone: ZoneId): Schedule =
    Schedule(Cadence.Weekly(day.name, time.toString.take(5)), zone.getId)
  def everyHours(hours: Int, zone: ZoneId = ZoneId.of("UTC")): Schedule =
    Schedule(Cadence.EveryHours(hours), zone.getId)
  def everyDays(days: Int, zone: ZoneId = ZoneId.of("UTC")): Schedule =
    Schedule(Cadence.EveryDays(days), zone.getId)
