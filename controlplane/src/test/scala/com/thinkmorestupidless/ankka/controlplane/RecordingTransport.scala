package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode, Metadata}
import com.thinkmorestupidless.ankka.sdk.{
  CallTransport,
  CommandHandle,
  ComponentClient,
  NoArgHandle
}

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*

/**
 * A transport for a consumer's unit test that records which entity each call went to, by component
 * and id, and answers from functions of the id and the decoded input. `TestTransport` answers by
 * handler alone, which cannot show that a change reached the right project.
 */
final class RecordingTransport extends CallTransport:

  /** One call: the component, the entity, the handler, the decoded input and the metadata. */
  final case class Call(
      componentId: String,
      entityId: String,
      method: String,
      input: Any,
      metadata: Metadata
  )

  private val calls   = ConcurrentLinkedQueue[Call]()
  private var answers = Map.empty[(String, String), (String, Array[Byte]) => (Any, Array[Byte])]

  def answer[C, I, O](handle: CommandHandle[C, I, O])(
      respond: (String, I) => O
  ): RecordingTransport =
    answers = answers.updated(
      (handle.componentId, handle.name),
      (entity, bytes) =>
        val input = handle.inputSerializer.fromBytes(bytes)
        (input, handle.outputSerializer.toBytes(respond(entity, input)))
    )
    this

  def answer[C, O](handle: NoArgHandle[C, O])(respond: String => O): RecordingTransport =
    answers = answers.updated(
      (handle.componentId, handle.name),
      (entity, _) => ((), handle.outputSerializer.toBytes(respond(entity)))
    )
    this

  def recorded: Vector[Call] = calls.asScala.toVector

  val askTimeout: scala.concurrent.duration.FiniteDuration =
    scala.concurrent.duration.FiniteDuration(5, "seconds")

  def client: ComponentClient = ComponentClient(this)

  def ask(
      componentId: com.thinkmorestupidless.ankka.core.ComponentId,
      entityId: com.thinkmorestupidless.ankka.core.EntityId,
      method: com.thinkmorestupidless.ankka.core.MethodName,
      payload: Array[Byte],
      metadata: Metadata
  ): Future[Array[Byte]] =
    answers.get((componentId, method)) match
      case None =>
        Future.failed(
          CommandError(s"$componentId#$method on '$entityId' is not answered", ErrorCode.NotFound)
        )
      case Some(respond) =>
        try
          val (input, reply) = respond(entityId, payload)
          calls.add(Call(componentId, entityId, method, input, metadata))
          Future.successful(reply)
        catch case error: CommandError => Future.failed(error)

  def tell(
      componentId: com.thinkmorestupidless.ankka.core.ComponentId,
      entityId: com.thinkmorestupidless.ankka.core.EntityId,
      message: Any
  ): Unit = throw CommandError("tell is not recorded", ErrorCode.NotFound)
