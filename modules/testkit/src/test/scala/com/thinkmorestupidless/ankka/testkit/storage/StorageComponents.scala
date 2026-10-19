package com.thinkmorestupidless.ankka.testkit.storage

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.agent.{Agent, AgentContext, FunctionTool}
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.sdk.*

import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.duration.*

/**
 * The service every in-process scenario of `features/storage-client` drives (feature 055): an
 * endpoint over the storage client, an entity that keeps an object's name, and a workflow, a
 * consumer, a timed action and an agent that each keep an object through their own context.
 */
final case class KeepObject(bucket: Option[String], name: String, contentType: String, text: String)

final case class Described(name: String, contentType: String, length: Long, lastKept: String)

final case class ListedObject(name: String, length: Long, lastKept: String)

final case class Listed(objects: Vector[ListedObject])

final case class RecordPhoto(name: String, contentType: String, text: String)

object StorageCodecs:
  given JsonValueCodec[KeepObject]  = Codecs.make[KeepObject]
  given JsonValueCodec[Described]   = Codecs.make[Described]
  given JsonValueCodec[Listed]      = Codecs.make[Listed]
  given JsonValueCodec[RecordPhoto] = Codecs.make[RecordPhoto]
  given Serializer[KeepObject]      = Codecs.serializer[KeepObject]("keep-object")

import StorageCodecs.given

/** Where a bucket is chosen by name: the unnamed one when the request names none. */
private def chosen(storage: ObjectStorage, bucket: Option[String]): Bucket =
  bucket.fold(storage.default)(storage.bucket)

private def keep(storage: ObjectStorage, request: KeepObject): Unit =
  chosen(storage, request.bucket).putBytes(
    request.name,
    request.text.getBytes(UTF_8),
    request.contentType
  )

// docs:start entity
/** What a listing records about a photo: its name in the bucket, never its bytes. */
final case class PhotoRecorded(name: String)

final case class ListingState(photos: Vector[String])

final class Listing extends EventSourcedEntity[ListingState, PhotoRecorded]:
  def emptyState: ListingState = ListingState(Vector.empty)

  def applyEvent(event: PhotoRecorded): ListingState =
    currentState.copy(photos = currentState.photos :+ event.name)

  def recordPhoto(name: String): Effect[Done] =
    effects.persist(PhotoRecorded(name)).thenReply(_ => Done)

  def photos: ReadOnlyEffect[ListingState] = effects.reply(currentState)

private val listingSerializer: Serializer[ListingState] = Codecs.serializer[ListingState]("listing")

object Listing
    extends EventSourcedEntity.Companion[Listing, ListingState, PhotoRecorded](
      componentId = ComponentId("listing"),
      stateSerializer = listingSerializer,
      eventSerializer = Codecs.serializer[PhotoRecorded]("photo-recorded")
    ):
  given Serializer[ListingState]                 = listingSerializer
  def create(context: EventSourcedEntityContext) = new Listing

  val recordPhoto = command("record-photo")(_.recordPhoto)
  val photos      = query("photos")(_.photos)
// docs:end entity

// docs:start endpoint
/** An endpoint over the service's buckets: keep, read, list, describe, delete, sign and address. */
final class PhotosEndpoint(clients: EndpointClients) extends HttpEndpoint("/storage"):

  val acl: Acl = Acl.AllowAll

  private def bucket: Bucket = chosen(clients.storage, query.optional[String]("bucket"))

  postBody("/objects") { (request: KeepObject) =>
    keep(clients.storage, request)
    Done: Done
  }

  get("/objects") { () =>
    val name = query.required[String]("name")
    bucket
      .getBytes(name)
      .fold(throw HttpProblem.notFound(s"no object '$name'"))((contentType, body) =>
        Bytes(contentType, body)
      )
  }

  get("/stat") { () =>
    val name = query.required[String]("name")
    bucket
      .stat(name)
      .fold(throw HttpProblem.notFound(s"no object '$name'"))(info =>
        Described(info.name, info.contentType, info.length, info.lastKept.toString)
      )
  }

  get("/list") { () =>
    Listed(
      bucket
        .list(query.optional[String]("prefix").getOrElse(""))
        .map(e => ListedObject(e.name, e.length, e.lastKept.toString))
        .toVector
    )
  }

  delete("/objects") { () =>
    bucket.delete(query.required[String]("name"))
    Done: Done
  }

  get("/signed") { () =>
    val access = query.required[String]("access") match
      case "keep" => SignedAccess.Keep
      case _      => SignedAccess.Read
    val span = query.optional[Int]("seconds").fold(ObjectRules.DefaultSignedUrl)(_.seconds)
    bucket.signedUrl(query.required[String]("name"), access, span).toString
  }

  get("/url") { () =>
    bucket.url(query.required[String]("name")).toString
  }

  // The entity keeps the name; the bucket keeps the object.
  postBody("/listings/{id}/photos") { (id: String, request: RecordPhoto) =>
    clients.storage.default.putBytes(
      request.name,
      request.text.getBytes(UTF_8),
      request.contentType
    )
    clients.componentClient
      .forEventSourcedEntity(EntityId(id))
      .call(Listing.recordPhoto)
      .invoke(request.name)
  }
