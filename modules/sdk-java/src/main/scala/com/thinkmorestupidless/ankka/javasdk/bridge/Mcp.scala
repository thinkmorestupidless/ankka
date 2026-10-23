package com.thinkmorestupidless.ankka.javasdk.bridge

import com.thinkmorestupidless.ankka.agent.{Json, ToolOutput}
import com.thinkmorestupidless.ankka.core.{CommandError, ComponentId, MethodName}
import com.thinkmorestupidless.ankka.http as shttp
import com.thinkmorestupidless.ankka.javasdk.Done
import com.thinkmorestupidless.ankka.javasdk.client.{ComponentClient, ServiceClients}
import com.thinkmorestupidless.ankka.javasdk.codec.{EncodingException, RecordCodec, Serializer}
import com.thinkmorestupidless.ankka.javasdk.http.{Acl, Caller, Principal}
import com.thinkmorestupidless.ankka.javasdk.mcp.{
  ClientKeyRequest,
  ClientKeySummary,
  ClientKeys,
  Expiry,
  IssuedClientKey,
  McpEndpoint,
  McpTool
}
import com.thinkmorestupidless.ankka.javasdk.problems.DeclarationProblem
import com.thinkmorestupidless.ankka.javasdk.secret.SecretStore
import com.thinkmorestupidless.ankka.javasdk.storage.ObjectStorage
import com.thinkmorestupidless.ankka.mcp as smcp
import com.thinkmorestupidless.ankka.sdk

import java.lang.reflect.{InvocationTargetException, Modifier, ParameterizedType, Type}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Optional
import java.util.function.{Function as JFunction, Supplier}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** A Java MCP endpoint class, read once. */
private[ankka] final case class McpPlan(cls: Class[?], construct: () => AnyRef)

/** One tool a Java MCP endpoint declared, finished on the Scala endpoint's builder. */
private final case class McpDeclared(
    name: String,
    description: String,
    acl: Option[Acl],
    finish: smcp.ToolBuilder => smcp.McpTool
) extends McpTool

/**
 * What a Java MCP endpoint declares and reads: its path and tools, collected while its constructor
 * runs, and what its handlers may call. Handed to the constructor through a thread-local, as an
 * HTTP endpoint's cell is.
 */
