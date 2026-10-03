package com.thinkmorestupidless.ankka.testkit.topology

import com.thinkmorestupidless.ankka.agent.{Agent, AgentContext}
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.http.{Acl, HttpEndpoint, PathTemplate}
import com.thinkmorestupidless.ankka.sdk.*
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source

import scala.concurrent.duration.FiniteDuration

// The components a topology scenario is built from. A step names a component ("an event sourced
// entity "cart""), so each kind here is made with the id it is given: what a scenario says about
// the service is all there is to the service.

/** The one event a kit entity records. */
final case class Noted(note: String)

/** The one row a kit view keeps. */
final case class Seen(changes: Int)

/** How a kit entity's one command behaves, as a scenario says it does. */
enum Manner:
  /** Records what it is given, and refuses a quantity of nothing. */
  case Answers

  /** Throws: a handler that failed, which is not one that refused. */
  case Fails

  /** Answers, after longer than its caller waits. */
  case Slow(after: FiniteDuration)

final class KitEventSourcedEntity(manner: Manner) extends EventSourcedEntity[Int, Noted]:
  def emptyState: Int               = 0
  def applyEvent(event: Noted): Int = currentState + 1

  def addItem(quantity: Int): Effect[Int] =
    manner match
      case Manner.Fails       => throw IllegalStateException("the scenario's handler fails")
      case Manner.Slow(after) => Thread.sleep(after.toMillis)
      case Manner.Answers     => ()
    if quantity <= 0 then effects.error("a quantity is more than nothing", ErrorCode.BadRequest)
    else effects.persist(Noted(quantity.toString)).thenReply(identity)

  def getCart: ReadOnlyEffect[Int] = effects.reply(currentState)

final class EventSourcedKit(val id: String, manner: Manner = Manner.Answers)
    extends EventSourcedEntity.Companion[KitEventSourcedEntity, Int, Noted](
      componentId = ComponentId(id),
      stateSerializer = Serializers.int,
      eventSerializer = Kit.noted
    ):
  def create(context: EventSourcedEntityContext) = new KitEventSourcedEntity(manner)
  val addItem                                    = command("add-item")(_.addItem)
  val getCart                                    = query("get-cart")(_.getCart)

final class KitKeyValueEntity extends KeyValueEntity[Int]:
  def emptyState: Int          = 0
  def bump: Effect[Int]        = effects.updateState(currentState + 1).thenReplyState
  def get: ReadOnlyEffect[Int] = effects.reply(currentState)

final class KeyValueKit(id: String)
    extends KeyValueEntity.Companion[KitKeyValueEntity, Int](
      componentId = ComponentId(id),
      stateSerializer = Serializers.int
    ):
  def create(context: KeyValueEntityContext) = new KitKeyValueEntity
  val bump                                   = command("bump")(_.bump)
  val get                                    = query("get")(_.get)

/** A view over whatever it is told to read: it counts the changes it was given. */
final class KitView[Src] extends View[Src, Seen]:
  def onChange(change: Src): Effect =
    effects.updateRow(Seen(rowState.fold(1)(_.changes + 1)))

final class ViewKit[Src](id: String, source: ChangeSource[Src])
    extends View.Companion[KitView[Src], Src, Seen](
      componentId = ComponentId(id),
      source = source,
      rowSerializer = Kit.seen
    ):
  def create(context: ViewComponentContext) = new KitView[Src]

/**
 * A consumer over whatever it is told to read, publishing a line for each message when asked to.
 */
final class KitConsumer[Src](publishes: Boolean) extends Consumer[Src, String]:
  def onMessage(message: Src): Effect =
    if publishes then effects.produce(message.toString) else effects.ignore()

final class ConsumerKit[Src](id: String, source: ChangeSource[Src], publishesTo: Option[String])
    extends Consumer.Companion[KitConsumer[Src], Src, String](
      componentId = ComponentId(id),
      source = source
    ):
  def create(context: ConsumerContext) = new KitConsumer[Src](publishesTo.isDefined)
  override val outputSerializer: Option[Serializer[String]] =
    publishesTo.map(_ => Serializers.string)
  override val produceTo: Option[String] = publishesTo

/**
 * A workflow with one step, which calls each of the entities it is given and ends.
 *
 * Started with the id of the instance of each entity to call, so two scenarios never share one.
 */
final class KitWorkflow(context: WorkflowContext, kit: WorkflowKit) extends Workflow[Int]:
  def emptyState: Int = 0

  def start(instance: String): Effect[Done] =
    effects.updateState(1).transitionTo(kit.only.withInput(instance)).thenReply(Done)

  def run(instance: String): StepEffect =
    kit.calls.foreach { entity =>
      context.componentClient
        .forEventSourcedEntity(EntityId(instance))
        .call(entity.addItem)
        .invoke(1)
    }
    stepEffects.updateState(2).thenEnd

  def state: ReadOnlyEffect[Int] = effects.reply(currentState)

