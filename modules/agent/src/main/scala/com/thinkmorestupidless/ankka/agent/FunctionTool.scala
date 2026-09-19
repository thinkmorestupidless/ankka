package com.thinkmorestupidless.ankka.agent

import scala.util.control.NonFatal

/**
 * A function the model may decide to call.
 *
 * Declared rather than annotated. Akka's `@FunctionTool` needs reflection and parameter- name
 * retention to work out a schema; here the schema comes from the same `SchemaType` instances that
 * decode the arguments, so a parameter cannot be described one way and read another, and arity or
 * type mistakes are compile errors.
 */
final class FunctionTool private[agent] (
    val spec: ToolSpec,
    private val invoker: Json => Either[String, String]
):

  def name: String = spec.name

  /**
   * Runs the tool against the model's arguments.
   *
   * A `Left` is not a crash — it is a message for the model. Decode failures and handler exceptions
   * both come back this way so the loop can hand the model a failed tool result and let it correct
   * itself, which is usually what it does.
   */
  def invoke(arguments: Json): Either[String, String] =
    try invoker(arguments)
    catch
      case NonFatal(failure) =>
        Left(Option(failure.getMessage).getOrElse(failure.getClass.getSimpleName))

  override def toString: String = s"FunctionTool(${spec.name})"

object FunctionTool:

  /** Starts declaring a tool. */
  def named(name: String): NamedBuilder =
    if name.isEmpty then throw IllegalArgumentException("a tool needs a name")
    else NamedBuilder(name)

  final case class NamedBuilder private[agent] (name: String):
    /**
     * What the tool does.
     *
     * Required, because this description is the entire basis on which the model decides whether to
     * call it. A tool with no description is a tool that gets called at random.
     */
    def describedAs(description: String): ToolBuilder0 =
      if description.isEmpty then
        throw IllegalArgumentException(s"tool '$name' needs a description")
      else ToolBuilder0(name, description)

  private[agent] final case class Param(
      name: String,
      description: String,
      schema: Json,
      required: Boolean,
      decode: Option[Json] => Either[String, Any]
  )

  private[agent] def build[R](
      name: String,
      description: String,
      params: Vector[Param],
      apply: Vector[Any] => R
  )(using out: ToolOutput[R]): FunctionTool =
    val duplicates = params.groupBy(_.name).collect {
      case (paramName, occurrences) if occurrences.sizeIs > 1 => paramName
    }
    if duplicates.nonEmpty then
      throw IllegalArgumentException(
        s"tool '$name' declares parameter(s) ${duplicates.mkString(", ")} more than once"
      )

    val properties = params.map { param =>
      param.name -> mergeDescription(param.schema, param.description)
    }

    val schema = Json.obj(
      "type"       -> Json.str("object"),
      "properties" -> Json.Obj(properties.toMap),
      "required"   -> Json.Arr(params.filter(_.required).map(p => Json.str(p.name))),
      // Models are markedly better behaved when told not to invent fields.
      "additionalProperties" -> Json.bool(false)
    )

    new FunctionTool(
      ToolSpec(name, description, schema),
      arguments =>
        val decoded =
          params.foldLeft[Either[String, Vector[Any]]](Right(Vector.empty)) { (acc, param) =>
            for
              soFar <- acc
              value <- param
                .decode(arguments(param.name))
                .left
                .map(reason => s"parameter '${param.name}': $reason")
            yield soFar :+ value
          }
        decoded.map(values => out.render(apply(values)))
    )

  private def mergeDescription(schema: Json, description: String): Json =
    schema match
      case Json.Obj(fields) if description.nonEmpty =>
        Json.Obj(fields + ("description" -> Json.str(description)))
      case other => other

/**
 * Tool builders, one per arity.
 *
 * Explicit types rather than a generic parameter list: `handle` then takes a function of exactly
 * the right shape, so the compiler catches a mismatch between what was declared and what the
 * handler expects — which is the mistake a map-of-strings API cannot catch.
 */
final case class ToolBuilder0 private[agent] (
    private val name: String,
    private val description: String,
    private val params: Vector[FunctionTool.Param] = Vector.empty
):
  def param[A](paramName: String, paramDescription: String)(using
      schema: SchemaType[A]
  ): ToolBuilder1[A] =
    ToolBuilder1(name, description, params :+ paramOf[A](paramName, paramDescription))

  def handle[R](f: () => R)(using ToolOutput[R]): FunctionTool =
    FunctionTool.build(name, description, params, _ => f())

  private def paramOf[A](paramName: String, paramDescription: String)(using
      schema: SchemaType[A]
  ) =
    FunctionTool.Param(
      paramName,
      paramDescription,
      schema.schema,
      schema.required,
      json => schema.decode(json)
    )

final case class ToolBuilder1[A] private[agent] (
    private val name: String,
    private val description: String,
    private val params: Vector[FunctionTool.Param]
):
  def param[B](paramName: String, paramDescription: String)(using
      schema: SchemaType[B]
  ): ToolBuilder2[A, B] =
    ToolBuilder2(
      name,
      description,
      params :+ FunctionTool.Param(
        paramName,
        paramDescription,
        schema.schema,
        schema.required,
        json => schema.decode(json)
      )
    )

  def handle[R](f: A => R)(using ToolOutput[R]): FunctionTool =
    FunctionTool.build(name, description, params, values => f(values(0).asInstanceOf[A]))

final case class ToolBuilder2[A, B] private[agent] (
    private val name: String,
    private val description: String,
    private val params: Vector[FunctionTool.Param]
):
  def param[C](paramName: String, paramDescription: String)(using
      schema: SchemaType[C]
  ): ToolBuilder3[A, B, C] =
    ToolBuilder3(
      name,
      description,
      params :+ FunctionTool.Param(
        paramName,
        paramDescription,
        schema.schema,
        schema.required,
        json => schema.decode(json)
      )
    )

  def handle[R](f: (A, B) => R)(using ToolOutput[R]): FunctionTool =
    FunctionTool.build(
      name,
      description,
      params,
      values => f(values(0).asInstanceOf[A], values(1).asInstanceOf[B])
    )

final case class ToolBuilder3[A, B, C] private[agent] (
    private val name: String,
    private val description: String,
    private val params: Vector[FunctionTool.Param]
):
  def handle[R](f: (A, B, C) => R)(using ToolOutput[R]): FunctionTool =
    FunctionTool.build(
      name,
      description,
      params,
      values => f(values(0).asInstanceOf[A], values(1).asInstanceOf[B], values(2).asInstanceOf[C])
    )
