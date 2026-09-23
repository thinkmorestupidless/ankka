package com.thinkmorestupidless.ankka.sidecar.conformance

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.sdk.*
import org.apache.pekko.stream.scaladsl.Source

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

  import ShoppingCartEvent.*

  final class ShoppingCartEntity(context: EventSourcedEntityContext)
      extends EventSourcedEntity[ShoppingCart, ShoppingCartEvent]:
    def emptyState: ShoppingCart = ShoppingCart(context.entityId, Nil, checkedOut = false)
    def applyEvent(event: ShoppingCartEvent): ShoppingCart = event match
      case ItemAdded(item)        => currentState.addItem(item)
      case ItemRemoved(productId) => currentState.removeItem(productId)
      case CheckedOut             => currentState.copy(checkedOut = true)
    def addItem(item: LineItem): Effect[Done] =
      if currentState.checkedOut then
        effects.error("cart is already checked out", ErrorCode.Conflict)
      else if item.quantity <= 0 then effects.error(s"quantity must be greater than zero")
      else effects.persist(ItemAdded(item)).thenReply(_ => Done)
    def removeItem(productId: String): Effect[Done] =
      if !currentState.items.exists(_.productId == productId) then
        effects.error(s"cart does not contain '$productId'", ErrorCode.NotFound)
      else effects.persist(ItemRemoved(productId)).thenReply(_ => Done)
    def checkout: Effect[ShoppingCart] =
      if currentState.items.isEmpty then effects.error("cannot check out an empty cart")
      else effects.persist(CheckedOut).deleteEntity().thenReplyState
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

    /** The tombstone: a checked-out cart's row outlives the cart. */
    override def onDelete: Effect = rowState match
      case Some(row) => effects.updateRow(row.copy(checkedOut = true))
      case None      => effects.ignore()

  object CartRows
      extends View.Companion[CartRowsView, ShoppingCartEvent, CartRow](
        componentId = ComponentId("cart-rows"),
        source = ChangeSource.eventsOf(ShoppingCartEntity),
        rowSerializer = Codecs.serializer[CartRow]("cart-row")
      ):
    def create(ctx: ViewComponentContext) = new CartRowsView

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

  // ── reminder: a timed action ──

  final class Reminder(context: TimedActionContext) extends TimedAction:
    def remind(id: String): Effect =
      val _ = context.componentClient
        .forEventSourcedEntity(EntityId(id))
        .call(Conformance.record)
        .invoke("reminded")
      effects.done()

  object Reminder extends TimedAction.Companion[Reminder](ComponentId("reminder")):
    def create(context: TimedActionContext) = new Reminder(context)
    val remind                              = handler("remind")(_.remind)

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
    get("/awkward")(() => "literal")
    get("/{cartId}")((cartId: String) => cart(cartId).call(ShoppingCartEntity.getCart).invoke())
    get("/{cartId}/rows") { (cartId: String) =>
      clients.viewClient
        .forView(CartRows)
        .get(cartId)
        .getOrElse(throw HttpProblem.notFound(s"no row for '$cartId'"))
    }

  final case class Echo(a: Vector[String], b: Option[String], headers: Map[String, String])
  given JsonValueCodec[Echo]           = Codecs.make[Echo]
  given JsonValueCodec[Vector[String]] = Codecs.make[Vector[String]]

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
    sse("/stream/{session}") { (session: String) =>
      val _ = session
      Source(List(" leading space", "two\nlines", "plain"))
    }
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
    postBody("/ask/{session}") { (session: String, question: String) =>
      agent(session).call(Assistant.ask).invoke(question)
    }
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

  final class PrivateEndpoint extends HttpEndpoint("/private"):
    val acl: Acl = Acl.Authenticate(_ => AuthDecision.Unavailable("no authenticator is configured"))
    get("/")(() => "private")

  val ComponentIds: Set[String] = Set(
    "shopping-cart",
    "conformance",
    "profile",
    "checkout",
    "cart-rows",
    "checkout-recorder",
    "reminder",
    "assistant"
  )

  def descriptors: Seq[ComponentDescriptor] = Seq(
    ShoppingCartEntity.descriptor,
    Conformance.descriptor,
    Profile.descriptor,
    Checkout.descriptor,
    CartRows.descriptor,
    CheckoutRecorder.descriptor,
    Reminder.descriptor,
    Assistant.descriptor
  )

  def endpoints(
      timers: () => TimerScheduler,
      problems: () => Vector[String]
  ): Seq[EndpointClients => HttpEndpoint] = Seq(
    clients => CartsEndpoint(clients),
    clients => ConformanceEndpoint(clients, timers, problems),
    _ => PrivateEndpoint()
  )
