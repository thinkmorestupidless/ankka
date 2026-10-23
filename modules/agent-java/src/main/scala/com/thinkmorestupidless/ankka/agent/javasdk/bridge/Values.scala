package com.thinkmorestupidless.ankka.agent.javasdk.bridge

import com.thinkmorestupidless.ankka.agent
import com.thinkmorestupidless.ankka.agent.javasdk
import com.thinkmorestupidless.ankka.agent.{Approval, FunctionTool, Json, MemoryProvider, ToolSpec}
import com.thinkmorestupidless.ankka.javasdk.annotations.Describe
import com.thinkmorestupidless.ankka.javasdk.bridge.{Declarations, Effects, Mcp}
import com.thinkmorestupidless.ankka.javasdk.codec.{RecordCodec, Serializer}
import com.thinkmorestupidless.ankka.mcp as smcp
import com.github.plokhotnyuk.jsoniter_scala.core.{JsonReader, JsonValueCodec, JsonWriter}

import java.lang.reflect.{Method, Parameter, Type}
import java.time.Duration
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.function.{Function as JFunction, Supplier}
import scala.jdk.OptionConverters.*

/** A Java model provider over the Scala one it stands for. */
/**
 * Java model providers that stand for a Scala one, as the test kit's scripted model does. A
 * registry rather than an interface, so the test kit's public class names nothing of the bridge.
 */
object ScalaModels:
  private val known = java.util.Collections.synchronizedMap(
    java.util.WeakHashMap[javasdk.ModelProvider, agent.ModelProvider]()
  )

  def register(java: javasdk.ModelProvider, scala: AnyRef): Unit =
    known.put(java, scala.asInstanceOf[agent.ModelProvider]): Unit

  private[bridge] def of(java: javasdk.ModelProvider): Option[agent.ModelProvider] =
    Option(known.get(java))

private[ankka] final case class JavaModelProvider(scala: agent.ModelProvider)
    extends javasdk.ModelProvider:
  def name(): String = scala.name

/** A Java memory over the Scala one. */
private[bridge] final case class JavaMemory(scala: MemoryProvider) extends javasdk.Memory:
  def readLast(count: Int): javasdk.Memory = JavaMemory(scala.readLast(count))
  def readOnly(): javasdk.Memory           = JavaMemory(scala.readOnly)
  def writeOnly(): javasdk.Memory          = JavaMemory(scala.writeOnly)

/** A Java MCP server over the Scala declaration. */
private[bridge] final case class JavaMcpServer(scala: agent.mcp.McpServer)
    extends javasdk.McpServer:
  def header(name: String, variable: String): javasdk.McpServer =
    JavaMcpServer(scala.header(name, variable))
  def requiresApproval(): javasdk.McpServer = JavaMcpServer(scala.requiresApproval)
  def requiresApproval(within: Duration): javasdk.McpServer =
    JavaMcpServer(scala.requiresApproval(Effects.finite(within)))

/** A tool declared in code, over the Scala tool. */
private[bridge] final case class JavaFunctionTool(scala: FunctionTool) extends javasdk.FunctionTool:
  def name(): String                           = scala.name
  def requiresApproval(): javasdk.FunctionTool = JavaFunctionTool(scala.requiresApproval)
  def requiresApproval(within: Duration): javasdk.FunctionTool =
    JavaFunctionTool(scala.requiresApproval(Effects.finite(within)))

/** One of the Scala guardrails, offered to Java as the Java interface. */
private[bridge] final case class ScalaGuardrail(scala: agent.Guardrail) extends javasdk.Guardrail:
  def name(): String                                       = scala.name
  override def checkInput(text: String): Optional[String]  = Values.reason(scala.checkInput(text))
  override def checkOutput(text: String): Optional[String] = Values.reason(scala.checkOutput(text))
  override def checkResult(text: String): Optional[String] = Values.reason(scala.checkResult(text))

/** A Java guardrail as the Scala loop runs it. */
private[bridge] final class JavaGuardrail(java: javasdk.Guardrail) extends agent.Guardrail:
  // Read once, as a value: the loop names a refusal by it, and a Java name that changed between
  // calls would make two refusals of one guardrail look like two guardrails.
  private val declaredName: String                            = java.name()
  def name: String                                            = declaredName
  override def checkInput(text: String): Either[String, Unit] = Values.either(java.checkInput(text))
  override def checkOutput(text: String): Either[String, Unit] =
    Values.either(java.checkOutput(text))
  override def checkResult(text: String): Either[String, Unit] =
    Values.either(java.checkResult(text))

/**
 * A Java serializer as the codec a structured reply is read with. The reply's JSON is handed to the
 * serializer as it came, so a record is read exactly as the service's own records are.
 */
private[bridge] final class SerializerCodec[A](serializer: Serializer[A]) extends JsonValueCodec[A]:
  def decodeValue(in: JsonReader, default: A): A = serializer.fromBytes(in.readRawValAsBytes())
  def encodeValue(x: A, out: JsonWriter): Unit   = out.writeRawVal(serializer.toBytes(x))
  def nullValue: A                               = null.asInstanceOf[A]

