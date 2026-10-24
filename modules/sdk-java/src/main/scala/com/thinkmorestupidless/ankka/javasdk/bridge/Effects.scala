package com.thinkmorestupidless.ankka.javasdk.bridge

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode, Metadata}
import com.thinkmorestupidless.ankka.core.effect as se
import com.thinkmorestupidless.ankka.core.graph.PropertyValue
import com.thinkmorestupidless.ankka.sdk
import com.thinkmorestupidless.ankka.javasdk.Done
import com.thinkmorestupidless.ankka.javasdk.effect.*
import com.thinkmorestupidless.ankka.javasdk.http.Problem

import java.time.Duration
import java.util.function.Function
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/**
 * The Java effects: each holds the Scala effect the same handler would have returned in Scala, so
 * the hosts and the test kits reduce it with the same `materialise` and cannot disagree.
 */
object Effects:

  /** Read by the Java base classes; one instance serves every entity, as in Scala. */
  def eventSourced(): EventSourcedEffects[?, ?] = EventSourced

  def keyValue(): KeyValueEffects[?] = KeyValue

  /** The Scala effect a Java handler returned. */
  private[ankka] def unwrap(effect: Any, where: => String): Any = effect match
    case held: Holding => held.scala
    case null          => throw IllegalStateException(s"$where returned null rather than an effect")
    case other         =>
      throw IllegalStateException(
        s"$where returned ${other.getClass.getName}, which is not an effect built by effects()"
      )

  /** A handler's problem as the platform's command error. */
  private[ankka] def toCommandError(problem: Problem): CommandError =
    CommandError(
      if problem.detail.isEmpty then problem.title else problem.detail,
      code(problem.status)
    )

  /** A command error as the problem a Java caller receives. */
  private[ankka] def toProblem(error: CommandError): Problem =
    val (status, title) = error.code match
      case ErrorCode.BadRequest     => 400 -> "Bad Request"
      case ErrorCode.Unauthorized   => 401 -> "Unauthorized"
      case ErrorCode.Forbidden      => 403 -> "Forbidden"
      case ErrorCode.NotFound       => 404 -> "Not Found"
      case ErrorCode.Conflict       => 409 -> "Conflict"
      case ErrorCode.Unavailable    => 503 -> "Service Unavailable"
      case ErrorCode.Timeout        => 504 -> "Gateway Timeout"
      case ErrorCode.NotHome        => 421 -> "Misdirected Request"
      case ErrorCode.WorkflowFailed => 500 -> "Workflow Failed"
      case _                        => 500 -> "Internal Server Error"
    Problem(status, title, error.message)

  private def code(status: Int): ErrorCode = status match
    case 400                      => ErrorCode.BadRequest
    case 401                      => ErrorCode.Unauthorized
    case 403                      => ErrorCode.Forbidden
    case 404                      => ErrorCode.NotFound
    case 409                      => ErrorCode.Conflict
    case 503                      => ErrorCode.Unavailable
    case 504                      => ErrorCode.Timeout
    case s if s >= 400 && s < 500 => ErrorCode.BadRequest
    case _                        => ErrorCode.Internal

  private[ankka] def finite(d: Duration): FiniteDuration =
    FiniteDuration(d.toNanos, java.util.concurrent.TimeUnit.NANOSECONDS)

  private def fn[S, R](f: Function[S, R]): S => R = s => f.apply(s)

  /** One class holds every Java effect; which interfaces it presents is the only difference. */
  /** A Scala effect, held behind one of the Java effect interfaces. */
  private[bridge] trait Holding:
    def scala: Any
    override def toString: String = s"Effect($scala)"

  private[bridge] final class Held[R](val scala: Any) extends ReadOnlyEffect[R] with Holding

  private final class HeldView[R](val scala: se.ViewEffect[Any]) extends ViewEffect[R] with Holding

  private final class HeldKeyed[R](val scala: se.KeyedViewEffect[Any])
      extends KeyedViewEffect[R]
      with Holding:
    def and(other: KeyedViewEffect[R]): KeyedViewEffect[R] = other match
      case o: HeldKeyed[R] @unchecked => HeldKeyed(scala ++ o.scala)
      case _ => throw IllegalArgumentException(s"$other is not an effect built by effects()")

  def timedAction(): TimedActionEffects = TimedAction

  private final class HeldAction(val scala: se.TimedActionEffect)
      extends TimedActionEffect
      with Holding

  private object TimedAction extends TimedActionEffects:
    private val effects                            = new se.TimedActionEffects()
    def done(): TimedActionEffect                  = HeldAction(effects.done())
    def error(message: String): TimedActionEffect  = HeldAction(effects.error(message))
    def error(problem: Problem): TimedActionEffect =
      val e = toCommandError(problem)
      HeldAction(effects.error(e.message, e.code))

  def consumer(): ConsumerEffects[?] = Consumer

  def graph(): GraphEffects = Graph

  def graphElements(): GraphElements = Elements

  private[bridge] final class HeldConsumer[R](val scala: se.ConsumerEffect[Any])
      extends ConsumerEffect[R]
      with Holding

  private[bridge] final class HeldGraph(val scala: sdk.graph.GraphEffect)
      extends GraphEffect
      with Holding

  private object Consumer extends ConsumerEffects[Any]:
    private val effects = new se.ConsumerEffects[Any]()

    private def outgoing(o: Outgoing[Any]): se.Outgoing[Any] =
      val withKey =
        o.key.toScala.fold(effects.message(o.payload))(effects.message(o.payload).withKey)
      withKey.withMetadata(
        o.metadata.asScala.foldLeft(Metadata.empty)((m, kv) => m.add(kv._1, kv._2))
      )

    def publish(message: Any): ConsumerEffect[Any] = HeldConsumer(effects.produce(message))
    def publish(message: Any, key: String): ConsumerEffect[Any] =
      HeldConsumer(effects.produceAll(Seq(effects.message(message).withKey(key))))
    def publishAll(messages: java.util.List[Any]): ConsumerEffect[Any] =
      HeldConsumer(effects.produceAll(messages.asScala.toVector.map(effects.message)))
    def publishMessages(messages: java.util.List[Outgoing[Any]]): ConsumerEffect[Any] =
      HeldConsumer(effects.produceAll(messages.asScala.toVector.map(outgoing)))
    def done(): ConsumerEffect[Any]   = HeldConsumer(effects.done())
    def ignore(): ConsumerEffect[Any] = HeldConsumer(effects.ignore())

  private[bridge] final case class HeldElement(scala: sdk.graph.GraphElement) extends GraphElement:
    def at(version: Long): GraphElement = HeldElement(scala.at(version))
    def kind(): String                  = scala.kind
    def id(): String                    = scala.id
    def key(): String                   = scala.key
    override def toString: String       = scala.toString

  private object Graph extends GraphEffects:
    private val effects                                          = new sdk.graph.GraphEffects()
    private def scalaOf(e: GraphElement): sdk.graph.GraphElement = e match
      case HeldElement(scala) => scala
      case other => throw IllegalArgumentException(s"$other is not an element built by graph()")
    def publish(elements: GraphElement*): GraphEffect =
      HeldGraph(effects.publish(elements.map(scalaOf)*))
    def publish(elements: java.util.List[GraphElement]): GraphEffect =
      HeldGraph(effects.publish(elements.asScala.toVector.map(scalaOf)*))
    def done(): GraphEffect   = HeldGraph(effects.done())
    def ignore(): GraphEffect = HeldGraph(effects.ignore())

  private object Elements extends GraphElements:
    private val graph = new sdk.graph.GraphElements()

    private def value(name: String, v: Any): PropertyValue = v match
      case s: String                => s
      case b: java.lang.Boolean     => b.booleanValue
      case i: java.lang.Integer     => i.intValue
      case l: java.lang.Long        => l.longValue
      case d: java.lang.Double      => d.doubleValue
      case items: java.util.List[?] =>
        items.asScala.toVector.map(value(name, _)).map {
          case scalar: (String | Boolean | Int | Long | Double) => scalar
          case _ => throw IllegalArgumentException(s"the property '$name' nests a list in a list")
        }
      case other =>
        throw IllegalArgumentException(
          s"the property '$name' is a ${Option(other).fold("null")(_.getClass.getName)}; a " +
            "property is a String, Boolean, Integer, Long, Double or a List of one of those"
        )

    private def properties(ps: java.util.Map[String, Object]): Map[String, PropertyValue] =
      ps.asScala.toMap.map((k, v) => k -> value(k, v))

    private def built(element: sdk.graph.GraphElement): GraphElement = HeldElement(element)
    def node(
        id: String,
        labels: java.util.List[String],
        ps: java.util.Map[String, Object]
    ): GraphElement = built(graph.node(id, labels.asScala.toVector, properties(ps)))
    def edge(
        id: String,
        `type`: String,
        from: String,
        to: String,
        ps: java.util.Map[String, Object]
    ): GraphElement = built(graph.edge(id, `type`, from, to, properties(ps)))
    def tombstoneNode(id: String): GraphElement = built(graph.tombstoneNode(id))
    def tombstoneEdge(id: String, `type`: String, from: String, to: String): GraphElement =
      built(graph.tombstoneEdge(id, `type`, from, to))

  def view(): ViewEffects[?] = View

  def keyedView(): KeyedViewEffects[?] = Keyed

  private object View extends ViewEffects[Any]:
    private val effects                      = new se.ViewEffects[Any]()
    def updateRow(row: Any): ViewEffect[Any] = HeldView(effects.updateRow(row))
    def deleteRow(): ViewEffect[Any]         = HeldView(effects.deleteRow())
    def ignore(): ViewEffect[Any]            = HeldView(effects.ignore())

  private object Keyed extends KeyedViewEffects[Any]:
    private val effects                                        = new se.KeyedViewEffects[Any]()
    def updateRow(key: String, row: Any): KeyedViewEffect[Any] =
      HeldKeyed(effects.updateRow(key, row))
    def deleteRow(key: String): KeyedViewEffect[Any] = HeldKeyed(effects.deleteRow(key))
    def updateRows(rows: java.util.Map[String, Any]): KeyedViewEffect[Any] =
      HeldKeyed(effects.updateRows(rows.asScala.toVector))
    def deleteRows(keys: java.util.Collection[String]): KeyedViewEffect[Any] =
      HeldKeyed(effects.deleteRows(keys.asScala.toVector))
    def ignore(): KeyedViewEffect[Any] = HeldKeyed(effects.ignore())

  private type AnyES = se.EventSourcedEffects[Any, Any]

  private object EventSourced extends EventSourcedEffects[Any, Any]:
    private val effects: AnyES = new se.EventSourcedEffects[Any, Any]()

    def persist(event: Any): PersistBuilder[Any, Any]              = Persist(effects.persist(event))
    def persist(first: Any, second: Any): PersistBuilder[Any, Any] =
      Persist(effects.persist(first, second))
    def persist(first: Any, second: Any, third: Any): PersistBuilder[Any, Any] =
      Persist(effects.persist(first, second, third))
    def persistAll(events: java.util.List[? <: Any]): PersistBuilder[Any, Any] =
      Persist(effects.persistAll(events.asScala.toVector))
    def deleteEntity(): PersistBuilder[Any, Any]                   = Persist(effects.deleteEntity())
    def handOverTo(installation: String): PersistBuilder[Any, Any] =
      Persist(effects.handOverTo(installation))
    def replicateTo(installation: String): PersistBuilder[Any, Any] =
      Persist(effects.replicateTo(installation))
    def stopReplicatingTo(installation: String): PersistBuilder[Any, Any] =
      Persist(effects.stopReplicatingTo(installation))
    def noReply[R](): ReadOnlyEffect[R]               = Held(effects.noReply)
    def reply[R](reply: R): ReadOnlyEffect[R]         = Held(effects.reply(reply))
    def error[R](message: String): ReadOnlyEffect[R]  = Held(effects.error(message))
    def error[R](problem: Problem): ReadOnlyEffect[R] = Held(effects.error(toCommandError(problem)))

  private final case class Persist(builder: se.PersistBuilder[Any, Any])
      extends PersistBuilder[Any, Any]:
    def thenReply[R](reply: Function[Any, R]): Effect[R] = Held(builder.thenReply(fn(reply)))
    def thenReply[R](reply: R): Effect[R]                = Held(builder.thenReply(_ => reply))
    def thenReplyState(): Effect[Any]                    = Held(builder.thenReplyState)
    def thenDone(): Effect[Done]                         = Held(builder.thenReply(_ => Done.DONE))
    def thenDelete(): PersistBuilder[Any, Any]           = Persist(builder.deleteEntity())
    def thenNoReply[R](): Effect[R]                      = Held(builder.thenNoReply)
    def handOverTo(installation: String): PersistBuilder[Any, Any] =
      Persist(builder.handOverTo(installation))
    def replicateTo(installation: String): PersistBuilder[Any, Any] =
      Persist(builder.replicateTo(installation))
    def stopReplicatingTo(installation: String): PersistBuilder[Any, Any] =
      Persist(builder.stopReplicatingTo(installation))
    def expireAfter(after: Duration): PersistBuilder[Any, Any] =
      Persist(builder.expireAfter(finite(after)))

  private object KeyValue extends KeyValueEffects[Any]:
    private val effects = new se.KeyValueEffects[Any]()

    def updateState(state: Any): UpdateBuilder[Any]   = Update(effects.updateState(state))
    def deleteEntity(): UpdateBuilder[Any]            = Update(effects.deleteEntity())
    def noReply[R](): ReadOnlyEffect[R]               = Held(effects.noReply)
    def reply[R](reply: R): ReadOnlyEffect[R]         = Held(effects.reply(reply))
    def error[R](message: String): ReadOnlyEffect[R]  = Held(effects.error(message))
    def error[R](problem: Problem): ReadOnlyEffect[R] = Held(effects.error(toCommandError(problem)))

  private final case class Update(builder: se.UpdateBuilder[Any]) extends UpdateBuilder[Any]:
    def thenReply[R](reply: Function[Any, R]): Effect[R] = Held(builder.thenReply(fn(reply)))
    def thenReply[R](reply: R): Effect[R]                = Held(builder.thenReply(_ => reply))
    def thenReplyState(): Effect[Any]                    = Held(builder.thenReplyState)
    def thenDone(): Effect[Done]                         = Held(builder.thenReply(_ => Done.DONE))
    def thenNoReply[R](): Effect[R]                      = Held(builder.thenNoReply)
    def expireAfter(after: Duration): UpdateBuilder[Any] = Update(
      builder.expireAfter(finite(after))
    )