final class McpCell private[bridge] (
    refs: Option[Refs] = None,
    client: ComponentClient = Clients.unavailable,
    serviceClients: ServiceClients = Services.unavailable,
    secretStore: SecretStore = Stores.noSecrets,
    objectStorage: ObjectStorage = Stores.noStorage,
    keys: ClientKeys = Mcp.noKeys
):
  private[bridge] var path: String = "/mcp"
  private[bridge] val declared     = mutable.ListBuffer.empty[McpDeclared]

  def bind(path: String): Unit = this.path = path

  private def add(
      name: String,
      description: String,
      acl: Acl,
      finish: smcp.ToolBuilder => smcp.McpTool
  ): McpTool =
    val tool = McpDeclared(name, description, Option(acl), finish)
    declared += tool
    tool

  def handle(name: String, description: String, acl: Acl, handler: Supplier[?]): McpTool =
    add(name, description, acl, _.handle(() => Mcp.refusals(handler.get(): Any))(using Mcp.output))

  def route(
      name: String,
      description: String,
      acl: Acl,
      prefix: String,
      method: String,
      path: String
  ): McpTool =
    add(name, description, acl, _.route(prefix, method, path))

  def arguments[A](
      name: String,
      description: String,
      acl: Acl,
      tpe: Class[A],
      handler: JFunction[? >: A, ?]
  ): McpTool =
    val input = Mcp.recordInput(tpe, RecordCodec.forType(tpe).asInstanceOf[Serializer[Any]], None)
    val run   = handler.asInstanceOf[JFunction[Any, Any]]
    add(
      name,
      description,
      acl,
      _.raw(Mcp.objectSchema(input.schema, input.required)) { call =>
        call.arguments match
          case obj: Json.Obj =>
            input.decode(obj) match
              case Left(refused) => Left(refused)
              case Right(value) =>
                Right(Mcp.answer(Mcp.refusals(run.apply(value))))
          case _ => Left("the arguments are not an object")
      }
    )

  def reference(
      name: String,
      description: String,
      acl: Acl,
      ref: AnyRef,
      id: String,
      input: String,
      readOnly: Boolean
  ): McpTool =
    val resolved = refs.getOrElse(
      throw IllegalStateException("a tool can name a handler only in an endpoint a service built")
    )
    val (component, plan) = resolved.resolve(ref)
    val componentId       = ComponentId(component.componentId)
    val wireName          = MethodName(plan.wireName)
    val output            = CodecBridge.toScala(plan.output)
    val finish: smcp.ToolBuilder => smcp.McpTool = plan.input match
      case None =>
        val handle =
          sdk.NoArgHandle[Any, Any](componentId, wireName, plan.readOnly, output, _ => ())
        b =>
          if readOnly then b.query(handle, id)(using Mcp.output)
          else b.command(handle, id)(using Mcp.output)
      case Some(serializer) =>
        val inputType = plan.method.getGenericParameterTypes.head
        val toolInput = Option(input) match
          case Some(named) => Mcp.namedInput(named, inputType, serializer)
          case None        => Mcp.recordInput(inputType, serializer, Some(id))
        val handle = sdk.CommandHandle[Any, Any, Any](
          componentId,
          wireName,
          plan.readOnly,
          CodecBridge.toScala(serializer),
          output,
          (_, _) => ()
        )
        b =>
          if readOnly then b.query(handle, id)(using toolInput, Mcp.output)
          else b.command(handle, id)(using toolInput, Mcp.output)
    add(name, description, acl, finish)

  private def call: shttp.RequestContext =
    shttp.RequestScope.currentContext.getOrElse(
      throw IllegalStateException("the call is only available inside a handler, on its own thread")
    )

  def caller(): Caller                   = Acls.javaCaller(call.caller)
  def principal(): Optional[Principal]   = call.principal.map(Acls.javaPrincipal).toJava
  def componentClient(): ComponentClient = client
  def services(): ServiceClients         = serviceClients
  def secrets(): SecretStore             = secretStore
  def storage(): ObjectStorage           = objectStorage
  def clientKeys(): ClientKeys           = keys

object McpCell:
  private val constructing = ThreadLocal[McpCell]()

  def take(): McpCell =
    val cell = constructing.get()
    if cell == null then McpCell()
    else
      constructing.remove()
      cell

  private[bridge] def constructing[A](cell: McpCell)(build: => A): A =
    constructing.set(cell)
    try build
    finally constructing.remove()

