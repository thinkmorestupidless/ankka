package com.thinkmorestupidless.ankka.sidecar.wasm

import ankka.protocol.v1.client.*
import ankka.protocol.v1.payload as pb
import ankka.protocol.v1.wasm.{ConfigReply, ConfigRequest, StreamTokens}
import com.dylibso.chicory.runtime.{HostFunction, ImportValues, Instance, WasmFunctionHandle}
import com.dylibso.chicory.wasm.types.{FunctionType, ValType}
import com.thinkmorestupidless.ankka.core.PlatformVariables
import com.thinkmorestupidless.ankka.sidecar.ClientLogic
import org.slf4j.LoggerFactory

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Await, Future, Promise}
import scala.jdk.CollectionConverters.*

/**
 * The `ankka1` import module: what a guest calls the runtime for.
 *
 * Every import runs on the thread that called the export, which is a virtual thread, so awaiting
 * the runtime parks that thread and nothing else. The component calls, queries and timers are
 * `ClientLogic`'s, the same code a process reaches over gRPC; `ClientLogic` exists only once the
 * service does, so it is bound after start, and a module calling out before then (from discovery)
 * is answered that the runtime is not ready.
 *
 * `config` reads the runtime's own environment, which in a single container holds every variable
 * the descriptor set alongside the platform's own. So it answers only a name that is none of the
 * platform's: a model key, the database credentials, the secret key, the cluster's and the
 * runtime's own settings all read as absent. This is the read-time version of the split the
 * operator makes at render time for a process; both read `core`'s `PlatformVariables`.
 */
