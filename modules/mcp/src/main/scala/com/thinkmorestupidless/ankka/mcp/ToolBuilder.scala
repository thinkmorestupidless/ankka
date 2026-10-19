package com.thinkmorestupidless.ankka.mcp

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromString}
import com.thinkmorestupidless.ankka.agent.{Json, SchemaType, ToolOutput, ToolSpec}
import com.thinkmorestupidless.ankka.agent.autonomous.JsonSchema
import com.thinkmorestupidless.ankka.core.{CommandError, EntityId, Metadata}
import com.thinkmorestupidless.ankka.http.{Acl, HttpEndpoint, HttpProblem, PathTemplate, Route}
import com.thinkmorestupidless.ankka.runtime.Trace
import com.thinkmorestupidless.ankka.sdk.{CallTransport, CommandHandle, NoArgHandle}

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

/**
 * Declares one tool of an MCP endpoint: its description, its ACL when it has one of its own, and
 * either its parameters and a handler, or a reference to a command, a query or a route the service
 * already has. Every way of finishing a declaration registers the tool with its endpoint, in the
 * order declared.
 *
 * A tool with no description still builds, so the start can refuse it naming the tool rather than a
 * constructor throwing somewhere in the middle of the endpoint's body.
 */
final class ToolBuilder private[mcp] (
    private[mcp] val name: String,
    private[mcp] val description: String,
    private[mcp] val acl: Option[Acl],
    private val register: McpTool => McpTool,
    private[mcp] val bindings: Reference.Bindings
):

  /** What the tool does: all a client's model has to decide whether to call it. */
  def describedAs(description: String): Described =
    ToolBuilder(name, description, acl, register, bindings)

  /**
   * The tool answers to `acl` after the endpoint's: a request the endpoint admitted, this refuses.
   */
  def withAcl(acl: Acl): Described = ToolBuilder(name, description, Some(acl), register, bindings)

  def param[A: SchemaType](paramName: String, paramDescription: String): Params1[A] =
    Params1(this, Vector(Param.of[A](paramName, paramDescription)))

  /** A tool taking no arguments. */
  def handle[R: ToolOutput](f: () => R): McpTool =
    Params.build(this, Vector.empty, _ => f())

  /** The tool's arguments as one case class, described by its schema and read by its codec. */
  def arguments[A](using schema: JsonSchema[A], codec: JsonValueCodec[A]): Arguments[A] =
    Arguments(this, schema, codec)

  // References (feature 054, US6): a command, a query or a route the service has, offered as a tool.

  /**
   * A command of an entity or a workflow, its id from the argument `id`, its input's fields beside
   * it.
   */
  def command[C, I, O](handle: CommandHandle[C, I, O], id: String)(using
      input: ToolInput[I],
      out: ToolOutput[O]
  ): McpTool = Reference.handler(this, handle, id, input, readOnly = false)

  /** A command whose input is a scalar, under the argument `input`. */
  def command[C, I, O](handle: CommandHandle[C, I, O], id: String, input: String)(using
      schema: SchemaType[I],
      out: ToolOutput[O]
  ): McpTool = Reference.handler(this, handle, id, ToolInput.named[I](input), readOnly = false)

  /** A command that takes nothing but the id. */
  def command[C, O](handle: NoArgHandle[C, O], id: String)(using out: ToolOutput[O]): McpTool =
    Reference.noArg(this, handle, id, readOnly = false)

  /**
   * A query of an entity, a view row or a workflow: as `command`, for a handler that only reads.
   */
  def query[C, I, O](handle: CommandHandle[C, I, O], id: String)(using
      input: ToolInput[I],
      out: ToolOutput[O]
  ): McpTool = Reference.handler(this, handle, id, input, readOnly = true)

  def query[C, I, O](handle: CommandHandle[C, I, O], id: String, input: String)(using
      schema: SchemaType[I],
      out: ToolOutput[O]
  ): McpTool = Reference.handler(this, handle, id, ToolInput.named[I](input), readOnly = true)

  def query[C, O](handle: NoArgHandle[C, O], id: String)(using out: ToolOutput[O]): McpTool =
    Reference.noArg(this, handle, id, readOnly = true)

  /**
   * A route of one of the service's HTTP endpoints: `path` is the route's whole path as declared,
   * prefix and template (`/jobs/{day}`). Its path names are the tool's arguments, and a `body`
   * argument is the request's body, as JSON.
   */
  def route(endpoint: HttpEndpoint, method: String, path: String): McpTool =
    Reference.route(this, endpoint.prefix, method, path)

  /** As `route`, naming the endpoint by its prefix. */
  def route(prefix: String, method: String, path: String): McpTool =
    Reference.route(this, prefix, method, path)

  /**
   * A tool whose schema is given as it is, and whose arguments are checked against it before `run`
   * (`RawArguments`): what the sidecar declares for a tool a process runs. `run` answers a tool
   * error as `Left`, and a throw is one too.
   */
  private[ankka] def raw(inputSchema: Json)(run: ToolCall => Either[String, ToolAnswer]): McpTool =
    finish(
      inputSchema,
      ToolKind.Handler,
      call =>
        RawArguments.problems(inputSchema, call.arguments).headOption match
          case Some(refused) => ToolOutcome.BadArguments(refused)
          case None =>
            try run(call).fold(ToolOutcome.Failed(_), ToolOutcome.Answered(_))
            catch
              case scala.util.control.NonFatal(failure) =>
                ToolOutcome.Failed(Params.reasonOf(failure))
    )

  private[mcp] def finish(
      inputSchema: Json,
      kind: ToolKind,
      run: ToolCall => ToolOutcome
  ): McpTool =
    register(McpTool(ToolSpec(name, description, inputSchema), acl, kind, run))