private[bridge] object Values:

  def reason(check: Either[String, Unit]): Optional[String] = check.left.toOption.toJava

  def either(check: Optional[String]): Either[String, Unit] =
    check.toScala.filter(_ != null).toLeft(())

  def scalaGuardrail(g: javasdk.Guardrail): agent.Guardrail = g match
    case ScalaGuardrail(scala) => scala
    case JavaJudged(scala)     => scala
    case java                  => JavaGuardrail(java)

  def scalaMemory(m: javasdk.Memory): MemoryProvider = m match
    case JavaMemory(scala) => scala
    case other             => throw IllegalArgumentException(s"$other is not a Memory the SDK made")

  def scalaModel(p: javasdk.ModelProvider): agent.ModelProvider = p match
    case JavaModelProvider(scala) => scala
    case other =>
      ScalaModels
        .of(other)
        .getOrElse(
          throw IllegalArgumentException(
            s"${other.name()} is not a model provider ModelProviders or the test kit made"
          )
        )

  def scalaMcp(m: javasdk.McpServer): agent.mcp.McpServer = m match
    case JavaMcpServer(scala) => scala
    case other => throw IllegalArgumentException(s"$other is not an McpServer the SDK made")

  /** The serializer of `tpe`: the service's own for its class, or the record codec's. */
  def serializer(tpe: Type, serializers: Declarations.Serializers): Serializer[Any] =
    val raw = com.thinkmorestupidless.ankka.javasdk.bridge.Planner.rawClass(tpe)
    // A judgment is the platform's, stored as the Scala SDK's is.
    if raw.contains(classOf[javasdk.Judgment]) then JudgmentSerializer.asInstanceOf[Serializer[Any]]
    else
      raw.flatMap(serializers.get) match
        case Some(explicit) => explicit.asInstanceOf[Serializer[Any]]
        case None           => RecordCodec.forType(tpe).asInstanceOf[Serializer[Any]]

  def codec[A](cls: Class[A], serializers: Declarations.Serializers): JsonValueCodec[A] =
    SerializerCodec(serializer(cls, serializers).asInstanceOf[Serializer[A]])

