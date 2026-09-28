package com.thinkmorestupidless.ankka.agent.autonomous

import com.github.plokhotnyuk.jsoniter_scala.core.{
  JsonReader,
  JsonValueCodec,
  JsonWriter,
  readFromString,
  writeToString
}
import com.thinkmorestupidless.ankka.agent.Json

import scala.util.control.NonFatal

/**
 * A kind of work an autonomous agent can be given.
 *
 * `name` is the wire name: it is written into every task record of this type, so it is declared
 * separately from the Scala value holding it, and renaming the value changes nothing stored. A type
 * with a result shape is completed with a value of `R`, decoded with its codec and described to the
 * model by its schema; a type without one is completed with text.
 *
 * {{{
 * val answer: TaskType[Answer] = Task
 *   .named("answer")
 *   .describedAs("Answer a question about the catalogue")
 *   .resultConformsTo[Answer]
 *   .rule("cites-sources")(a => if a.sources.isEmpty then TaskRule.Rejected("cite something") else TaskRule.Accepted)
 * }}}
 */
final class TaskType[R] private[autonomous] (
    val name: String,
    val description: String,
    private[ankka] val shape: ResultShape[R],
    val rules: Vector[TaskRule[R]],
    private[ankka] val external: Option[String => TaskType.Verdict] = None
):

  /** Adds a rule a result must satisfy. Rules run in the order they are added. */
  def rule(ruleName: String)(check: R => TaskRule.Result): TaskType[R] =
    if ruleName.isEmpty then
      throw IllegalArgumentException(s"a rule on task type '$name' needs a name")
    else if rules.exists(_.name == ruleName) then
      throw IllegalArgumentException(s"task type '$name' already has a rule named '$ruleName'")
    else new TaskType[R](name, description, shape, rules :+ TaskRule(ruleName, check), external)

  /** Whether the result is a declared type rather than text. */
  def hasResultShape: Boolean = shape.schema.isDefined

  /** The JSON Schema the model is shown for `complete_task`. */
  private[ankka] def completionSchema: Json =
    shape.schema.getOrElse(
      Json.obj(
        "type"                 -> Json.str("object"),
        "properties"           -> Json.obj("result" -> Json.obj("type" -> Json.str("string"))),
        "required"             -> Json.arr(Json.str("result")),
        "additionalProperties" -> Json.bool(false)
      )
    )

  /**
   * Reads what the model passed to `complete_task` into a result, or explains why it could not.
   *
   * A type without a shape takes `{"result": "..."}`; a type with one takes the value itself.
   */
  private[ankka] def decodeCompletion(arguments: Json): Either[String, R] =
    shape.decodeCompletion(arguments)

  /**
   * Whether what the model passed to `complete_task` stands: it must decode as the result, and pass
   * the type's rules in order. A rule that throws is not a verdict; it propagates, and the
   * iteration is tried again.
   */
  private[ankka] def verify(arguments: Json): TaskType.Verdict =
    decodeCompletion(arguments) match
      case Left(problem) => TaskType.Verdict.Malformed(problem)
      case Right(result) =>
        val encoded = encode(result)
        external match
          case Some(check) => check(encoded)
          case None =>
            rules.iterator
              .map(rule => rule.name -> rule.run(result))
              .collectFirst { case (rule, TaskRule.Rejected(reason)) =>
                TaskType.Verdict.Rejected(rule, reason)
              }
              .getOrElse(TaskType.Verdict.Accepted(encoded))

  /** The stored, canonical JSON of a result. */
  private[ankka] def encode(result: R): String = shape.encode(result)

  /** Reads a stored result back. */
  private[ankka] def decode(stored: String): Either[String, R] = shape.decode(stored)

  override def toString: String = s"TaskType($name)"