/** A tool with its description: what `describedAs` answers. */
type Described = ToolBuilder

/** One declared parameter. */
private[mcp] final case class Param(
    name: String,
    description: String,
    schema: Json,
    required: Boolean,
    decode: Option[Json] => Either[String, Any]
)

private[mcp] object Param:
  def of[A](name: String, description: String)(using schema: SchemaType[A]): Param =
    Param(name, description, schema.schema, schema.required, json => schema.decode(json))

final class Params1[A] private[mcp] (builder: ToolBuilder, params: Vector[Param]):
  def param[B: SchemaType](paramName: String, paramDescription: String): Params2[A, B] =
    Params2(builder, params :+ Param.of[B](paramName, paramDescription))
  def handle[R: ToolOutput](f: A => R): McpTool =
    Params.build(builder, params, values => f(values(0).asInstanceOf[A]))

final class Params2[A, B] private[mcp] (builder: ToolBuilder, params: Vector[Param]):
  def param[C: SchemaType](paramName: String, paramDescription: String): Params3[A, B, C] =
    Params3(builder, params :+ Param.of[C](paramName, paramDescription))
  def handle[R: ToolOutput](f: (A, B) => R): McpTool =
    Params.build(builder, params, v => f(v(0).asInstanceOf[A], v(1).asInstanceOf[B]))

final class Params3[A, B, C] private[mcp] (builder: ToolBuilder, params: Vector[Param]):
  def handle[R: ToolOutput](f: (A, B, C) => R): McpTool =
    Params.build(
      builder,
      params,
      v => f(v(0).asInstanceOf[A], v(1).asInstanceOf[B], v(2).asInstanceOf[C])
    )

final class Arguments[A] private[mcp] (
    builder: ToolBuilder,
    schema: JsonSchema[A],
    codec: JsonValueCodec[A]
):
  def handle[R](f: A => R)(using out: ToolOutput[R]): McpTool =
    builder.finish(
      schema.schema,
      ToolKind.Handler,
      call =>
        Params.argumentsOf(call) match
          case Left(refused) => ToolOutcome.BadArguments(refused)
          case Right(arguments) =>
            val decoded =
              try Right(readFromString(arguments.render)(using codec))
              catch
                case NonFatal(failure) =>
                  Left(Option(failure.getMessage).map(_.linesIterator.next()).getOrElse("invalid"))
            decoded match
              case Left(refused) => ToolOutcome.BadArguments(refused)
              case Right(value)  => Params.answering(f(value))
    )

