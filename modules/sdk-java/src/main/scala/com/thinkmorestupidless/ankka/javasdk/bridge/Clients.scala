package com.thinkmorestupidless.ankka.javasdk.bridge

import com.thinkmorestupidless.ankka.core.{
  CommandError,
  ComponentId,
  EntityId,
  Metadata,
  MethodName
}
import com.thinkmorestupidless.ankka.javasdk.client.*
import com.thinkmorestupidless.ankka.javasdk.codec.{RecordCodec, Serializer}
import com.thinkmorestupidless.ankka.sdk.{CallTransport, ComponentClient as ScalaClient}

import java.time.Duration
import java.util.concurrent.{CompletionException, CompletionStage}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.FutureConverters.*

/** The Java component client over the Scala one's transport. */
private[ankka] object Clients:

  def of(scala: ScalaClient, refs: Refs): ComponentClient =
    JavaComponentClient(scala.transportRef, refs)

  /** The client of an instance built outside a host, which answers nothing. */
  val unavailable: ComponentClient = new ComponentClient:
    def forEventSourcedEntity(entityId: String): EntityRef = refuse
    def forKeyValueEntity(entityId: String): EntityRef     = refuse
    private def refuse =
      throw IllegalStateException("this component was not built by a service or a test kit")

  /** A rejection or a failure as a Java caller sees it. */
  private[bridge] def javaFailure(t: Throwable): Throwable = t match
    case e: CompletionException if e.getCause != null => javaFailure(e.getCause)
    case e: CommandError                              => CommandException(Effects.toProblem(e))
    case other                                        => other

private final class JavaComponentClient(transport: CallTransport, refs: Refs)
    extends ComponentClient:
  def forEventSourcedEntity(entityId: String): EntityRef = JavaEntityRef(transport, refs, entityId)
  def forKeyValueEntity(entityId: String): EntityRef     = JavaEntityRef(transport, refs, entityId)

private final class JavaEntityRef(transport: CallTransport, refs: Refs, entityId: String)
    extends EntityRef:

  def call[C, I, O](handler: CommandRef[C, I, O]): Invocation[I, O] =
    val (component, plan) = refs.resolve(handler)
    JavaInvocation(
      transport,
      Target(component.componentId, plan.wireName, plan.readOnly, plan.input, plan.output),
      entityId
    )

  def call[C, O](handler: NoArgRef[C, O]): Invocation[Void, O] =
    val (component, plan) = refs.resolve(handler)
    JavaInvocation(
      transport,
      Target(component.componentId, plan.wireName, plan.readOnly, None, plan.output),
      entityId
    )

  def call[I, O](
      componentId: String,
      wireName: String,
      input: Class[I],
      output: Class[O]
  ): Invocation[I, O] =
    val in =
      if input == classOf[Void] then None
      else Some(RecordCodec.forType(input).asInstanceOf[Serializer[Any]])
    JavaInvocation(
      transport,
      Target(
        componentId,
        wireName,
        readOnly = false,
        in,
        RecordCodec.forType(output).asInstanceOf[Serializer[Any]]
      ),
      entityId
    )

/** Where a call goes and how its input and reply cross. */
private final case class Target(
    componentId: String,
    wireName: String,
    readOnly: Boolean,
    input: Option[Serializer[Any]],
    output: Serializer[Any]
)

private final case class JavaInvocation[I, O](
    transport: CallTransport,
    target: Target,
    entityId: String,
    timeout: Option[FiniteDuration] = None,
    metadata: Metadata = Metadata.empty
) extends Invocation[I, O]:

  def invoke(input: I): O =
    try ComponentClientAwait(future(input), timeout.getOrElse(transport.askTimeout)).asInstanceOf[O]
    catch case t: Throwable => throw Clients.javaFailure(t)

  def invokeAsync(input: I): CompletionStage[O] =
    future(input)
      .transform(identity, Clients.javaFailure)(using ExecutionContext.parasitic)
      .asInstanceOf[Future[O]]
      .asJava

  def withTimeout(timeout: Duration): Invocation[I, O] =
    copy(timeout = Some(Effects.finite(timeout)))

  def withMetadata(key: String, value: String): Invocation[I, O] =
    copy(metadata = metadata.add(key, value))

  private def future(input: I): Future[Any] =
    val bytes = target.input match
      case Some(serializer) => serializer.toBytes(input)
      case None             => Array.emptyByteArray
    transport
      .askHandler(
        target.readOnly,
        ComponentId(target.componentId),
        EntityId(entityId),
        MethodName(target.wireName),
        bytes,
        metadata
      )
      .map(target.output.fromBytes)(using ExecutionContext.parasitic)

private object ComponentClientAwait:
  def apply(future: Future[Any], timeout: FiniteDuration): Any =
    ScalaClient.await(future, timeout)