/** Tools: from a `FunctionTool`, or read from the `@Tool` methods of an object or a class. */
private[bridge] object Tools:

  private val NoArguments: Json = Mcp.objectSchema(Vector.empty, Vector.empty)

  def noArgument(name: String, description: String, run: Supplier[String]): javasdk.FunctionTool =
    JavaFunctionTool(
      FunctionTool.raw(ToolSpec(name, description, NoArguments))(_ => Right(run.get()))
    )

  def withArguments[A](
      name: String,
      description: String,
      arguments: Class[A],
      run: JFunction[A, String]
  ): javasdk.FunctionTool =
    if !arguments.isRecord then
      throw IllegalArgumentException(
        s"${arguments.getSimpleName} is not a record, so it has no arguments of its own"
      )
    val input = recordInput(arguments, arguments, Values.serializer(arguments, Map.empty))
    val spec  = ToolSpec(name, description, Mcp.objectSchema(input.schema, input.required))
    JavaFunctionTool(
      FunctionTool.raw(spec)(json =>
        input.decode(asObject(json)).map(a => run.apply(a.asInstanceOf[A]))
      )
    )

  /**
   * The schema of a record, as a tool's arguments are offered: each component's `@Describe` too.
   */
  def recordSchema(record: Class[?]): Json =
    val input = recordInput(record, record, Values.serializer(record, Map.empty))
    Mcp.objectSchema(input.schema, input.required)

  /** What a builder's `tools(...)` was given, as the Scala tools. */
  def of(items: Seq[AnyRef], serializers: Declarations.Serializers): Vector[FunctionTool] =
    items.toVector.flatMap {
      case JavaFunctionTool(scala) => Vector(scala)
      case cls: Class[?]           => annotated(construct(cls), serializers)
      case null                    => throw IllegalArgumentException("a tool cannot be null")
      case instance                => annotated(instance, serializers)
    }

  private def construct(cls: Class[?]): AnyRef =
    val c =
      try cls.getDeclaredConstructor()
      catch
        case _: NoSuchMethodException =>
          throw IllegalArgumentException(
            s"${cls.getSimpleName} has no constructor taking nothing; give the tools an instance"
          )
    c.setAccessible(true)
    try c.newInstance().asInstanceOf[AnyRef]
    catch case e: java.lang.reflect.InvocationTargetException => throw e.getCause

  /** A tool method, read once per class. */
  private final case class ToolMethod(
      method: Method,
      spec: ToolSpec,
      approval: Option[Approval],
      decode: Json.Obj => Either[String, Vector[AnyRef]]
  )

  private val read = ConcurrentHashMap[Class[?], Vector[ToolMethod]]()

  private def annotated(
      instance: AnyRef,
      serializers: Declarations.Serializers
  ): Vector[FunctionTool] =
    val methods = read.computeIfAbsent(instance.getClass, cls => toolMethods(cls, serializers))
    if methods.isEmpty then
      throw IllegalArgumentException(
        s"${instance.getClass.getSimpleName} has no @Tool method, so it offers the model nothing"
      )
    methods.map { t =>
      FunctionTool.raw(t.spec, t.approval) { json =>
        t.decode(asObject(json)).map { arguments =>
          Mcp.output.render(
            com.thinkmorestupidless.ankka.javasdk.bridge.Invocable
              .invoke(t.method, instance, arguments*)
          )
        }
      }
    }

  private def toolMethods(
      cls: Class[?],
      serializers: Declarations.Serializers
  ): Vector[ToolMethod] =
    Iterator
      .iterate[Class[?]](cls)(_.getSuperclass)
      .takeWhile(c => c != null && c != classOf[Object])
      .flatMap(_.getDeclaredMethods)
      .filter(Declarations.declaredByHand)
      .flatMap(m => Option(m.getAnnotation(classOf[javasdk.Tool])).map(m -> _))
      .map((m, tool) => toolMethod(cls, m, tool, serializers))
      .toVector
      .sortBy(_.spec.name)

  private def toolMethod(
      cls: Class[?],
      m: Method,
      tool: javasdk.Tool,
      serializers: Declarations.Serializers
  ): ToolMethod =
    m.setAccessible(true)
    val where = s"${cls.getSimpleName}.${m.getName}"
    val approval =
      if !tool.requiresApproval then
        if tool.approvalWithin.nonEmpty then
          throw IllegalArgumentException(s"$where has approvalWithin but not requiresApproval")
        None
      else if tool.approvalWithin.isEmpty then Some(Approval(None))
      else
        val within =
          try Duration.parse(tool.approvalWithin)
          catch
            case _: java.time.format.DateTimeParseException =>
              throw IllegalArgumentException(
                s"$where waits '${tool.approvalWithin}', which is not an ISO-8601 duration such as PT10M"
              )
        if within.isNegative || within.isZero then
          throw IllegalArgumentException(s"$where needs a positive time limit for approval")
        Some(Approval(Some(Effects.finite(within))))
    val parameters = m.getParameters.toVector
    val inputs: Vector[smcp.ToolInput[Any]] = parameters match
      case Vector() => Vector.empty
      case Vector(p) if p.getType.isRecord =>
        val tpe = p.getParameterizedType
        Vector(recordInput(p.getType, tpe, Values.serializer(tpe, serializers)))
      case ps => ps.map(p => described(p, named(where, p, serializers)))
    val spec = ToolSpec(
      tool.value,
      tool.description,
      Mcp.objectSchema(inputs.flatMap(_.schema), inputs.flatMap(_.required))
    )
    ToolMethod(
      m,
      spec,
      approval,
      arguments =>
        inputs.foldLeft[Either[String, Vector[AnyRef]]](Right(Vector.empty)) { (done, input) =>
          done.flatMap(values => input.decode(arguments).map(v => values :+ v.asInstanceOf[AnyRef]))
        }
    )

  private def named(where: String, p: Parameter, serializers: Declarations.Serializers) =
    if !p.isNamePresent then
      throw IllegalArgumentException(
        s"$where's parameters have no names: compile with -parameters, or take one record"
      )
    Mcp.namedInput(
      p.getName,
      p.getParameterizedType,
      Values.serializer(p.getParameterizedType, serializers)
    )

  private def describe(annotated: java.lang.reflect.AnnotatedElement): Option[String] =
    Option(annotated.getAnnotation(classOf[Describe])).map(_.value)

  /** A parameter's input, its property carrying the parameter's `@Describe`. */
  private def described(p: Parameter, input: smcp.ToolInput[Any]): smcp.ToolInput[Any] =
    described(input, describe(p).map(p.getName -> _).toMap)

  /** A record's input, each property carrying its component's `@Describe`. */
  private def recordInput(record: Class[?], tpe: Type, serializer: Serializer[Any]) =
    val descriptions = record.getRecordComponents.toVector.flatMap { c =>
      describe(c.getAccessor).orElse(describe(c)).map(c.getName -> _)
    }
    described(Mcp.recordInput(tpe, serializer, None), descriptions.toMap)

  /** What the model is told each property is, beside its type. */
  private def described(
      input: smcp.ToolInput[Any],
      descriptions: Map[String, String]
  ): smcp.ToolInput[Any] =
    if descriptions.isEmpty then input
    else
      new smcp.ToolInput[Any]:
        def schema: Vector[(String, Json)] = input.schema.map {
          case (name, Json.Obj(fields)) if descriptions.contains(name) =>
            name -> Json.Obj(fields + ("description" -> Json.str(descriptions(name))))
          case other => other
        }
        def required: Vector[String]                         = input.required
        def decode(arguments: Json.Obj): Either[String, Any] = input.decode(arguments)

  private def asObject(json: Json): Json.Obj = json match
    case obj: Json.Obj => obj
    case _             => Json.Obj(Map.empty)