object TaskType:

  enum Verdict:
    /** The result stands; `encoded` is what the task's record keeps. */
    case Accepted(encoded: String)

    /** It does not match the task's shape; the model is told, and tries again. */
    case Malformed(problem: String)

    /** `rule` refused it. */
    case Rejected(rule: String, reason: String)

  /**
   * A task type declared by a process in another language, through the sidecar: its result is
   * described by the process's schema, kept as the JSON the model sent, and checked — decoded and
   * put through the process's rules — by `check`, which is handed the result's stored JSON.
   */
  private[ankka] def remote(
      name: String,
      description: String,
      schema: Option[Json],
      check: String => Verdict
  ): TaskType[?] =
    schema match
      case Some(s) =>
        new TaskType[Json](name, description, ResultShape.json(s), Vector.empty, Some(check))
      case None =>
        new TaskType[String](name, description, ResultShape.text, Vector.empty, Some(check))

  /**
   * A task type whose result is described by a schema someone else wrote — a process in another
   * language, through the sidecar — and carried as JSON rather than decoded into a Scala type. The
   * rules are theirs too, checked with the result's JSON.
   */
  private[ankka] def json(
      name: String,
      description: String,
      schema: Json,
      rules: Vector[TaskRule[Json]]
  ): TaskType[Json] =
    new TaskType[Json](name, description, ResultShape.json(schema), rules)

  /** As `json`, for a type whose result is text. */
  private[ankka] def text(
      name: String,
      description: String,
      rules: Vector[TaskRule[String]]
  ): TaskType[String] =
    new TaskType[String](name, description, ResultShape.text, rules)

  extension (task: TaskType[String])
    /**
     * Declares the type of the result. The codec decodes it; the schema describes it to the model.
     * Both are normally derived from the same case class.
     */
    def resultConformsTo[R](using codec: JsonValueCodec[R], schema: JsonSchema[R]): TaskType[R] =
      if task.rules.nonEmpty then
        throw IllegalArgumentException(
          s"task type '${task.name}': declare the result before its rules, which are typed by it"
        )
      else new TaskType[R](task.name, task.description, ResultShape.of[R], Vector.empty)

object Task:

  /** Starts declaring a task type with its wire name. */
  def named(name: String): Named =
    if name.isEmpty then throw IllegalArgumentException("a task type needs a name")
    else Named(name)

  final case class Named private[autonomous] (name: String):
    /**
     * What the work is. Shown to the model, and the only thing it has to go on besides the
     * instructions of each task.
     */
    def describedAs(description: String): TaskType[String] =
      if description.isEmpty then
        throw IllegalArgumentException(s"task type '$name' needs a description")
      else new TaskType[String](name, description, ResultShape.text, Vector.empty)

/** A check a result must pass before a task counts as completed. */
final case class TaskRule[R](name: String, check: R => TaskRule.Result):

  /** A rule that throws has not decided anything; the caller treats that as a failed iteration. */
  private[ankka] def run(result: R): TaskRule.Result = check(result)

object TaskRule:
  sealed trait Result
  case object Accepted                      extends Result
  final case class Rejected(reason: String) extends Result

/** How a task type's result is read, written and described. */
private[ankka] final class ResultShape[R] private (
    val schema: Option[Json],
    codec: Option[JsonValueCodec[R]]
):

  def decodeCompletion(arguments: Json): Either[String, R] = codec match
    case Some(c) => decodeWith(arguments.render)(using c)
    case None =>
      arguments("result").flatMap(_.asString) match
        case Some(text) => Right(text.asInstanceOf[R])
        case None       => Left("""complete_task needs {"result": "<text>"}""")

  def encode(result: R): String = codec match
    case Some(c) => writeToString(result)(using c)
    case None    => writeToString(result.asInstanceOf[String])(using ResultShape.stringCodec)

  def decode(stored: String): Either[String, R] = codec match
    case Some(c) => decodeWith(stored)(using c)
    case None    => decodeWith(stored)(using ResultShape.stringCodec).map(_.asInstanceOf[R])

  private def decodeWith[A](text: String)(using c: JsonValueCodec[A]): Either[String, A] =
    try Right(readFromString(text))
    catch case NonFatal(failure) => Left(Option(failure.getMessage).getOrElse(failure.toString))

private[ankka] object ResultShape:
  val text: ResultShape[String] = new ResultShape[String](None, None)

  def of[R](using codec: JsonValueCodec[R], schema: JsonSchema[R]): ResultShape[R] =
    new ResultShape[R](Some(schema.schema), Some(codec))

  /** A result that stays JSON: any value the schema describes, stored as the model sent it. */
  def json(schema: Json): ResultShape[Json] = new ResultShape[Json](Some(schema), Some(Json.codec))

  /** A result stored as text is a JSON string, so every stored result is JSON. */
  val stringCodec: JsonValueCodec[String] = new JsonValueCodec[String]:
    def nullValue: String                                    = null
    def decodeValue(in: JsonReader, default: String): String = in.readString(default)
    def encodeValue(x: String, out: JsonWriter): Unit        = out.writeVal(x)

/** Content that travels with a task: inline text, or a reference the agent's tools can fetch. */
final case class Attachment(name: String, contentType: String, content: AttachmentContent)

enum AttachmentContent:
  case Inline(text: String)
  case Reference(uri: String)