final class WorkflowKit(id: String, stepName: String, val calls: Vector[EventSourcedKit])
    extends Workflow.Companion[KitWorkflow, Int](
      componentId = ComponentId(id),
      stateSerializer = Serializers.int
    ):
  def create(context: WorkflowContext) = new KitWorkflow(context, this)
  val only                             = step(stepName)(_.run)
  val start                            = command("start")(_.start)
  val state                            = query("state")(_.state)

/** A timed action whose one handler calls the entity it is given. */
final class KitTimedAction(context: TimedActionContext, kit: TimedActionKit) extends TimedAction:
  def remind(instance: String): Effect =
    val _ = context.componentClient
      .forEventSourcedEntity(EntityId(instance))
      .call(kit.calls.addItem)
      .invoke(1)
    effects.done()

final class TimedActionKit(id: String, val calls: EventSourcedKit)
    extends TimedAction.Companion[KitTimedAction](ComponentId(id)):
  def create(context: TimedActionContext) = new KitTimedAction(context, this)
  val remind                              = handler("remind")(_.remind)

/** An agent whose one handler answers as a stream. */
final class KitAgent extends Agent:
  def ask(question: String): StreamEffect =
    effects.systemMessage("Answer in a few words.").userMessage(question).thenStream()

final class AgentKit(id: String) extends Agent.Companion[KitAgent](ComponentId(id)):
  def create(context: AgentContext) = new KitAgent
  val ask                           = stream("ask")(_.ask)

/**
 * An endpoint serving the routes it is given, each as a scenario writes it (`GET /status/health`).
 *
 * Its prefix is the route's first segment, as a developer's endpoint for `/carts` would have, so a
 * scenario's routes and the endpoint's are the same words.
 */
final class KitEndpoint(prefix: String, routes: Vector[KitEndpoint.Route])
    extends HttpEndpoint(prefix):
  val acl: Acl = Acl.AllowAll

  routes.foreach { route =>
    (route.method, PathTemplate.parse(route.template).arity, route.serve) match
      case ("GET", 0, KitEndpoint.Serve.Answer(f)) => get(route.template)(() => f(Vector.empty))
      case ("GET", 1, KitEndpoint.Serve.Answer(f)) =>
        get(route.template)((a: String) => f(Vector(a)))
      case ("POST", 0, KitEndpoint.Serve.Answer(f)) => post(route.template)(() => f(Vector.empty))
      case ("POST", 1, KitEndpoint.Serve.Answer(f)) =>
        post(route.template)((a: String) => f(Vector(a)))
      // A stream is handed back, not run: the server runs it, on a thread of its own.
      case ("GET", 1, KitEndpoint.Serve.Stream(f)) =>
        sse(route.template)((a: String) => f(Vector(a)))
      case (method, arity, _) =>
        throw IllegalArgumentException(
          s"the kit's endpoint serves GET and POST with at most one path parameter, and a " +
            s"stream as GET with one, not $method with $arity"
        )
  }

object KitEndpoint:

  /** What serving a route does with the path's parameters: answer, or hand back a stream. */
  enum Serve:
    case Answer(run: Vector[String] => String)
    case Stream(run: Vector[String] => Source[String, NotUsed])

  /** One route, and what serving it does. */
  final case class Route(method: String, template: String, serve: Serve)

  /** `"POST /carts/{cartId}/items"` as the prefix an endpoint is registered with and the rest. */
  def split(route: String): (String, String, String) =
    route.split(" ", 2) match
      case Array(method, path) =>
        path.split("/").filter(_.nonEmpty).toList match
          case first :: rest if rest.nonEmpty =>
            (method, s"/$first", rest.mkString("/", "/", ""))
          case _ =>
            throw IllegalArgumentException(
              s"'$route': a kit route has a prefix and at least one more segment, as in " +
                "\"GET /status/health\""
            )
      case _ => throw IllegalArgumentException(s"'$route' is not a method and a path")

object Kit:
  val noted: Serializer[Noted] = Codecs.serializer[Noted]("kit-noted")
  val seen: Serializer[Seen]   = Codecs.serializer[Seen]("kit-seen")

  def eventsOf(entity: String): ChangeSource[Noted] =
    ChangeSource.EventSourced(ComponentId(entity), noted)

  def stateOf(entity: String): ChangeSource[Int] =
    ChangeSource.KeyValue(ComponentId(entity), Serializers.int)

  def topic(name: String): ChangeSource[String] =
    ChangeSource.Topic(
      name,
      Serializers.string,
      Some(com.thinkmorestupidless.ankka.sdk.StartFrom.Earliest)
    )
