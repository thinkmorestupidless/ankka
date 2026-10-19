package com.thinkmorestupidless.ankka.mcp.features

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.mcp.{ClientKeys, McpEndpoint}
import com.thinkmorestupidless.ankka.sdk.*

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/**
 * The service the MCP endpoint features run against: a cart and a job, an HTTP endpoint listing a
 * day's jobs, and the MCP endpoint whose tools the scenarios name. Every handler records that it
 * ran, so "no handler is run" is a count, not a log line (R19).
 */
object Fixtures:

  /** What ran: `(component or tool, id, handler, input)`, in order. */
  val ran = ConcurrentLinkedQueue[(String, String, String, String)]()

  def runs: Vector[(String, String, String, String)] = ran.asScala.toVector

  def record(component: String, id: String, handler: String, input: String = ""): Unit =
    ran.add((component, id, handler, input)): Unit

  val Refusal = "quantity must be positive"

  final case class Item(productId: String, quantity: Int)
  final case class Cart(cartId: String, items: List[Item], checkedOut: Boolean)
  enum CartEvent:
    case Added(item: Item)
    case CheckedOut

  given JsonValueCodec[Cart] = Codecs.make

  final class CartEntity(context: EventSourcedEntityContext)
      extends EventSourcedEntity[Cart, CartEvent]:
    def emptyState: Cart = Cart(context.entityId, Nil, checkedOut = false)
    def applyEvent(event: CartEvent): Cart = event match
      case CartEvent.Added(item) => currentState.copy(items = currentState.items :+ item)
      case CartEvent.CheckedOut  => currentState.copy(checkedOut = true)

    def addItem(item: Item): Effect[Done] =
      record("cart", context.entityId, "add-item", s"${item.productId} ${item.quantity}")
      if item.quantity <= 0 then effects.error(Refusal)
      else effects.persist(CartEvent.Added(item)).thenReply(_ => Done)

    def checkout: Effect[Cart] =
      record("cart", context.entityId, "checkout")
      effects.persist(CartEvent.CheckedOut).thenReplyState

    def getCart: ReadOnlyEffect[Cart] =
      record("cart", context.entityId, "get-cart")
      effects.reply(currentState)

  object CartEntity
      extends EventSourcedEntity.Companion[CartEntity, Cart, CartEvent](
        componentId = ComponentId("cart"),
        stateSerializer = Codecs.serializer[Cart]("mcp-cart"),
        eventSerializer = Codecs.serializer[CartEvent]("mcp-cart-event")
      ):
    given Serializer[Item]                         = Codecs.serializer[Item]("mcp-item")
    def create(context: EventSourcedEntityContext) = new CartEntity(context)
    val addItem                                    = command("add-item")(_.addItem)
    val checkout                                   = command("checkout")(_.checkout)
    val getCart                                    = query("get-cart")(_.getCart)

  final case class Job(jobId: String, slot: Option[String])
  enum JobEvent:
    case Booked(slot: String)

  given JsonValueCodec[Job] = Codecs.make

  final class JobEntity(context: EventSourcedEntityContext)
      extends EventSourcedEntity[Job, JobEvent]:
    def emptyState: Job = Job(context.entityId, None)
    def applyEvent(event: JobEvent): Job = event match
      case JobEvent.Booked(slot) => currentState.copy(slot = Some(slot))

    /** A scalar input: `Serializers.string`, raw UTF-8 and never JSON — the trap R4 names. */
    def book(slot: String): Effect[Job] =
      record("job", context.entityId, "book", slot)
      effects.persist(JobEvent.Booked(slot)).thenReplyState

    def get: ReadOnlyEffect[Job] =
      record("job", context.entityId, "get")
      effects.reply(currentState)

  object JobEntity
      extends EventSourcedEntity.Companion[JobEntity, Job, JobEvent](
        componentId = ComponentId("job"),
        stateSerializer = Codecs.serializer[Job]("mcp-job"),
        eventSerializer = Codecs.serializer[JobEvent]("mcp-job-event")
      ):
    def create(context: EventSourcedEntityContext) = new JobEntity(context)
    val book                                       = command("book")(_.book)
    val get                                        = query("get")(_.get)

  final case class DayJobs(day: String, jobs: List[String])
  given JsonValueCodec[DayJobs] = Codecs.make

  /** An ordinary HTTP endpoint, one route of which a tool refers to. */
  final class JobsEndpoint extends HttpEndpoint("/jobs"):
    val acl: Acl = Acl.AllowAll
    get("/{day}") { (day: String) =>
      record("jobs-endpoint", day, "GET /jobs/{day}")
      DayJobs(day, List(s"$day-1", s"$day-2"))
    }

  /** Which of the references a scenario asked the cart's MCP endpoint to have. */
  final case class References(
      bookJob: Boolean = false,
      getJob: Option[String] = None,
      todaysJobs: Boolean = false
  )

  /** The cart's MCP endpoint: three tools, and the references a scenario asks for. */
  final class CartMcp(clients: EndpointClients, references: References, val acl: Acl)
      extends McpEndpoint("/mcp"):

    val getCart = tool("get_cart")
      .describedAs("Reads one cart")
      .param[String]("cartId", "The cart's id")
      .handle { (cartId: String) =>
        record("tool", cartId, "get_cart")
        clients.componentClient
          .forEventSourcedEntity(EntityId(cartId))
          .call(CartEntity.getCart)
          .invoke()
      }

    val addItem = tool("add_item")
      .describedAs("Adds an item to a cart")
      .param[String]("cartId", "The cart's id")
      .param[Int]("quantity", "How many; positive")
      .handle { (cartId: String, quantity: Int) =>
        record("tool", cartId, "add_item", quantity.toString)
        clients.componentClient
          .forEventSourcedEntity(EntityId(cartId))
          .call(CartEntity.addItem)
          .invoke(Item("thing", quantity))
      }

    val checkout =
      tool("checkout").describedAs("Checks a cart out").command(CartEntity.checkout, id = "cartId")

    if references.bookJob then
      tool("book_job")
        .describedAs("Books a job")
        .command(JobEntity.book, id = "jobId", input = "slot"): Unit
    references.getJob.foreach { description =>
      tool("get_job").describedAs(description).query(JobEntity.get, id = "jobId"): Unit
    }
    if references.todaysJobs then
      tool("todays_jobs").describedAs("The day's jobs").route("/jobs", "GET", "/jobs/{day}"): Unit

  /** What the refusal scenarios start: one MCP endpoint built as the scenario says. */
  final class ShopMcp(declare: ShopMcp => Unit) extends McpEndpoint("/mcp"):
    val acl: Acl               = Acl.AllowAll
    def declared(name: String) = tool(name)
    declare(this)

  /** An HTTP endpoint at `/` with one route, for the path clash. */
  final class RootEndpoint(method: String, path: String) extends HttpEndpoint("/"):
    val acl: Acl = Acl.AllowAll
    method match
      case "POST" => post(path)(() => "root")
      case _      => get(path)(() => "root")

  // ── The plumbers of client-keys.feature ──────────────────────────────────

  val PlumbingTools = Vector("list_jobs", "book_job", "reschedule_job", "close_books")

  /** Who each tool call was told it was for, in order: `(tool, principal)`. */
  val told = ConcurrentLinkedQueue[(String, Principal)]()

  def principalsTold: Vector[(String, Principal)] = told.asScala.toVector

  /** An MCP endpoint at `path` of `names`, each recording the principal it was told. */
  final class PlumbersMcp(at: String, names: Vector[String], val acl: Acl) extends McpEndpoint(at):
    names.foreach { name =>
      tool(name).describedAs(s"The plumbers' $name").handle { () =>
        val p = principal
        told.add(name -> p)
        record("tool", p.subject, name)
        s"$name for ${p.subject}"
      }: Unit
    }

  /** What a scenario asks a handler to issue: the expiry optional, so "none given" can be said. */
  final case class IssueBody(
      subject: String,
      expiry: Option[Expiry],
      endpoint: Option[String] = None,
      scope: Set[String] = Set.empty,
      attributes: Map[String, String] = Map.empty,
      label: Option[String] = None
  )
  given JsonValueCodec[IssueBody]                = Codecs.make
  given JsonValueCodec[IssuedClientKey]          = Codecs.make
  given JsonValueCodec[Vector[ClientKeySummary]] = Codecs.make

  /** A service's own routes over its client keys, as a product would write them. */
  final class KeysEndpoint(clients: EndpointClients) extends HttpEndpoint("/keys"):
    val acl: Acl = Acl.AllowAll
    postBody("/issue") { (body: IssueBody) =>
      clients.clientKeys.issueRequest(
        ClientKeyRequest(
          body.subject,
          body.expiry,
          body.endpoint,
          body.scope,
          body.attributes,
          body.label
        )
      )
    }
    get("/list")(() => clients.clientKeys.list())
    delete("/{id}") { (id: String) =>
      clients.clientKeys.revoke(id)
      "revoked"
    }

  /** The plumbers' endpoint behind client keys: the tools of the feature's background. */
  val plumbers: Seq[EndpointClients => HttpEndpoint] =
    Seq(
      _ => PlumbersMcp("/mcp", PlumbingTools, ClientKeys.authenticate()),
      clients => KeysEndpoint(clients)
    )

  /** Another service, `office`, with one tool behind its own client keys. */
  val office: Seq[EndpointClients => HttpEndpoint] =
    Seq(
      _ => PlumbersMcp("/mcp", Vector("file_invoice"), ClientKeys.authenticate()),
      clients => KeysEndpoint(clients)
    )

  // ── The orders of access.feature ─────────────────────────────────────────

  val OrderTools = Vector("list_orders", "cancel_order")

  /** Which caller each order tool was called by, in order: `(tool, caller)`. */
  val callersTold = ConcurrentLinkedQueue[(String, Caller)]()

  def callersOf: Vector[(String, Caller)] = callersTold.asScala.toVector

  /**
   * The orders' MCP endpoint behind `acl`, `cancel_order` behind its own when one is given. Each
   * tool records its caller, and its principal when the ACL established one.
   */
  final class OrdersMcp(val acl: Acl, cancelAcl: Option[Acl], signIn: Boolean = false)
      extends McpEndpoint("/mcp"):
    override def brokered: Boolean = signIn

    private def serve(name: String): String =
      val current = request
      current.principal.foreach(p => told.add(name -> p))
      callersTold.add(name -> current.caller)
      record("tool", current.principal.fold("-")(_.subject), name)
      s"$name served"

    tool("list_orders").describedAs("Lists the orders").handle(() => serve("list_orders")): Unit
    cancelAcl match
      case Some(own) =>
        tool("cancel_order")
          .describedAs("Cancels an order")
          .withAcl(own)
          .handle(() => serve("cancel_order")): Unit
      case None =>
        tool("cancel_order")
          .describedAs("Cancels an order")
          .handle(() => serve("cancel_order")): Unit

  /** billing's MCP endpoint behind `acl`: one tool, which records that it ran. */
  final class BillingMcp(val acl: Acl) extends McpEndpoint("/mcp"):
    tool("file_invoice").describedAs("Files an invoice").handle { () =>
      val current = request
      current.principal.foreach(p => told.add("file_invoice" -> p))
      record("tool", current.principal.fold("-")(_.subject), "file_invoice")
      "filed"
    }: Unit

  /** An MCP endpoint that states no ACL: what a Scala service gets by writing `null`. */
  final class NoAclMcp extends McpEndpoint("/mcp"):
    val acl: Acl = null
    tool("get_cart").describedAs("Reads one cart").handle(() => "c"): Unit