final class HostImports(
    commandTimeout: FiniteDuration,
    streamTimeout: FiniteDuration,
    env: String => Option[String] = sys.env.get
):
  import HostImports.*

  private val moduleLog                            = LoggerFactory.getLogger("ankka.module")
  @volatile private var logic: Option[ClientLogic] = None

  /** Called once the service has started and its client exists. */
  def bind(client: ClientLogic): Unit = logic = Some(client)

  /**
   * The imports, the same for every instance: each call is handed the instance that made it. Lazy,
   * because the functions it lists are defined below it.
   */
  lazy val values: ImportValues = ImportValues
    .builder()
    .addFunction(bytes("invoke")(invoke))
    .addFunction(bytes("send")(send))
    .addFunction(bytes("invoke_stream")(invokeStream))
    .addFunction(bytes("query")(query))
    .addFunction(bytes("schedule")(schedule))
    .addFunction(bytes("cancel")(cancel))
    .addFunction(bytes("config")(config))
    .addFunction(bytes("get_secret")(getSecret))
    .addFunction(bytes("put_secret")(putSecret))
    .addFunction(bytes("delete_secret")(deleteSecret))
    .addFunction(logFunction)
    .build()

  // ── The calls ──────────────────────────────────────────────────────────────

  private def client: ClientLogic =
    logic.getOrElse(throw IllegalStateException("the runtime is not ready to be called yet"))

  private def await[A](f: Future[A], timeout: FiniteDuration): A = Await.result(f, timeout)

  private def invoke(request: Array[Byte]): Array[Byte] =
    logic match
      case None =>
        InvokeReply(InvokeReply.Result.Error(notReady)).toByteArray
      case Some(c) => await(c.invoke(InvokeRequest.parseFrom(request)), commandTimeout).toByteArray

  /**
   * A command sent without waiting for its answer: dispatched, and the guest carries on at once. A
   * handler that never replies can only be called this way, since `invoke` waits for a reply. What
   * the call answers, a refusal included, is logged at debug and goes nowhere else.
   */
  private def send(request: Array[Byte]): Array[Byte] =
    val parsed = InvokeRequest.parseFrom(request)
    logic match
      case None =>
        moduleLog.warn("a send to {} before the runtime was ready was dropped", parsed.componentId)
      case Some(c) =>
        c.invoke(parsed)
          .foreach(reply =>
            reply.result.error.foreach(e =>
              moduleLog.debug("a send to {} was refused: {}", parsed.componentId, e.message)
            )
          )(using scala.concurrent.ExecutionContext.parasitic)
    Array.emptyByteArray

  /** A streaming reply, delivered whole: every token, the last one completed or failed. */
  private def invokeStream(request: Array[Byte]): Array[Byte] =
    logic match
      case None =>
        StreamTokens(Seq(StreamToken(StreamToken.Token.Failed(notReady)))).toByteArray
      case Some(c) =>
        val tokens = Vector.newBuilder[StreamToken]
        val done   = Promise[Unit]()
        c.invokeStream(
          InvokeRequest.parseFrom(request),
          token =>
            tokens.synchronized(tokens += token): Unit
            if token.token.isCompleted || token.token.isFailed then done.trySuccess(()): Unit
        )
        await(done.future, streamTimeout)
        StreamTokens(tokens.synchronized(tokens.result())).toByteArray

  private def query(request: Array[Byte]): Array[Byte] =
    logic match
      case None    => QueryReply(QueryReply.Result.Error(notReady)).toByteArray
      case Some(c) => await(c.query(QueryRequest.parseFrom(request)), commandTimeout).toByteArray

  /** A timer that cannot be set is a fault of the call that tried, not a silent success. */
  private def schedule(request: Array[Byte]): Array[Byte] =
    await(client.schedule(ScheduleRequest.parseFrom(request)), commandTimeout).toByteArray

  private def cancel(request: Array[Byte]): Array[Byte] =
    await(client.cancel(CancelRequest.parseFrom(request)), commandTimeout).toByteArray

  // The secret store (protocol 1.6). A refusal is the reply's `Error`, as for `invoke`.

  private def getSecret(request: Array[Byte]): Array[Byte] =
    logic match
      case None => GetSecretReply(GetSecretReply.Result.Error(notReady)).toByteArray
      case Some(c) =>
        await(c.getSecret(GetSecretRequest.parseFrom(request)), commandTimeout).toByteArray

  private def putSecret(request: Array[Byte]): Array[Byte] =
    logic match
      case None => PutSecretReply(Some(notReady)).toByteArray
      case Some(c) =>
        await(c.putSecret(PutSecretRequest.parseFrom(request)), commandTimeout).toByteArray

  private def deleteSecret(request: Array[Byte]): Array[Byte] =
    logic match
      case None => DeleteSecretReply(Some(notReady)).toByteArray
      case Some(c) =>
        await(c.deleteSecret(DeleteSecretRequest.parseFrom(request)), commandTimeout).toByteArray

  private def config(request: Array[Byte]): Array[Byte] =
    ConfigReply(lookup(ConfigRequest.parseFrom(request).name)).toByteArray

  /** A descriptor variable, or nothing: a reserved name reads as unset whether or not it is set. */
  def lookup(name: String): Option[String] =
    if PlatformVariables.withheldFromModule(name) then None else env(name)

  // ── The ABI's plumbing ─────────────────────────────────────────────────────

  /** An import taking a request's bytes and answering bytes the guest allocates and then owns. */
  private def bytes(name: String)(answer: Array[Byte] => Array[Byte]): HostFunction =
    new HostFunction(
      Abi.ImportModule,
      name,
      FunctionType.of(List(ValType.I32, ValType.I32).asJava, List(ValType.I64).asJava),
      new WasmFunctionHandle:
        def apply(instance: Instance, args: Long*): Array[Long] =
          val request = instance.memory().readBytes(args(0).toInt, args(1).toInt)
          Array(give(instance, answer(request)))
    )

  private val logFunction: HostFunction = new HostFunction(
    Abi.ImportModule,
    "log",
    FunctionType.of(List(ValType.I32, ValType.I32, ValType.I32).asJava, List.empty[ValType].asJava),
    new WasmFunctionHandle:
      def apply(instance: Instance, args: Long*): Array[Long] =
        val text = instance.memory().readString(args(1).toInt, args(2).toInt)
        args(0).toInt match
          case 0 => moduleLog.trace(text)
          case 1 => moduleLog.debug(text)
          case 2 => moduleLog.info(text)
          case 3 => moduleLog.warn(text)
          case _ => moduleLog.error(text)
        Array.emptyLongArray
  )

object HostImports:

  private val notReady =
    pb.Error("the runtime is not ready to be called yet", pb.ErrorCode.UNAVAILABLE)

  /**
   * Writes `reply` into a buffer the guest allocates, and answers its packed pointer and length.
   */
  def give(instance: Instance, reply: Array[Byte]): Long =
    if reply.isEmpty then 0L
    else
      val ptr = instance.`export`(Abi.Prefix + "alloc").apply(reply.length.toLong)(0).toInt
      instance.memory().write(ptr, reply)
      Abi.pack(ptr, reply.length)