private[mcp] object Params:

  def build[R](builder: ToolBuilder, params: Vector[Param], apply: Vector[Any] => R)(using
      out: ToolOutput[R]
  ): McpTool =
    builder.finish(
      schemaOf(
        params.map(p => p.name -> describe(p.schema, p.description)),
        params.collect {
          case p if p.required => p.name
        }
      ),
      ToolKind.Handler,
      call =>
        argumentsOf(call).flatMap(arguments =>
          params.foldLeft[Either[String, Vector[Any]]](Right(Vector.empty)) { (acc, param) =>
            for
              soFar <- acc
              value <- param
                .decode(arguments(param.name))
                .left
                .map(reason => s"parameter '${param.name}': $reason")
            yield soFar :+ value
          }
        ) match
          case Left(refused) => ToolOutcome.BadArguments(refused)
          case Right(values) => answering(apply(values))
    )

  /** The object schema of a tool's arguments; closed, so a model does not invent fields. */
  def schemaOf(properties: Vector[(String, Json)], required: Vector[String]): Json =
    Json.obj(
      "type"                 -> Json.str("object"),
      "properties"           -> Json.Obj(properties.toMap),
      "required"             -> Json.Arr(required.map(Json.str)),
      "additionalProperties" -> Json.bool(false)
    )

  def describe(schema: Json, description: String): Json = schema match
    case Json.Obj(fields) if description.nonEmpty =>
      Json.Obj(fields + ("description" -> Json.str(description)))
    case other => other

  /** The arguments a client sent: an object, or nothing, which is an empty one. */
  def argumentsOf(call: ToolCall): Either[String, Json.Obj] = call.arguments match
    case obj: Json.Obj => Right(obj)
    case Json.Null     => Right(new Json.Obj(Map.empty))
    case other         => Left(s"arguments must be an object, not ${other.render}")

  /** Runs a handler, turning what it answered or how it refused into the tool's outcome. */
  def answering[R](result: => R)(using out: ToolOutput[R]): ToolOutcome =
    try ToolOutcome.Answered(answerOf(result))
    catch case NonFatal(failure) => ToolOutcome.Failed(reasonOf(failure))

  def answerOf[R](value: R)(using out: ToolOutput[R]): ToolAnswer =
    val text = out.render(value)
    // A string is prose: never structured, even when it happens to look like JSON.
    val structured =
      if out eq ToolOutput.string then None
      else Json.parse(text).toOption.collect { case obj: Json.Obj => obj }
    ToolAnswer(text, structured)

  /** What a refusal says: a command's, a route's, or whatever the failure's message is. */
  def reasonOf(failure: Throwable): String = failure match
    case refusal: CommandError => refusal.message
    case problem: HttpProblem  => problem.message
    case _: java.util.concurrent.TimeoutException =>
      "the tool did not answer in time"
    case other => Option(other.getMessage).getOrElse(other.getClass.getSimpleName)

/**
 * A tool declared by reference, run against what it refers to once the endpoint is mounted:
 * `McpEndpoint.problems` resolves the reference and binds it. Called before that, it answers a tool
 * error saying so rather than throwing.
 */