// docs:end endpoint

  /** A keep from a stream that ends at half the length it declares: the store must keep nothing. */
  postBody("/objects/short") { (request: KeepObject) =>
    val body = request.text.getBytes(UTF_8)
    chosen(clients.storage, request.bucket).put(
      request.name,
      java.io.ByteArrayInputStream(body.take(body.length / 2)),
      body.length.toLong,
      request.contentType
    )
    Done: Done
  }

/** What a keeping workflow did: the object it kept, or why it could not. */
final case class KeepingState(kept: String, refused: String)

/** Keeps an object in a step, and tries to in a command handler, which is refused. */
final class KeepingWorkflow(context: WorkflowContext) extends Workflow[KeepingState]:

  def emptyState: KeepingState = KeepingState("", "")

  def start(request: KeepObject): Effect[Done] =
    effects
      .updateState(emptyState)
      .transitionTo(KeepingWorkflow.keepStep.withInput(request))
      .thenReply(Done)

  def keepInStep(request: KeepObject): StepEffect =
    keep(context.storage, request)
    stepEffects.updateState(currentState.copy(kept = request.name)).thenEnd

  /** A command that keeps an object, which a workflow may not do outside a step. */
  def keepInCommand(request: KeepObject): Effect[Done] =
    keep(context.storage, request)
    effects.reply(Done)

  def status: ReadOnlyEffect[KeepingState] = effects.reply(currentState)

object KeepingWorkflow
    extends Workflow.Companion[KeepingWorkflow, KeepingState](
      componentId = ComponentId("keeping"),
      stateSerializer = Codecs.serializer[KeepingState]("keeping-state")
    ):
  def create(context: WorkflowContext) = new KeepingWorkflow(context)

  val keepStep      = step("keep")(_.keepInStep)
  val start         = command("start")(_.start)
  val keepInCommand = command("keep-in-command")(_.keepInCommand)
  val status        = query("status")(_.status)

/** A request to keep an object, recorded so a consumer keeps it. */
final case class KeepRequested(request: KeepObject)

final class KeepRequests extends EventSourcedEntity[Int, KeepRequested]:
  def emptyState: Int                       = 0
  def applyEvent(event: KeepRequested): Int = currentState + 1
  def request(request: KeepObject): Effect[Done] =
    effects.persist(KeepRequested(request)).thenReply(_ => Done)

object KeepRequests
    extends EventSourcedEntity.Companion[KeepRequests, Int, KeepRequested](
      componentId = ComponentId("keep-requests"),
      stateSerializer = Codecs.serializer[Int]("keep-requests"),
      eventSerializer = Codecs.serializer[KeepRequested]("keep-requested")
    ):
  def create(context: EventSourcedEntityContext) = new KeepRequests
  val request                                    = command("request")(_.request)

/** Keeps every requested object, before the change is done with. */
final class KeepingConsumer(context: ConsumerContext) extends Consumer[KeepRequested, Nothing]:
  def onMessage(event: KeepRequested): Effect =
    keep(context.storage, event.request)
    effects.ignore()

object KeepingConsumer
    extends Consumer.Companion[KeepingConsumer, KeepRequested, Nothing](
      componentId = ComponentId("keeping-consumer"),
      source = ChangeSource.eventsOf(KeepRequests)
    ):
  def create(context: ConsumerContext) = new KeepingConsumer(context)

/** Keeps an object when its timer fires. */
final class KeepingTimedAction(context: TimedActionContext) extends TimedAction:
  def keepNow(request: KeepObject): Effect =
    keep(context.storage, request)
    effects.done()

object KeepingTimedAction
    extends TimedAction.Companion[KeepingTimedAction](ComponentId("keeping-timer")):
  def create(context: TimedActionContext) = new KeepingTimedAction(context)
  val keepNow                             = handler("keep")(_.keepNow)

/** An agent with a tool that keeps a caption as an object. */
final class CaptionAgent(context: AgentContext) extends Agent:

  private val keepCaption = FunctionTool
    .named("keep_caption")
    .describedAs("Keep a caption under a name in the service's bucket.")
    .param[String]("name", "The object's name.")
    .handle { (name: String) =>
      context.storage.default.putBytes(name, s"a caption for $name".getBytes(UTF_8), "text/plain")
      "kept"
    }

  def ask(question: String): Effect[String] =
    effects
      .systemMessage("Keep captions when asked.")
      .userMessage(question)
      .tools(keepCaption)
      .thenReply()

object CaptionAgent extends Agent.Companion[CaptionAgent](ComponentId("caption-agent")):
  def create(context: AgentContext) = new CaptionAgent(context)
  val ask                           = command("ask")(_.ask)
