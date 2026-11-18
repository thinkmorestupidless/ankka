package com.thinkmorestupidless.ankka.http.openapi

import com.github.plokhotnyuk.jsoniter_scala.core.{WriterConfig, writeToString}
import com.thinkmorestupidless.ankka.core.graph.GraphJson
import com.thinkmorestupidless.ankka.core.shape.{Named, Shape, TypeName}

/** Who may call an operation, as far as its ACL tells (contracts/document.md, Security). */
private[ankka] enum Access:
  case Open, Token, NotOpen

/** One parameter: `in` is `path` or `query`; `kind` the `FromPath` name of its type. */
private[ankka] final case class Parameter(name: String, in: String, required: Boolean, kind: String)

/** A body's or an answer's content: its media type and its shape, when it has one. */
private[ankka] final case class Content(mediaType: String, shape: Option[Shape])

/** An answer: `status` is a number or `default`. */
private[ankka] final case class Answer(
    status: String,
    description: String,
    content: Option[Content]
)

/** One route, as the document holds it (data-model.md). */
private[ankka] final case class Operation(
    method: String,
    path: String,
    summary: Option[String],
    tags: Vector[String],
    parameters: Vector[Parameter],
    body: Option[Content],
    answers: Vector[Answer],
    access: Access,
    lacks: Vector[String]
):
  /** `get_players_playerId_messages`: stable, and unique because method and path are. */
  def operationId: String =
    s"${method.toLowerCase}_$path"
      .replaceAll("[^A-Za-z0-9]+", "_")
      .stripPrefix("_")
      .stripSuffix("_")

  def incomplete: Boolean = lacks.nonEmpty

  /** `GET /players/{playerId}`. */
  def describe: String = s"$method $path"

/**
 * A service's OpenAPI 3.1 document (feature 067): its operations, the named shapes they reach and
 * the names the document gives them. `render` writes contracts/document.md's bytes.
 */
private[ankka] final case class Document(
    title: String,
    version: String,
    operations: Vector[Operation],
    definitions: Vector[Named],
    names: Map[TypeName, String]
):
  import Document.*

  def render: String =
    writeToString(json, WriterConfig.withIndentionStep(2))(using GraphJson.codec) + "\n"

  def json: GraphJson =
    val paths = operations.groupBy(_.path).toVector.sortBy(_._1).map { (path, ops) =>
      path -> J.Obj(ops.sortBy(o => Methods.indexOf(o.method.toLowerCase)).map { o =>
        o.method.toLowerCase -> operation(o)
      })
    }
    val schemas = definitions
      .map(n => names(n.name) -> Shape.render(n.shape, ref))
      .sortBy(_._1)
    val bearer     = operations.exists(_.access == Access.Token)
    val components = Vector(
      Option.when(schemas.nonEmpty)("schemas" -> J.Obj(schemas)),
      Option.when(bearer)(
        "securitySchemes" -> J.Obj(
          Vector("bearer" -> J.Obj(Vector("type" -> J.Str("http"), "scheme" -> J.Str("bearer"))))
        )
      )
    ).flatten
    J.Obj(
      Vector(
        "openapi" -> J.Str("3.1.0"),
        "info"    -> J.Obj(Vector("title" -> J.Str(title), "version" -> J.Str(version))),
        "paths"   -> J.Obj(paths)
      ) ++ Option.when(components.nonEmpty)("components" -> J.Obj(components))
    )

  private def ref(name: TypeName): String = s"#/components/schemas/${names(name)}"

  private def schema(shape: Shape): GraphJson = Shape.render(shape, ref)

  private def content(c: Content): GraphJson =
    J.Obj(Vector(c.mediaType -> J.Obj(c.shape.toVector.map(s => "schema" -> schema(s)))))

  private def operation(o: Operation): GraphJson =
    val pathParameters  = o.parameters.filter(_.in == "path")
    val queryParameters = o.parameters.filter(_.in == "query").sortBy(_.name)
    val parameters      = (pathParameters ++ queryParameters).map { p =>
      J.Obj(
        Vector(
          "name"     -> J.Str(p.name),
          "in"       -> J.Str(p.in),
          "required" -> J.Bool(p.required),
          "schema"   -> schema(parameterShape(p.kind))
        )
      )
    }
    val answers = o.answers
      .sortBy(a => a.status.toIntOption.getOrElse(Int.MaxValue))
      .map { a =>
        a.status -> J.Obj(
          Vector("description" -> J.Str(a.description)) ++
            a.content.map(c => "content" -> content(c))
        )
      }
    J.Obj(
      Vector(
        Some("operationId" -> J.Str(o.operationId)),
        o.summary.map(s => "summary" -> J.Str(s)),
        Option.when(o.tags.nonEmpty)("tags"           -> J.Arr(o.tags.map(J.Str(_)))),
        Option.when(parameters.nonEmpty)("parameters" -> J.Arr(parameters)),
        o.body.map(b => "requestBody" -> J.Obj(Vector("content" -> content(b)))),
        Some("responses" -> J.Obj(answers)),
        o.access match
          case Access.Open  => Some("security" -> J.Arr(Vector.empty))
          case Access.Token =>
            Some("security" -> J.Arr(Vector(J.Obj(Vector("bearer" -> J.Arr(Vector.empty))))))
          case Access.NotOpen => None
        ,
        Option.when(o.access == Access.NotOpen)("x-ankka-admission" -> J.Str("not-open")),
        Option.when(o.incomplete)("x-ankka-incomplete"              -> J.Arr(o.lacks.map(J.Str(_))))
      ).flatten
    )

private[ankka] object Document:
  private val J = GraphJson

  /** The order of methods within a path. */
  val Methods: Vector[String] = Vector("get", "post", "put", "patch", "delete")

  /** A path or query parameter's schema, from its `FromPath` name. */
  def parameterShape(kind: String): Shape = kind match
    case "int"     => Shape.integer
    case "long"    => Shape.Scalar("integer", Some("int64"))
    case "boolean" => Shape.boolean
    case "double"  => Shape.number
    case "uuid"    => Shape.Scalar("string", Some("uuid"))
    case _         => Shape.string