private[mcp] object Reference:

  /** What a command or query reference calls through, bound at mount. */
  final class Bound[A]:
    private val held         = AtomicReference[A]()
    def bind(value: A): Unit = held.set(value)
    def get: Option[A]       = Option(held.get())

  final case class Transport(transport: CallTransport, timeout: FiniteDuration)

  /**
   * Every reference's binding in one endpoint, keyed by the tool, so the endpoint binds what it
   * resolved. Declaration is the endpoint's constructor, which is single-threaded.
   */
  final class Bindings:
    val transports = scala.collection.mutable.Map.empty[String, Bound[Transport]]
    val routes     = scala.collection.mutable.Map.empty[String, Bound[Route]]

  private def transportFor(builder: ToolBuilder): Bound[Transport] =
    val bound = Bound[Transport]()
    builder.bindings.transports.update(builder.name, bound)
    bound

  private def routeFor(builder: ToolBuilder): Bound[Route] =
    val bound = Bound[Route]()
    builder.bindings.routes.update(builder.name, bound)
    bound

  private def unbound(tool: String) =
    ToolOutcome.Failed(s"tool '$tool' refers to something this service has not mounted")

  def handler[C, I, O](
      builder: ToolBuilder,
      handle: CommandHandle[C, I, O],
      id: String,
      input: ToolInput[I],
      readOnly: Boolean
  )(using out: ToolOutput[O]): McpTool =
    val bound = transportFor(builder)
    val properties = (id -> Json.obj(
      "type"        -> Json.str("string"),
      "description" -> Json.str(s"The id of the ${handle.componentId}")
    )) +: input.schema.filterNot(_._1 == id)
    builder.finish(
      Params.schemaOf(properties, id +: input.required.filterNot(_ == id)),
      kindOf(handle.componentId.toString, handle.name.toString, readOnly),
      call =>
        bound.get match
          case None => unbound(builder.name)
          case Some(Transport(transport, timeout)) =>
            idAndArguments(call, id).flatMap((entity, arguments) =>
              input.decode(arguments).map(entity -> _)
            ) match
              case Left(refused) => ToolOutcome.BadArguments(refused)
              case Right((entity, value)) =>
                Params.answering(
                  handle.outputSerializer.fromBytes(
                    awaited(
                      transport.askHandler(
                        handle.readOnly,
                        handle.componentId,
                        entity,
                        handle.name,
                        handle.inputSerializer.toBytes(value),
                        Trace.outbound(Metadata.empty)
                      ),
                      timeout
                    )
                  )
                )
    )

  def noArg[C, O](builder: ToolBuilder, handle: NoArgHandle[C, O], id: String, readOnly: Boolean)(
      using out: ToolOutput[O]
  ): McpTool =
    val bound = transportFor(builder)
    builder.finish(
      Params.schemaOf(
        Vector(id -> Json.obj("type" -> Json.str("string"))),
        Vector(id)
      ),
      kindOf(handle.componentId.toString, handle.name.toString, readOnly),
      call =>
        bound.get match
          case None => unbound(builder.name)
          case Some(Transport(transport, timeout)) =>
            idAndArguments(call, id) match
              case Left(refused) => ToolOutcome.BadArguments(refused)
              case Right((entity, _)) =>
                Params.answering(
                  handle.outputSerializer.fromBytes(
                    awaited(
                      transport.askHandler(
                        handle.readOnly,
                        handle.componentId,
                        entity,
                        handle.name,
                        Array.emptyByteArray,
                        Trace.outbound(Metadata.empty)
                      ),
                      timeout
                    )
                  )
                )
    )

  def route(builder: ToolBuilder, prefix: String, method: String, path: String): McpTool =
    val bound    = routeFor(builder)
    val template = PathTemplate.parse(path)
    val names    = template.parameterNames
    val withBody = method == "POST" || method == "PUT" || method == "PATCH"
    val properties = names.map(n => n -> Json.obj("type" -> Json.str("string"))) ++
      Option.when(withBody)("body" -> Json.obj("type" -> Json.str("object")))
    builder.finish(
      Params.schemaOf(properties, names),
      ToolKind.Route(prefix, method.toUpperCase, path),
      call =>
        bound.get match
          case None => unbound(builder.name)
          case Some(route) =>
            Params.argumentsOf(call).flatMap { arguments =>
              names
                .foldLeft[Either[String, Vector[String]]](Right(Vector.empty)) { (acc, n) =>
                  acc.flatMap(soFar =>
                    arguments(n) match
                      case Some(Json.Str(value)) => Right(soFar :+ value)
                      case Some(Json.Num(value)) =>
                        Right(
                          soFar :+ (if value.isWhole then value.toLong.toString else value.toString)
                        )
                      case Some(other) =>
                        Left(s"parameter '$n': expected a string but got ${other.render}")
                      case None => Left(s"argument '$n' is required")
                  )
                }
                .map(args => args -> arguments("body").map(_.render).getOrElse(""))
            } match
              case Left(refused) => ToolOutcome.BadArguments(refused)
              case Right((args, body)) =>
                try
                  val response = route.run(args, body.getBytes("UTF-8"))
                  val text     = String(response.body, "UTF-8")
                  if response.status >= 400 then ToolOutcome.Failed(errorOf(text))
                  else
                    ToolOutcome.Answered(
                      ToolAnswer(
                        text,
                        Json.parse(text).toOption.collect { case obj: Json.Obj => obj }
                      )
                    )
                catch case NonFatal(failure) => ToolOutcome.Failed(Params.reasonOf(failure))
    )

  /** A route's refusal says why in its `error` field; anything else is the body as it came. */
  private def errorOf(body: String): String =
    Json.parse(body).toOption.flatMap(_("error")).flatMap(_.asString).getOrElse(body)

  private def kindOf(component: String, handler: String, readOnly: Boolean): ToolKind =
    if readOnly then ToolKind.Query(component, handler) else ToolKind.Command(component, handler)

  private def idAndArguments(call: ToolCall, id: String): Either[String, (EntityId, Json.Obj)] =
    Params.argumentsOf(call).flatMap { arguments =>
      arguments(id) match
        case Some(Json.Str(value)) if value.nonEmpty =>
          EntityId.parse(value).left.map(reason => s"parameter '$id': $reason").map(_ -> arguments)
        case Some(other) => Left(s"parameter '$id': expected a string but got ${other.render}")
        case None        => Left(s"argument '$id' is required")
    }

  private def awaited(future: Future[Array[Byte]], timeout: FiniteDuration): Array[Byte] =
    scala.concurrent.blocking(Await.result(future, timeout))