private[ankka] object Mcp:

  def read(cls: Class[?]): Either[Vector[DeclarationProblem], McpPlan] =
    val problems = Vector.newBuilder[DeclarationProblem]
    def problem(message: String): Unit =
      problems += DeclarationProblem(cls.getSimpleName, "", message)
    if !classOf[McpEndpoint].isAssignableFrom(cls) then
      problem("is not an MCP endpoint: extend McpEndpoint")
    if Modifier.isAbstract(cls.getModifiers) then problem("is abstract; register a concrete class")
    val construct = cls.getDeclaredConstructors.find(_.getParameterCount == 0) match
      case Some(c) =>
        c.setAccessible(true)
        Some(() =>
          try c.newInstance().asInstanceOf[AnyRef]
          catch case e: InvocationTargetException => throw e.getCause
        )
      case None =>
        problem("has no constructor taking nothing")
        None
    val found = problems.result()
    if found.nonEmpty then Left(found) else Right(McpPlan(cls, construct.get))

  /** The Scala endpoint of a Java one, built for the clients the server hands it. */
  def scalaEndpoint(plan: McpPlan, cell: McpCell): smcp.McpEndpoint =
    val instance = McpCell.constructing(cell)(plan.construct()).asInstanceOf[McpEndpoint]
    ScalaMcpEndpoint(
      cell.path,
      Acls.scalaOf(Protected[Acl](instance, "acl")),
      Protected[String](instance, "version"),
      Protected[java.lang.Boolean](instance, "brokered").booleanValue,
      cell.declared.toVector
    )

  /** A Java handler's refusal as the command error whose message a tool error carries. */
  private[bridge] def refusals[A](run: => A): A = Grpc.refusals(run)

  /** A handler's answer as text: a string as it is, `Done` as `done`, anything else as JSON. */
  val output: ToolOutput[Any] =
    case null                 => "null"
    case text: String         => text
    case _: Done              => "done"
    case n: java.lang.Number  => n.toString
    case b: java.lang.Boolean => b.toString
    case value =>
      String(
        RecordCodec.forType(value.getClass).asInstanceOf[Serializer[Any]].toBytes(value),
        UTF_8
      )

  /** A handler's answer, structured when its text is a JSON object and it was not a string. */
  def answer(value: Any): smcp.ToolAnswer =
    val text = output.render(value)
    val structured = value match
      case _: String => None
      case _         => Json.parse(text).toOption.collect { case obj: Json.Obj => obj }
    smcp.ToolAnswer(text, structured)

  private def parsed(schema: String): Json =
    Json
      .parse(schema)
      .fold(e => throw IllegalStateException(s"a schema that is not JSON: $e"), identity)

  def objectSchema(properties: Vector[(String, Json)], required: Vector[String]): Json =
    Json.obj(
      "type"       -> Json.str("object"),
      "properties" -> Json.Obj(properties.toMap),
      "required"   -> Json.Arr(required.map(Json.str))
    )

  /**
   * A value from its JSON. A `String`'s serializer is raw UTF-8, so a string is handed over as its
   * characters, never its JSON (`.claude/rules/mcp.md`: the quotes would reach the handler).
   */
  private def decoded(serializer: Serializer[Any], tpe: Type, value: Json): Either[String, Any] =
    val bytes = (tpe, value) match
      case (c, Json.Str(text)) if c == classOf[String] => text.getBytes(UTF_8)
      case _                                           => value.render.getBytes(UTF_8)
    try Right(serializer.fromBytes(bytes))
    catch
      case e: EncodingException =>
        Left(if e.path.isEmpty then e.getMessage else s"argument '${e.path}': ${e.getMessage}")

  /**
   * Arguments checked against the schema before anything decodes them, worded as every SDK's
   * builder words a refused parameter (`parameter 'quantity': must be an integer, not a string`).
   */
  private def checked(schema: Json, arguments: Json): Either[String, Unit] =
    smcp.RawArguments.problems(schema, arguments).headOption.toLeft(())

  /** A record's components as arguments of their own, beside the id's when there is one. */
  def recordInput(tpe: Type, serializer: Serializer[Any], id: Option[String]): smcp.ToolInput[Any] =
    val declared = parsed(RecordCodec.schemaOf(tpe))
    val properties = declared("properties") match
      case Some(Json.Obj(fields)) => fields.toVector.sortBy(_._1)
      case _ =>
        throw IllegalArgumentException(
          s"${tpe.getTypeName} is not a record, so it has no arguments of its own; " +
            "name its argument with the input form of command or query"
        )
    val requiredFields =
      declared("required").flatMap(_.asArray).getOrElse(Vector.empty).flatMap(_.asString)
    new smcp.ToolInput[Any]:
      def schema: Vector[(String, Json)] = properties
      def required: Vector[String]       = requiredFields
      def decode(arguments: Json.Obj): Either[String, Any] =
        val own = Json.Obj(id.fold(arguments.fields)(arguments.fields - _))
        checked(objectSchema(properties, requiredFields), own)
          .flatMap(_ => decoded(serializer, tpe, own))

  /** An input that is not a record, under one argument of its own. */
  def namedInput(name: String, tpe: Type, serializer: Serializer[Any]): smcp.ToolInput[Any] =
    val optional = tpe match
      case p: ParameterizedType => p.getRawType == classOf[Optional[?]]
      case c                    => c == classOf[Optional[?]]
    val declared = parsed(RecordCodec.schemaOf(tpe))
    new smcp.ToolInput[Any]:
      def schema: Vector[(String, Json)] = Vector(name -> declared)
      def required: Vector[String]       = if optional then Vector.empty else Vector(name)
      def decode(arguments: Json.Obj): Either[String, Any] =
        arguments.fields.get(name) match
          case Some(value) =>
            checked(objectSchema(schema, required), Json.Obj(Map(name -> value)))
              .flatMap(_ => decoded(serializer, tpe, value))
          case None if optional => Right(Optional.empty())
          case None             => Left(s"argument '$name' is required")

  /** The Java client keys over the Scala ones; a refusal is a `CommandException`. */
  def clientKeys(scala: shttp.ClientKeys): ClientKeys = new ClientKeys:
    private def refused[A](run: => A): A =
      try run
      catch case e: CommandError => throw Clients.javaFailure(e)

    def issue(subject: String, expiry: Expiry): IssuedClientKey =
      issue(ClientKeyRequest.of(subject, expiry))

    def issue(request: ClientKeyRequest): IssuedClientKey =
      val issued = refused(
        scala.issueRequest(
          shttp.ClientKeyRequest(
            request.subject,
            Option(request.expiry).map(scalaExpiry),
            request.endpoint.toScala,
            request.scope.asScala.toSet,
            request.attributes.asScala.toMap,
            request.label.toScala
          )
        )
      )
      IssuedClientKey(issued.id, issued.clientKey)

    def list(): java.util.List[ClientKeySummary] =
      refused(scala.list()).map { s =>
        ClientKeySummary(
          s.id,
          s.subject,
          s.attributes.asJava,
          s.label.toJava,
          s.endpoint,
          s.scope.asJava,
          s.issuedAt,
          s.expiresAt.toJava,
          s.lastUsed.toJava,
          s.revokedAt.toJava
        )
      }.asJava

    def revoke(id: String): Unit = refused(scala.revoke(id))

  private def scalaExpiry(expiry: Expiry): shttp.Expiry = expiry match
    case after: Expiry.After => shttp.Expiry.After(Effects.finite(after.duration))
    case at: Expiry.At       => shttp.Expiry.At(at.instant)
    case _: Expiry.Never     => shttp.Expiry.Never

  val noKeys: ClientKeys = clientKeys(shttp.ClientKeys.unavailable)

/** Reads one of a Java class's protected methods that take nothing. */
private object Protected:
  def apply[A](instance: AnyRef, name: String): A =
    val method = Iterator
      .iterate[Class[?]](instance.getClass)(_.getSuperclass)
      .takeWhile(_ != null)
      .flatMap(_.getDeclaredMethods.find(m => m.getName == name && m.getParameterCount == 0))
      .next()
    method.setAccessible(true)
    try method.invoke(instance).asInstanceOf[A]
    catch case e: InvocationTargetException => throw e.getCause

private final class ScalaMcpEndpoint(
    path: String,
    endpointAcl: shttp.Acl,
    endpointVersion: String,
    signsIn: Boolean,
    declared: Vector[McpDeclared]
) extends smcp.McpEndpoint(path):

  override val acl: shttp.Acl    = endpointAcl
  override def version: String   = endpointVersion
  override def brokered: Boolean = signsIn

  declared.foreach { d =>
    val described = tool(d.name).describedAs(d.description)
    d.finish(d.acl.fold(described)(a => described.withAcl(Acls.scalaOf(a))))
  }
