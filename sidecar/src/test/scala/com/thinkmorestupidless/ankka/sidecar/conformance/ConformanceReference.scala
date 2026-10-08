package com.thinkmorestupidless.ankka.sidecar.conformance

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.sdk.*
import com.thinkmorestupidless.ankka.sdk.graph.GraphConsumer
import org.apache.pekko.stream.scaladsl.Source

import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*

/**
 * The reference service, in Scala: every component and every route the conformance contract names,
 * written as plainly as possible — it is documentation of the behaviours an SDK must reproduce. The
 * Python one is `sdks/python/examples/shopping_cart/conformance.py`; the two must agree on wire
 * names, routes and JSON, or the suite says where they differ.
 */
object ConformanceReference:

  // ── The cart, field for field the sample's (that is what makes the journal shared) ──

  final case class LineItem(productId: String, name: String, quantity: Int)

  final case class ShoppingCart(cartId: String, items: List[LineItem], checkedOut: Boolean):
    def addItem(item: LineItem): ShoppingCart =
      val merged = items.find(_.productId == item.productId) match
        case Some(existing) => item.copy(quantity = existing.quantity + item.quantity)
        case None           => item
      copy(items = (merged :: items.filterNot(_.productId == item.productId)).sortBy(_.productId))
    def removeItem(productId: String): ShoppingCart =
      copy(items = items.filterNot(_.productId == productId))
    def totalQuantity: Int = items.map(_.quantity).sum

  enum ShoppingCartEvent:
    case ItemAdded(item: LineItem)
    case ItemRemoved(productId: String)
    case CheckedOut
    case Discarded

  import ShoppingCartEvent.*

  final class ShoppingCartEntity(context: EventSourcedEntityContext)
      extends EventSourcedEntity[ShoppingCart, ShoppingCartEvent]:
    def emptyState: ShoppingCart = ShoppingCart(context.entityId, Nil, checkedOut = false)
    def applyEvent(event: ShoppingCartEvent): ShoppingCart = event match
      case ItemAdded(item)        => currentState.addItem(item)
      case ItemRemoved(productId) => currentState.removeItem(productId)
      case CheckedOut             => currentState.copy(checkedOut = true)
      case Discarded              => currentState
    def addItem(item: LineItem): Effect[Done] =
      if currentState.checkedOut then
        effects.error("cart is already checked out", ErrorCode.Conflict)
      else if item.quantity <= 0 then effects.error(s"quantity must be greater than zero")
      else effects.persist(ItemAdded(item)).thenReply(_ => Done)
    def removeItem(productId: String): Effect[Done] =
      if currentState.checkedOut then
        effects.error("cart is already checked out", ErrorCode.Conflict)
      else if !currentState.items.exists(_.productId == productId) then
        effects.error(s"cart does not contain '$productId'", ErrorCode.NotFound)
      else effects.persist(ItemRemoved(productId)).thenReply(_ => Done)
    def checkout: Effect[ShoppingCart] =
      if currentState.checkedOut then
        effects.error("cart is already checked out", ErrorCode.Conflict)
      else if currentState.items.isEmpty then effects.error("cannot check out an empty cart")
      else effects.persist(CheckedOut).thenReplyState
    def discard: Effect[Done] =
      if currentState.checkedOut then
        effects.error("cart is already checked out", ErrorCode.Conflict)
      else effects.persist(Discarded).deleteEntity().thenReply(_ => Done)
    def getCart: ReadOnlyEffect[ShoppingCart] = effects.reply(currentState)
    def totalQuantity: ReadOnlyEffect[Int]    = effects.reply(currentState.totalQuantity)

  object ShoppingCartEntity
      extends EventSourcedEntity.Companion[ShoppingCartEntity, ShoppingCart, ShoppingCartEvent](
        componentId = ComponentId("shopping-cart"),
        stateSerializer = Codecs.serializer[ShoppingCart]("shopping-cart"),
        eventSerializer = Codecs.serializer[ShoppingCartEvent]("shopping-cart-event")
      ):
    given Serializer[LineItem]                     = Codecs.serializer[LineItem]("line-item")
    override def snapshotEvery: Option[Int]        = Some(3)
    def create(context: EventSourcedEntityContext) = new ShoppingCartEntity(context)
    val addItem                                    = command("add-item")(_.addItem)
    val removeItem                                 = command("remove-item")(_.removeItem)
    val checkout                                   = command("checkout")(_.checkout)
    val discard                                    = command("discard")(_.discard)
    val getCart                                    = query("get-cart")(_.getCart)
    val totalQuantity                              = query("total-quantity")(_.totalQuantity)

  // ── conformance: an entity whose handlers are the protocol's edge cases ──

  final case class Recorded(input: String)

  final class Conformance(context: EventSourcedEntityContext)
      extends EventSourcedEntity[Vector[String], Recorded]:
    def emptyState: Vector[String]                  = Vector.empty
    def applyEvent(event: Recorded): Vector[String] = currentState :+ event.input
    def record(input: String): Effect[String] =
      effects.persist(Recorded(input)).thenReply(_ => "done")
    def recordMany(n: Int): Effect[String] =
      effects.persistAll(Vector.tabulate(n)(i => Recorded(s"many-$i"))).thenReply(_ => "done")
    def refuse: Effect[String]  = effects.error("refused on purpose", ErrorCode.Conflict)
    def noReply: Effect[String] = effects.persist(Recorded("silent")).thenNoReply
    def delete: Effect[String]  = effects.deleteEntity().thenReply(_ => "done")
    def expire(millis: Long): Effect[String] =
      effects.persist(Recorded("expiring")).expireAfter(millis.millis).thenReply(_ => "done")
    def count: ReadOnlyEffect[Int] = effects.reply(currentState.size)
    def misbehave: Effect[String]  = throw RuntimeException("boom")
    val _                          = context

  object Conformance
      extends EventSourcedEntity.Companion[Conformance, Vector[String], Recorded](
        componentId = ComponentId("conformance"),
        stateSerializer = Codecs.serializer[Vector[String]]("conformance-state"),
        eventSerializer = Codecs.serializer[Recorded]("conformance-event")
      ):
    override def snapshotEvery: Option[Int]        = Some(3)
    def create(context: EventSourcedEntityContext) = new Conformance(context)
    val record                                     = command("record")(_.record)
    val recordMany                                 = command("record-many")(_.recordMany)
    val refuse                                     = command("refuse")(_.refuse)
    val noReply                                    = command("no-reply")(_.noReply)
    val delete                                     = command("delete")(_.delete)
    val expire                                     = command("expire")(_.expire)
    val count                                      = query("count")(_.count)
    val misbehave                                  = command("misbehave")(_.misbehave)

  // ── profile: a key value entity ──

  final case class ProfileState(name: String)

  final class Profile(context: KeyValueEntityContext) extends KeyValueEntity[ProfileState]:
    def emptyState: ProfileState = ProfileState("")
    def set(name: String): Effect[String] =
      if name.isEmpty then effects.error("a name is needed")
      else effects.updateState(ProfileState(name)).thenReply(_ => "done")
    def get: ReadOnlyEffect[String] =
      effects.reply(if currentState.name.isEmpty then "none" else currentState.name)
    def delete: Effect[String] = effects.deleteEntity().thenReply(_ => "done")
    val _                      = context

  object Profile
      extends KeyValueEntity.Companion[Profile, ProfileState](
        componentId = ComponentId("profile"),
        stateSerializer = Codecs.serializer[ProfileState]("profile")
      ):
    def create(context: KeyValueEntityContext) = new Profile(context)
    val set                                    = command("set")(_.set)
    val get                                    = query("get")(_.get)
    val delete                                 = command("delete")(_.delete)

  // ── checkout: a workflow with a failing step and a pause ──

  final case class CheckoutState(id: String, mode: String, status: String)

  final class Checkout(context: WorkflowContext) extends Workflow[CheckoutState]:
    private val client            = context.componentClient
    def emptyState: CheckoutState = CheckoutState(context.workflowId, "", "new")
    override def settings: WorkflowSettings =
      WorkflowSettings.builder
        .defaultStepTimeout(10.seconds)
        .stepRecovery(
          Checkout.charge,
          RecoverStrategy.maxRetries(1).failoverTo(Checkout.compensate)
        )
        .build

    /** `mode`: `ok`, `fail` (charge is declined) or `pause` (a pause before charging). */
    def start(mode: String): Effect[String] =
      if currentState.status != "new" then effects.error("already started", ErrorCode.Conflict)
      else
        effects
          .updateState(currentState.copy(mode = mode, status = "reserving"))
          .transitionTo(Checkout.reserve.ref)
          .thenReply("started")
    def reserveStep: StepEffect =
      // A client call from a step: what the cart holds.
      val _ = client
        .forEventSourcedEntity(EntityId(currentState.id))
        .call(ShoppingCartEntity.totalQuantity)
        .invoke()
      val next =
        if currentState.mode == "pause" then Checkout.pauseStep.ref else Checkout.charge.ref
      stepEffects.updateState(currentState.copy(status = "reserved")).thenTransitionTo(next)
    def waitStep: StepEffect =
      stepEffects
        .updateState(currentState.copy(status = "waiting"))
        .thenPause(1500.millis, Checkout.charge.ref)
    def chargeStep: StepEffect =
      if currentState.mode == "fail" then throw RuntimeException("payment declined")
      else stepEffects.updateState(currentState.copy(status = "charged")).thenEnd
    def compensateStep: StepEffect =
      stepEffects.updateState(currentState.copy(status = "compensated")).thenEnd
    def status: ReadOnlyEffect[String] = effects.reply(currentState.status)

  object Checkout
      extends Workflow.Companion[Checkout, CheckoutState](
        componentId = ComponentId("checkout"),
        stateSerializer = Codecs.serializer[CheckoutState]("checkout")
      ):
    def create(context: WorkflowContext) = new Checkout(context)
    val reserve                          = step("reserve")(_.reserveStep)
    val pauseStep                        = step("wait")(_.waitStep)
    val charge                           = step("charge")(_.chargeStep)
    val compensate                       = step("compensate")(_.compensateStep)
    val start                            = command("start")(_.start)
    val status                           = query("status")(_.status)

  // ── cart-rows: a view; checkout-recorder: a consumer that acts through the client ──

  final case class CartRow(cartId: String, quantities: Map[String, Int], checkedOut: Boolean)

  final class CartRowsView extends View[ShoppingCartEvent, CartRow]:
    def onChange(event: ShoppingCartEvent): Effect =
      val current =
        rowState.getOrElse(CartRow(updateContext.subject, Map.empty, checkedOut = false))
      event match
        case ItemAdded(item) =>
          val existing = current.quantities.getOrElse(item.productId, 0)
          effects.updateRow(
            current.copy(quantities =
              current.quantities.updated(item.productId, existing + item.quantity)
            )
          )
        case ItemRemoved(productId) =>
          effects.updateRow(current.copy(quantities = current.quantities - productId))
        case CheckedOut => effects.updateRow(current.copy(checkedOut = true))
        // The deletion that follows removes the row, by the view's default deletion handler.
        case Discarded => effects.ignore()

  object CartRows
      extends View.Companion[CartRowsView, ShoppingCartEvent, CartRow](
        componentId = ComponentId("cart-rows"),
        source = ChangeSource.eventsOf(ShoppingCartEntity),
        rowSerializer = Codecs.serializer[CartRow]("cart-row")
      ):
    def create(ctx: ViewComponentContext) = new CartRowsView

  // ── tree-node, tree-rows: a tree, walked by a declared recursive query ──

  enum TreeEvent:
    case Placed(under: Option[String])

  final class TreeNode extends EventSourcedEntity[Option[String], TreeEvent]:
    def emptyState: Option[String] = None
    def applyEvent(event: TreeEvent): Option[String] = event match
      case TreeEvent.Placed(under) => under
    def place(under: String): Effect[String] =
      effects.persist(TreeEvent.Placed(Option(under).filter(_.nonEmpty))).thenReply(_ => "placed")

  object TreeNode
      extends EventSourcedEntity.Companion[TreeNode, Option[String], TreeEvent](
        componentId = ComponentId("tree-node"),
        stateSerializer = Codecs.serializer[Option[String]]("tree-node"),
        eventSerializer = Codecs.serializer[TreeEvent]("tree-event")
      ):
    def create(context: EventSourcedEntityContext) = new TreeNode
    val place                                      = command("place")(_.place)

  final case class TreeRow(key: String, under: Option[String])

  final class TreeRowsView extends View[TreeEvent, TreeRow]:
    def onChange(event: TreeEvent): Effect = event match
      case TreeEvent.Placed(under) => effects.updateRow(TreeRow(updateContext.subject, under))

  object TreeRows
      extends View.Companion[TreeRowsView, TreeEvent, TreeRow](
        componentId = ComponentId("tree-rows"),
        source = ChangeSource.eventsOf(TreeNode),
        rowSerializer = Codecs.serializer[TreeRow]("tree-row")
      ):
    /**
     * Every row under the row `row`, to any depth, by key: the same statement in every language.
     */
    val under = query("under")(s"""WITH RECURSIVE below AS (
      |  SELECT row_key, payload FROM $table WHERE payload::jsonb->>'under' = :row
      |  UNION
      |  SELECT n.row_key, n.payload FROM $table n JOIN below b ON n.payload::jsonb->>'under' = b.row_key
      |)
      |SELECT payload FROM below ORDER BY row_key""".stripMargin)
    def create(ctx: ViewComponentContext) = new TreeRowsView

  // ── joined-left, joined-right, joined-rows: a keyed view of two sources ──

  final case class Noted(text: String)

  final class Joining extends EventSourcedEntity[Int, Noted]:
    def emptyState: Int               = 0
    def applyEvent(event: Noted): Int = currentState + 1
    def record(text: String): Effect[String] =
      effects.persist(Noted(text)).thenReply(_ => "recorded")

  abstract class JoiningCompanion(id: String)
      extends EventSourcedEntity.Companion[Joining, Int, Noted](
        componentId = ComponentId(id),
        stateSerializer = Codecs.serializer[Int]("joining"),
        eventSerializer = Codecs.serializer[Noted]("noted")
      ):
    def create(context: EventSourcedEntityContext) = new Joining
    val record                                     = command("record")(_.record)

  object JoinedLeft  extends JoiningCompanion("joined-left")
  object JoinedRight extends JoiningCompanion("joined-right")

  // docs:start keyed-view
  /** A row the left writes under the key it names, holding a right entity's id. */
  final case class JoinedRow(key: String, holding: String, notes: Vector[String])

  final class JoinedRowsView extends KeyedView[JoinedRow]:

    /** The left names a row `key|holding`, and writes it from what it held, noting itself. */
    def onLeft(event: Noted, change: Change): Effect =
      val Array(key, holding) = event.text.split('|')
      val held                = change.rows.get(key).fold(Vector.empty[String])(_.notes)
      effects.updateRow(key, JoinedRow(key, holding, held :+ "left"))

    /** The right finds every row holding it by asking the view's own query, and notes itself. */
    def onRight(@scala.annotation.unused event: Noted, change: Change): Effect =
      val theirs = change.rows.ask(JoinedRows.ofRight, "holding" -> change.subject)
      effects.updateRows(theirs.map(row => row.key -> row.copy(notes = row.notes :+ "right")))

  object JoinedRows
      extends KeyedView.Companion[JoinedRowsView, JoinedRow](
        ComponentId("joined-rows"),
        Codecs.serializer[JoinedRow]("joined-row")
      ):
    val lefts  = source(ChangeSource.eventsOf(JoinedLeft))(_.onLeft)
    val rights = source(ChangeSource.eventsOf(JoinedRight))(_.onRight)

    /** The rows holding one right entity, by key: the same statement in every language. */
    val ofRight = query("of-right")(
      s"SELECT payload FROM $table WHERE payload::jsonb->>'holding' = :holding ORDER BY row_key"
    )
    def create(ctx: ViewComponentContext) = new JoinedRowsView
  // docs:end keyed-view

  final class CheckoutRecorder(context: ConsumerContext)
      extends Consumer[ShoppingCartEvent, Nothing]:
    def onMessage(event: ShoppingCartEvent): Effect = event match
      case CheckedOut =>
        val _ = context.componentClient
          .forEventSourcedEntity(EntityId(messageContext.subject))
          .call(Conformance.record)
          .invoke("checkout")
        effects.done()
      case _ => effects.ignore()

  object CheckoutRecorder
      extends Consumer.Companion[CheckoutRecorder, ShoppingCartEvent, Nothing](
        componentId = ComponentId("checkout-recorder"),
        source = ChangeSource.eventsOf(ShoppingCartEntity)
      ):
    def create(ctx: ConsumerContext) = new CheckoutRecorder(ctx)

  // docs:start fanout
  /** What `checkout-fanout` publishes: the n-th message of a change. */
  final case class Fanned(n: Int)

  /**
   * Several messages for one change: three for a checkout — the second under a key of its own, the
   * third with a header — none for an item added, and a single one, the old way, for an item
   * removed.
   */
  final class CheckoutFanout extends Consumer[ShoppingCartEvent, Fanned]:
    def onMessage(event: ShoppingCartEvent): Effect = event match
      case _: ItemAdded   => effects.produceAll(Nil)
      case _: ItemRemoved => effects.produce(Fanned(0))
      case CheckedOut =>
        effects.produceAll(
          Seq(
            effects.message(Fanned(1)),
            effects.message(Fanned(2)).withKey(s"second:${messageContext.subject}"),
            effects.message(Fanned(3)).withMetadata(Metadata.empty.set("x-n", "3"))
          )
        )
      case Discarded => effects.ignore()

  object CheckoutFanout
      extends Consumer.Companion[CheckoutFanout, ShoppingCartEvent, Fanned](
        componentId = ComponentId("checkout-fanout"),
        source = ChangeSource.eventsOf(ShoppingCartEntity)
      ):
    def create(ctx: ConsumerContext) = new CheckoutFanout

    override val outputSerializer: Option[Serializer[Fanned]] =
      Some(Codecs.serializer[Fanned]("fanned"))

    override val produceTo: Option[String] = Some("conformance-fanout")
  // docs:end fanout

  // ── topic-rows and topic-relay: a view and a consumer over a topic ──

  /**
   * The topic the conformance target fills before the service starts, so that where a source starts
   * can be seen: what it holds then is `fanned` messages about `t-1`, `t-2` and `t-3`.
   */
  val Topic: String = "conformance-topic"

  /** Where `topic-relay` republishes each message it reads. */
  val Relayed: String = "conformance-topic-relayed"

  // docs:start topic-sources
  /** The latest message about each subject. Declares no start, so it reads from the earliest. */
  final class TopicRowsView extends View[Fanned, Fanned]:
    def onChange(message: Fanned): Effect = effects.updateRow(message)

  object TopicRows
      extends View.Companion[TopicRowsView, Fanned, Fanned](
        componentId = ComponentId("topic-rows"),
        source = ChangeSource.fromTopic(Topic, Codecs.serializer[Fanned]("fanned")),
        rowSerializer = Codecs.serializer[Fanned]("fanned")
      ):
    // Raised from 1 once: every reference declares 2, which names the group it reads under.
    override def version                  = Some(2)
    def create(ctx: ViewComponentContext) = new TopicRowsView

  /** Republishes what it reads, from the latest: none of what the topic held when it started. */
  final class TopicRelay extends Consumer[Fanned, Fanned]:
    def onMessage(message: Fanned): Effect = effects.produce(message)

  object TopicRelay
      extends Consumer.Companion[TopicRelay, Fanned, Fanned](
        componentId = ComponentId("topic-relay"),
        source =
          ChangeSource.fromTopic(Topic, Codecs.serializer[Fanned]("fanned"), StartFrom.Latest)
      ):
    def create(ctx: ConsumerContext) = new TopicRelay

    override val outputSerializer: Option[Serializer[Fanned]] =
      Some(Codecs.serializer[Fanned]("fanned"))

    override val produceTo: Option[String] = Some(Relayed)
  // docs:end topic-sources

  // ── contract-relay: a consumer that states a contract, a declared broker and parallel reading ──

  /**
   * The contract every reference states, from the same schema document: the fixtures' `order.v1`.
   */
  val OrderSchema: String =
    """{"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",""" +
      """"required":["id","total"],"properties":{"id":{"type":"string"},"total":{"type":"number"}}}"""

  val OrderContract: Contract = Contract
    .fromSchema("order.v1", OrderSchema.getBytes(StandardCharsets.UTF_8))
    .fold(why => throw IllegalStateException(why), identity)

  /** The declared broker the reference names; every conformance target declares it. */
  val DeclaredBroker: String = "legacy"

  /**
   * What `contract-relay` reads, and where it publishes, both as the contract and on the broker.
   */
  val Contracts: String  = "conformance-contracts"
  val Contracted: String = "conformance-contracted"

  // docs:start contract-relay
  /**
   * Reads `conformance-contracts` as `order.v1` on broker `legacy`, partitions in parallel, and
   * publishes one more.
   */
  final class ContractRelay extends Consumer[Fanned, Fanned]:
    def onMessage(message: Fanned): Effect = effects.produce(Fanned(message.n + 1))

  object ContractRelay
      extends Consumer.Companion[ContractRelay, Fanned, Fanned](
        componentId = ComponentId("contract-relay"),
        source = ChangeSource.fromTopic(
          Contracts,
          Codecs.serializer[Fanned]("fanned"),
          StartFrom.Earliest,
          TopicOptions(
            contract = Some(OrderContract),
            broker = Some(DeclaredBroker),
            parallel = true
          )
        )
      ):
    def create(ctx: ConsumerContext) = new ContractRelay

    override val outputSerializer: Option[Serializer[Fanned]] =
      Some(Codecs.serializer[Fanned]("fanned"))

    override def produces: Option[Publication] =
      Some(Publication(Contracted, Some(OrderContract), Some(DeclaredBroker)))
  // docs:end contract-relay

  /**
   * The carts as a graph: the cart's node for an item added or removed, the cart checked out with
   * its checkout and the edge between them for a checkout, the cart's tombstone when it is deleted.
   * A function of the event alone, so every reference publishes the same records.
   */
  final class CartGraph extends GraphConsumer[ShoppingCartEvent]:
    private def cart(id: String, checkedOut: Boolean) =
      graph.node(s"cart:$id", Seq("Cart"), Map("cartId" -> id, "checkedOut" -> checkedOut))

    def onMessage(event: ShoppingCartEvent): Effect =
      val id = messageContext.subject
      event match
        case _: ItemAdded | _: ItemRemoved => effects.publish(cart(id, checkedOut = false))
        case CheckedOut =>
          effects.publish(
            cart(id, checkedOut = true),
            graph.node(s"checkout:$id", Seq("Checkout"), Map("cartId" -> id)),
            graph.edge(s"checked-out:$id", "CHECKED_OUT", from = s"cart:$id", to = s"checkout:$id")
          )
        case Discarded => effects.ignore()

    override def onDelete: Effect =
      effects.publish(graph.tombstoneNode(s"cart:${messageContext.subject}"))

  object CartGraph
      extends GraphConsumer.Companion[CartGraph, ShoppingCartEvent](
        componentId = ComponentId("cart-graph"),
        source = ChangeSource.eventsOf(ShoppingCartEntity),
        topic = "conformance-graph"
      ):
    def create(ctx: ConsumerContext) = new CartGraph

  /** The key value entity as a graph: its node at each state's revision, its tombstone after. */
  final class ProfileGraph extends GraphConsumer[ProfileState]:
    def onMessage(state: ProfileState): Effect =
      effects.publish(
        graph.node(s"profile:${messageContext.subject}", Seq("Profile"), Map("name" -> state.name))
      )

    override def onDelete: Effect =
      effects.publish(graph.tombstoneNode(s"profile:${messageContext.subject}"))

  object ProfileGraph
      extends GraphConsumer.Companion[ProfileGraph, ProfileState](
        componentId = ComponentId("profile-graph"),
        source = ChangeSource.stateOf(Profile),
        topic = "conformance-profile-graph"
      ):
    def create(ctx: ConsumerContext) = new ProfileGraph

  // ── reminder: a timed action ──

  final class Reminder(context: TimedActionContext) extends TimedAction:
    def remind(id: String): Effect =
      val _ = context.componentClient
        .forEventSourcedEntity(EntityId(id))
        .call(Conformance.record)
        .invoke("reminded")
      effects.done()

    /** Records the due time it was run for, as the runtime told it. */
    def tick(id: String): Effect =
      val _ = context.componentClient
        .forEventSourcedEntity(EntityId(id))
        .call(Conformance.record)
        .invoke(s"due:${context.dueTime.toEpochMilli}")
      effects.done()

  object Reminder extends TimedAction.Companion[Reminder](ComponentId("reminder")):
    def create(context: TimedActionContext) = new Reminder(context)
    val remind                              = handler("remind")(_.remind)
    val tick                                = handler("tick")(_.tick)

  // ── assistant: an agent whose tool acts through the client ──

  final class Assistant(context: AgentContext) extends Agent:
    private val lookup = FunctionTool
      .named("lookup")
      .describedAs("Looks up how many things were recorded under an id.")
      .param[String]("id", "The id to look up.")
      .handle { id =>
        if id.isEmpty then throw IllegalArgumentException("an id is needed")
        val entity = context.componentClient.forEventSourcedEntity(EntityId(id))
        val _      = entity.call(Conformance.record).invoke("looked-up")
        s"count for $id is ${entity.call(Conformance.count).invoke()}"
      }
    private val noSecrets = Guardrail.forbidding("no-secrets", "sk-".r)
    private def describe(question: String) =
      effects
        .systemMessage("You are helpful.")
        .userMessage(question)
        .tools(lookup)
        .guardrails(noSecrets)
    def ask(question: String): Effect[String]  = describe(question).thenReply()
    def stream(question: String): StreamEffect = describe(question).thenStream()

  object Assistant extends Agent.Companion[Assistant](ComponentId("assistant")):
    def create(context: AgentContext) = new Assistant(context)
    val ask                           = command("ask")(_.ask)
    val streamAsk                     = stream("stream")(_.stream)

  // ── answerer: an autonomous agent, whose tool acts through the client ──

  final case class Answer(answer: String, sources: List[String])
  object Answer:
    given JsonValueCodec[Answer]        = Codecs.make
    given autonomous.JsonSchema[Answer] = autonomous.JsonSchema.derived

  val AnswerType: autonomous.TaskType[Answer] = autonomous.Task
    .named("answer")
    .describedAs("Answer a question, citing what you looked up")
    .resultConformsTo[Answer]
    .rule("cites-sources")(a =>
      if a.sources.isEmpty then autonomous.TaskRule.Rejected("sources must not be empty")
      else autonomous.TaskRule.Accepted
    )
    // Throws the first time it sees "flaky-once": a rule that fails once, then decides.
    .rule("steady")(a =>
      if a.answer == "flaky-once" && FlakyRule.seen.add(a.answer) then
        throw IllegalStateException("the rule threw")
      else autonomous.TaskRule.Accepted
    )

  object FlakyRule:
    val seen: java.util.Set[String] = java.util.concurrent.ConcurrentHashMap.newKeySet[String]()

  final class Answerer(context: autonomous.AutonomousAgentContext)
      extends autonomous.AutonomousAgent(context):
    override def tools: Seq[FunctionTool] = Seq(
      FunctionTool
        .named("lookup")
        .describedAs("Looks up how many things were recorded under an id.")
        .param[String]("id", "The id to look up.")
        .handle { id =>
          if id.isEmpty then throw IllegalArgumentException("an id is needed")
          val entity = context.componentClient.forEventSourcedEntity(EntityId(id))
          val _      = entity.call(Conformance.record).invoke("looked-up")
          s"count for $id is ${entity.call(Conformance.count).invoke()}"
        },
      // Waits for a person before it runs; what it records is counted as a run.
      FunctionTool
        .named("sensitive_lookup")
        .describedAs("Looks up what was recorded under an id. A person approves every one.")
        .param[String]("id", "The id to look up.")
        .handle { id =>
          val entity = context.componentClient.forEventSourcedEntity(EntityId(id))
          val _      = entity.call(Conformance.record).invoke("sensitive")
          s"sensitive count for $id is ${entity.call(Conformance.count).invoke()}"
        }
        .requiresApproval
    )

  object Answerer extends autonomous.AutonomousAgent.Companion[Answerer](ComponentId("answerer")):
    def create(context: autonomous.AutonomousAgentContext) = new Answerer(context)
    def definition =
      define
        .describedAs("Answers questions")
        .guardrails(Guardrail.forbidding("no-secrets", "sk-".r))
        .capability(autonomous.TaskAcceptance.of(AnswerType).maxIterationsPerTask(4))

  // ── approver: an agent whose tools wait for a person, with two MCP servers ──

  final class Approver(context: AgentContext) extends Agent:
    // docs:start approver-tool
    private val refund = FunctionTool
      .named("refund")
      .describedAs("Refunds what was recorded under an id. A person approves every refund.")
      .param[String]("id", "The id to refund.")
      .handle { id =>
        val entity = context.componentClient.forEventSourcedEntity(EntityId(id))
        val _      = entity.call(Conformance.record).invoke("refunded")
        s"refunded $id"
      }
      .requiresApproval
    // docs:end approver-tool
    // docs:start tool-calls-service
    // A tool calls another service as this service: the called service's ACL can admit it by name.
    private val askScripted = FunctionTool
      .named("ask_scripted")
      .describedAs("Asks the scripted service for what is at a path.")
      .param[String]("path", "The path to ask for.")
      .handle(path => context.services("scripted").getText(path))
    // docs:end tool-calls-service
    def ask(question: String): Effect[String] =
      effects
        .systemMessage("You approve refunds.")
        .userMessage(question)
        .tools(refund, askScripted)
        .thenReply()

  object Approver extends Agent.Companion[Approver](ComponentId("approver")):
    // Both found at ANKKA_MCP_<NAME>_URL; every tool of `guarded` waits for a person.
    override def mcpServers: Vector[mcp.McpServer] = Vector(
      mcp.McpServer.named("tickets"),
      mcp.McpServer.named("guarded").requiresApproval
    )
    override def resultGuardrails: Vector[Guardrail] = Vector(
      Guardrail.forbidding("no-instructions", "(?i)ignore what you were told".r)
    )
    def create(context: AgentContext) = new Approver(context)
    val ask                           = command("ask")(_.ask)

  /**
   * An outcome as every reference renders it: `{"answered": text}`, or `{"awaiting": [{"id",
   * "tool", "arguments"}]}` — the request's own fields, so the suite reads the same JSON from every
   * language.
   */
  def renderOutcome(outcome: AgentOutcome[String]): String = outcome match
    case AgentOutcome.Answered(text) => Json.obj("answered" -> Json.Str(text)).render
    case AgentOutcome.AwaitingApproval(requests) =>
      Json
        .obj(
          "awaiting" -> Json.Arr(
            requests.map(r =>
              Json
                .obj("id" -> Json.Str(r.id), "tool" -> Json.Str(r.tool), "arguments" -> r.arguments)
            )
          )
        )
        .render

  /** A decision from `{"approved": bool, "by": name, "note": text}`, on the request `id`. */
  def decisionFrom(id: String, body: String): Decision =
    val json = Json.parse(body).fold(p => throw HttpProblem.badRequest(p), identity)
    val by   = json("by").flatMap(_.asString).getOrElse("")
    val note = json("note").flatMap(_.asString).getOrElse("")
    if json("approved").flatMap(_.asBoolean).getOrElse(false) then Decision.approved(id, by)
    else Decision.refused(id, by, note)

  // ── Endpoints ──

  given JsonValueCodec[ShoppingCart] = Codecs.make[ShoppingCart]
  given JsonValueCodec[LineItem]     = Codecs.make[LineItem]
  given JsonValueCodec[CartRow]      = Codecs.make[CartRow]

  final class CartsEndpoint(clients: EndpointClients) extends HttpEndpoint("/carts"):
    val acl: Acl                 = Acl.AllowAll
    private def cart(id: String) = clients.componentClient.forEventSourcedEntity(EntityId(id))
    postBody("/{cartId}/items") { (cartId: String, item: LineItem) =>
      cart(cartId).call(ShoppingCartEntity.addItem).invoke(item)
    }
    delete("/{cartId}/items/{productId}") { (cartId: String, productId: String) =>
      cart(cartId).call(ShoppingCartEntity.removeItem).invoke(productId)
    }
    post("/{cartId}/checkout") { (cartId: String) =>
      cart(cartId).call(ShoppingCartEntity.checkout).invoke()
    }
    delete("/{cartId}") { (cartId: String) =>
      cart(cartId).call(ShoppingCartEntity.discard).invoke()
    }
    get("/awkward")(() => "literal")
    get("/{cartId}")((cartId: String) => cart(cartId).call(ShoppingCartEntity.getCart).invoke())
    get("/{cartId}/rows") { (cartId: String) =>
      clients.viewClient
        .forView(CartRows)
        .get(cartId)
        .getOrElse(throw HttpProblem.notFound(s"no row for '$cartId'"))
    }

  given JsonValueCodec[TreeRow]   = Codecs.make[TreeRow]
  given JsonValueCodec[JoinedRow] = Codecs.make[JoinedRow]

  /** Records on either side of the keyed view, and reads its rows. */
  final class JoinedEndpoint(clients: EndpointClients) extends HttpEndpoint("/joined"):
    val acl: Acl                   = Acl.AllowAll
    private def entity(id: String) = clients.componentClient.forEventSourcedEntity(EntityId(id))
    // The left entity is the row's own key: one left per row.
    post("/left/{key}/{holding}") { (key: String, holding: String) =>
      entity(key).call(JoinedLeft.record).invoke(s"$key|$holding")
    }
    post("/right/{rightId}")((rightId: String) =>
      entity(rightId).call(JoinedRight.record).invoke("")
    )
    get("/rows/{key}") { (key: String) =>
      clients.viewClient
        .forView(JoinedRows)
        .get(key)
        .getOrElse(throw HttpProblem.notFound(s"no row '$key'"))
    }

  /** Places nodes of a tree and asks what is under one. */
  final class TreeEndpoint(clients: EndpointClients) extends HttpEndpoint("/tree"):
    val acl: Acl                 = Acl.AllowAll
    private def node(id: String) = clients.componentClient.forEventSourcedEntity(EntityId(id))
    post("/{nodeId}")((nodeId: String) => node(nodeId).call(TreeNode.place).invoke(""))
    post("/{nodeId}/under/{parentId}") { (nodeId: String, parentId: String) =>
      node(nodeId).call(TreeNode.place).invoke(parentId)
    }
    get("/{nodeId}/below") { (nodeId: String) =>
      clients.viewClient.forView(TreeRows).ask(TreeRows.under, "row" -> nodeId).map(_.key)
    }

  final case class Echo(a: Vector[String], b: Option[String], headers: Map[String, String])
  given JsonValueCodec[Echo]           = Codecs.make[Echo]
  given JsonValueCodec[Vector[String]] = Codecs.make[Vector[String]]

  /**
   * What a call to another service came to, as the reference answers it in every language:
   * `outcome` is `response`, `failed`, `unresolvable`, `mismatch`, `unanswered` or `refused`;
   * `answer` is the `X-Answer` header the other service set, if any.
   */
  final case class ServiceCallRecord(
      outcome: String,
      status: Int,
      contentType: String,
      body: String,
      answer: String,
      message: String
  )
  given JsonValueCodec[ServiceCallRecord] = Codecs.make[ServiceCallRecord]

  /**
   * What the socket routes' handlers noticed, for a case to read once a socket has closed: in every
   * language, `closed:<room>` when a `/socket/{room}` handler was told its socket closed, and
   * `private:<subject>` when the authenticated one ran.
   */
  val socketLog = java.util.concurrent.ConcurrentLinkedQueue[String]()

  private def callerWord(caller: Caller): String = caller match
    case Caller.Service(project, name) => s"service:$project/$name"
    case Caller.Gateway                => "gateway"
    case Caller.Local                  => "local"

  /** `problems`: what a sidecar reported through `ReportError`; empty in-process. */
  final class ConformanceEndpoint(
      clients: EndpointClients,
      timers: () => TimerScheduler,
      problems: () => Vector[String]
  ) extends HttpEndpoint("/conformance"):
    val acl: Acl                       = Acl.AllowAll
    private val transport              = clients.componentClient.transportRef
    private def entity(id: String)     = clients.componentClient.forEventSourcedEntity(EntityId(id))
    private def profile(id: String)    = clients.componentClient.forKeyValueEntity(EntityId(id))
    private def checkout(id: String)   = clients.componentClient.forWorkflow(EntityId(id))
    private def agent(session: String) = clients.componentClient.forAgent(SessionId(session))

    get("/problems")(() => problems())

    // docs:start service-call
    // A call to another service (protocol 1.8), as the case asks for it: `service`, `method` and
    // `path` from the query, the body and every `X-Conformance-*` header sent on, and two headers no
    // handler may send — the caller's and the host — added to show they never arrive. The answer is
    // a record of what the client returned or raised, the same in every language.
    postBody("/service-call") { (body: String) =>
      val service = query.required[String]("service")
      val method  = query.required[String]("method")
      val path    = query.required[String]("path")
      val headers = request.headers.filter((n, _) => n.toLowerCase.startsWith("x-conformance-")) ++
        Seq("X-Ankka-Caller" -> "ankka://elsewhere/impostor", "Host" -> "elsewhere")
      val client = clients.services(service)
      try
        if query.optional[String]("mode").contains("typed") then
          ServiceCallRecord("response", 200, "", client.getText(path, headers), "", "")
        else
          val answer = client.request(
            method,
            path,
            Option.when(body.nonEmpty)(body.getBytes(StandardCharsets.UTF_8)),
            request.header("Content-Type").filter(_ => body.nonEmpty),
            headers
          )
          ServiceCallRecord(
            "response",
            answer.status,
            answer.contentType,
            answer.text,
            answer.headers
              .collectFirst { case (n, v) if n.equalsIgnoreCase("x-answer") => v }
              .getOrElse(""),
            ""
          )
      catch
        case e: ServiceCallFailed => ServiceCallRecord("failed", e.status, "", e.body, "", "")
        case e: ServiceUnresolvable =>
          ServiceCallRecord("unresolvable", 0, "", "", "", e.getMessage)
        case e: ServiceIdentityMismatch =>
          ServiceCallRecord("mismatch", 0, "", "", "", e.getMessage)
        case e: ServiceUnanswered => ServiceCallRecord("unanswered", 0, "", "", "", e.getMessage)
        case e: CommandError      => ServiceCallRecord("refused", 0, "", "", "", e.message)
    }
    // docs:end service-call

    // The secret store. The name is a query parameter because it may hold a slash.
    postBody("/secrets") { (value: String) =>
      clients.secrets.put(query.required[String]("name"), value)
      Done: Done
    }
    get("/secrets") { () =>
      val name = query.required[String]("name")
      clients.secrets.get(name).getOrElse(throw HttpProblem.notFound(s"no secret '$name'"))
    }
    delete("/secrets") { () =>
      clients.secrets.delete(query.required[String]("name"))
      Done: Done
    }
    get("/echo") { () =>
      Echo(
        query.rawAll("a"),
        query.raw("b"),
        Map(
          "x-one" -> request.header("x-one").getOrElse(""),
          "x-two" -> request.header("x-two").getOrElse("")
        )
      )
    }
    get[Int, String]("/status/{code}")((code: Int) =>
      throw HttpProblem(code, s"status $code as asked")
    )
    get[String]("/boom")(() => throw RuntimeException("boom from the handler"))

    // A route whose acl differs from its endpoint's: `/conformance` admits everyone, this one
    // admits nobody, and the routes declared around it are unaffected.
    withAcl(Acl.DenyAll) {
      get[String]("/closed")(() => "never reached")
    }
    sse("/stream/{session}") { (session: String) =>
      val _ = session
      Source(List(" leading space", "two\nlines", "plain"))
    }

    // Sockets (protocol 1.9). Echoes each frame; "context" is answered with the room and the
    // opening request's `tag`, read after any number of frames.
    socket("/socket/{room}") { (room: String, socket: Socket) =>
      Iterator.continually(socket.receive()).takeWhile(_.isDefined).flatten.foreach {
        case "context" => socket.send(s"$room ${query.raw("tag").getOrElse("")}")
        case text      => socket.send(text)
      }
      socketLog.add(s"closed:$room"): Unit
    }
    socket("/socket-once")(socket => socket.receive(): Unit)
    socket("/socket-fail") { socket =>
      socket.receive(): Unit
      throw RuntimeException("the socket handler broke")
    }
    get("/socket-log")(() => socketLog.toArray.toVector.map(_.toString))
    postBody("/profile/{id}") { (id: String, name: String) =>
      profile(id).call(Profile.set).invoke(name)
    }
    get("/profile/{id}")((id: String) => profile(id).call(Profile.get).invoke())
    delete("/profile/{id}")((id: String) => profile(id).call(Profile.delete).invoke())
    postBody("/checkout/{id}") { (id: String, mode: String) =>
      checkout(id).call(Checkout.start).invoke(mode)
    }
    get("/checkout/{id}")((id: String) => checkout(id).call(Checkout.status).invoke())
    post[String, Done]("/remind/{id}") { (id: String) =>
      timers().createSingleTimer(s"remind-$id", 1.second, Reminder.remind.deferred(id))
      Done
    }
    // A recurring timer: due at once, then every second.
    post[String, Done]("/recur/{id}") { (id: String) =>
      timers().createRecurringTimer(
        s"recur-$id",
        Duration.Zero,
        1.second,
        Reminder.tick.deferred(id)
      )
      Done
    }
    // The same timer set again, with a delay a replacement would be first due after.
    post[String, Done]("/recur/{id}/again") { (id: String) =>
      timers().createRecurringTimer(s"recur-$id", 60.seconds, 1.second, Reminder.tick.deferred(id))
      Done
    }
    post[String, Done]("/recur/{id}/cancel") { (id: String) =>
      timers().delete(s"recur-$id")
      Done
    }
    post[String, Done]("/recur-refused/{id}") { (id: String) =>
      try
        timers().createRecurringTimer(
          s"recur-$id",
          Duration.Zero,
          Duration.Zero,
          Reminder.tick.deferred(id)
        )
      catch
        case e: IllegalArgumentException => throw CommandError(e.getMessage, ErrorCode.BadRequest)
      Done
    }
    postBody("/ask/{session}") { (session: String, question: String) =>
      agent(session).call(Assistant.ask).invoke(question)
    }
    // docs:start approver-routes
    // A turn that may wait: the model's answer, or the approval requests it waits on.
    postBody("/approver/{session}") { (session: String, question: String) =>
      renderOutcome(agent(session).ask(Approver.ask).invoke(question))
    }
    // A person's decision, answered as the turn's caller would have been once the turn goes on.
    postBody("/approver/{session}/decide/{id}") { (session: String, id: String, body: String) =>
      renderOutcome(agent(session).decide(Approver.ask)(decisionFrom(id, body)))
    }
    // docs:end approver-routes
    sse("/stream-ask/{session}") { (session: String) =>
      agent(session).stream(Assistant.streamAsk)(query.raw("q").getOrElse(""))
    }
    get("/{id}/count")((id: String) => entity(id).call(Conformance.count).invoke())
    post[String, Done]("/{id}/no-reply") { (id: String) =>
      // A handler that answers nothing: the ask is never awaited, and the route says so with 204.
      val _ = transport.ask(
        Conformance.componentId,
        EntityId(id),
        MethodName("no-reply"),
        Array.emptyByteArray,
        Metadata.empty
      )
      Done
    }
    postBody("/{id}/{handler}") { (id: String, handler: String, body: String) =>
      // The generic forwarder: the body goes through as the handler's input, the reply comes back as text.
      import scala.concurrent.Await
      val bytes = Await.result(
        transport.ask(
          Conformance.componentId,
          EntityId(id),
          MethodName(handler),
          body.getBytes("UTF-8"),
          Metadata.empty
        ),
        10.seconds
      )
      String(bytes, "UTF-8")
    }

  /** Autonomous agents: tasks run, read and cancelled; instances driven and watched. */
  final class AutonomousEndpoint(clients: EndpointClients) extends HttpEndpoint("/autonomous"):
    import autonomous.*
    val acl: Acl       = Acl.AllowAll
    private def client = clients.componentClient
    private def record(id: String): String =
      String(TaskEntity.stateSerializer.toBytes(client.forTask(id).get()), "UTF-8")

    postBody("/tasks/{type}") { (taskType: String, instructions: String) =>
      if taskType != AnswerType.name then throw HttpProblem.badRequest(s"no task type '$taskType'")
      val id       = client.forAutonomousAgent(Answerer).runSingleTask(AnswerType, instructions)
      val instance = client.forTask(id).get().assignee.map(_.instanceId).getOrElse("")
      s"""{"taskId":"$id","instanceId":"$instance"}"""
    }
    get("/tasks/{id}")((id: String) => record(id))
    post[String, Done]("/tasks/{id}/cancel")((id: String) => client.forTask(id).cancel())
    postBody("/tasks/{type}/create") { (taskType: String, body: String) =>
      // {"instructions": "...", "dependsOn": ["..."]} — creates without running.
      if taskType != AnswerType.name then throw HttpProblem.badRequest(s"no task type '$taskType'")
      val json = Json.parse(body).fold(p => throw HttpProblem.badRequest(p), identity)
      val deps = json("dependsOn").flatMap(_.asArray).getOrElse(Vector.empty).flatMap(_.asString)
      val id = client.tasks
        .create(AnswerType, json("instructions").flatMap(_.asString).getOrElse(""))
        .dependsOn(deps*)
        .create()
      s"""{"taskId":"$id"}"""
    }
    postBody("/instances/{instance}/assign") { (instance: String, body: String) =>
      val ids =
        Json.parse(body).toOption.flatMap(_.asArray).getOrElse(Vector.empty).flatMap(_.asString)
      val r = client.forAutonomousAgent(Answerer)(instance).assign(ids*)
      s"""{"accepted":[${r.accepted.map(i => s"\"$i\"").mkString(",")}]}"""
    }
    post[String, String, Done]("/instances/{instance}/{op}") { (instance: String, op: String) =>
      val calls = client.forAutonomousAgent(Answerer)(instance)
      op match
        case "suspend"   => calls.suspend()
        case "resume"    => calls.resume()
        case "terminate" => calls.terminate()
        case other       => throw HttpProblem.notFound(s"no operation '$other'")
    }
    sse("/instances/{instance}/notifications") { (instance: String) =>
      client
        .forAutonomousAgent(Answerer)(instance)
        .notifications()
        .map(n => String(Notification.serializer.toBytes(n), "UTF-8"))
    }
    postBody("/instances/{instance}/decide/{id}") { (instance: String, id: String, body: String) =>
      client.forAutonomousAgent(Answerer)(instance).decide(decisionFrom(id, body))
    }
    get("/instances/{instance}/state") { (instance: String) =>
      String(
        AgentState.serializer.toBytes(client.forAutonomousAgent(Answerer)(instance).state()),
        "UTF-8"
      )
    }

  /**
   * Caller-naming ACLs (feature 014). The suite names callers through the local caller header,
   * since every target runs outside a cluster, where this service's own identity is `local/local`.
   */
  final class CallersEndpoint extends HttpEndpoint("/callers"):
    val acl: Acl = Acl.allowCallers(Callers.internet, Callers.service("orders"))

    get("/whoami")(() =>
      caller match
        case Caller.Service(project, name) => s"service:$project/$name"
        case Caller.Gateway                => "gateway"
        case Caller.Local                  => "local"
    )

    withAcl(Acl.allowCallers(Callers.self)) {
      get("/self")(() => "self")
      sse("/events")(() => Source.single("tick"))
    }

  /**
   * Admits only a verified token from the suite's test issuer (feature 022), and says what the
   * principal carried, so every language is held to the same answer.
   */
  final class PrivateEndpoint extends HttpEndpoint("/private"):
    val acl: Acl = ConformanceTarget.authenticated
    get("/")(() => "private")
    get("/me")(() =>
      Json
        .obj(
          "subject" -> Json.Str(principal.subject),
          "roles"   -> Json.Arr(principal.roles.toVector.sorted.map(Json.Str(_))),
          "tier"    -> principal.claims.get("tier").fold(Json.Null)(Json.Str(_)),
          "issuer"  -> principal.issuer.fold(Json.Null)(Json.Str(_))
        )
        .render
    )
    // The principal and the caller, sent once when the socket opens.
    socket("/socket") { socket =>
      socketLog.add(s"private:${principal.subject}"): Unit
      socket.send(
        Json
          .obj(
            "subject" -> Json.Str(principal.subject),
            "roles"   -> Json.Arr(principal.roles.toVector.sorted.map(Json.Str(_))),
            "caller"  -> Json.Str(callerWord(caller))
          )
          .render
      )
      while socket.receive().isDefined do ()
    }

  val ComponentIds: Set[String] = Set(
    "shopping-cart",
    "conformance",
    "profile",
    "checkout",
    "cart-rows",
    "checkout-recorder",
    "checkout-fanout",
    "topic-rows",
    "topic-relay",
    "contract-relay",
    "tree-node",
    "tree-rows",
    "joined-left",
    "joined-right",
    "joined-rows",
    "cart-graph",
    "profile-graph",
    "reminder",
    "assistant",
    "answerer",
    "approver"
  )

  def descriptors: Seq[ComponentDescriptor] = Seq(
    ShoppingCartEntity.descriptor,
    Conformance.descriptor,
    Profile.descriptor,
    Checkout.descriptor,
    CartRows.descriptor,
    CheckoutRecorder.descriptor,
    CheckoutFanout.descriptor,
    TopicRows.descriptor,
    TopicRelay.descriptor,
    ContractRelay.descriptor,
    TreeNode.descriptor,
    TreeRows.descriptor,
    JoinedLeft.descriptor,
    JoinedRight.descriptor,
    JoinedRows.descriptor,
    CartGraph.descriptor,
    ProfileGraph.descriptor,
    Reminder.descriptor,
    Assistant.descriptor,
    Answerer.descriptor,
    Approver.descriptor
  )

  def endpoints(
      timers: () => TimerScheduler,
      problems: () => Vector[String]
  ): Seq[EndpointClients => HttpEndpoint] = Seq(
    clients => CartsEndpoint(clients),
    clients => TreeEndpoint(clients),
    clients => JoinedEndpoint(clients),
    clients => ConformanceEndpoint(clients, timers, problems),
    _ => PrivateEndpoint(),
    _ => CallersEndpoint(),
    clients => AutonomousEndpoint(clients)
  )
