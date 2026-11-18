package com.thinkmorestupidless.ankka.http.openapi

import com.thinkmorestupidless.ankka.core.shape.{Named, Shape, ShapeOf, TypeName}
import com.thinkmorestupidless.ankka.http.{Acl, HttpEndpoint, ServesRoutes, TokenAuthenticator}

/**
 * Which of a service's operations a document holds: those carrying any of `tags` when tags are
 * given, and under any of `prefixes` when prefixes are given. `strict` refuses a document while any
 * operation it holds is incomplete.
 */
private[ankka] final case class Selection(
    tags: Vector[String] = Vector.empty,
    prefixes: Vector[String] = Vector.empty,
    strict: Boolean = false
)

/**
 * Builds a service's OpenAPI document from its endpoints, built with no service behind them
 * (feature 067, research R3). Reads only what each route recorded of itself; runs nothing.
 */
private[ankka] object OpenApi:

  /**
   * The platform's refusal: what every operation's `default` is unless its endpoint says another.
   */
  val ProblemName: TypeName =
    TypeName("com.thinkmorestupidless.ankka.http.AnkkaProblem", SchemaNames.Problem)

  private val problem: Named = Named(
    ProblemName,
    Shape.Obj(
      Vector(
        "status"  -> Shape.integer,
        "error"   -> Shape.string,
        "details" -> Shape.Dict(Shape.string)
      ),
      Vector("status", "error")
    )
  )

  private val Json = "application/json"

  /** What one route gives a document: its operation and the named shapes it reaches. */
  private final case class Built(
      operation: Operation,
      definitions: Vector[Named],
      problems: Vector[String]
  )

  def build(
      name: String,
      version: String,
      endpoints: Seq[HttpEndpoint],
      selection: Selection = Selection()
  ): Either[Vector[String], Document] =
    val built = endpoints.toVector.filterNot(_.isInstanceOf[ServesRoutes]).flatMap { endpoint =>
      val requests = endpoint.routes.map(r => (r.method, r.template.render, r.acl, r.shape))
      val streams  = endpoint.streamRoutes.map(r => (r.method, r.template.render, r.acl, r.shape))
      (requests ++ streams).collect {
        case (method, template, acl, shape) if !reserved(endpoint.prefix + template) =>
          operation(
            endpoint,
            method,
            endpoint.prefix + template,
            acl.getOrElse(endpoint.acl),
            shape
          )
      }
    }
    val problems = built.flatMap(_.problems)
    if problems.nonEmpty then Left(problems)
    else
      select(built, selection).flatMap { kept =>
        val strictly =
          if selection.strict then
            kept
              .map(_.operation)
              .filter(_.incomplete)
              .map(o => s"${o.describe}: incomplete, lacks ${o.lacks.mkString(", ")}")
          else Vector.empty
        if strictly.nonEmpty then Left(strictly)
        else
          val all     = (kept.flatMap(_.definitions) :+ problem).distinctBy(_.name)
          val reached = reachable(kept.map(_.operation), all)
          SchemaNames.assign(reached.filterNot(_.name == ProblemName).map(_.name)).map { names =>
            Document(
              name,
              if version.isEmpty then "0.0.0" else version,
              kept
                .map(_.operation)
                .sortBy(o => (o.path, Document.Methods.indexOf(o.method.toLowerCase))),
              reached,
              names + (ProblemName -> SchemaNames.Problem)
            )
          }
      }

  /** A path the platform serves for itself, never a service's to document. */
  private def reserved(path: String): Boolean = path == "/_ankka" || path.startsWith("/_ankka/")

  private def select(
      built: Vector[Built],
      selection: Selection
  ): Either[Vector[String], Vector[Built]] =
    val tagged =
      built.filter(b => selection.tags.isEmpty || b.operation.tags.exists(selection.tags.contains))
    val under = tagged.filter(b =>
      selection.prefixes.isEmpty || selection.prefixes.exists(p => within(b.operation.path, p))
    )
    if selection.tags.nonEmpty && tagged.isEmpty then
      Left(selection.tags.map(t => s"""no route carries the tag "$t""""))
    else if selection.prefixes.nonEmpty && under.isEmpty then
      Left(selection.prefixes.map(p => s"""no route is under "$p""""))
    else Right(under)

  private def within(path: String, prefix: String): Boolean =
    val p = prefix.stripSuffix("/")
    path == p || path.startsWith(p + "/")

  /** The named shapes the operations refer to, and everything those refer to in turn. */
  private def reachable(operations: Vector[Operation], all: Vector[Named]): Vector[Named] =
    val byName = all.map(n => n.name -> n).toMap
    val roots  = operations.flatMap { o =>
      (o.body.toVector ++ o.answers.flatMap(_.content)).flatMap(_.shape).flatMap(Shape.references)
    }
    val seen                        = scala.collection.mutable.LinkedHashSet.empty[TypeName]
    def visit(name: TypeName): Unit =
      if seen.add(name) then byName.get(name).foreach(n => Shape.references(n.shape).foreach(visit))
    roots.foreach(visit)
    seen.toVector.flatMap(byName.get)

  private def access(acl: Acl): Access = acl match
    case Acl.AllowAll => Access.Open
    case other        =>
      val tokens = Acl.flatten(other).exists {
        case Acl.Authenticate(_: TokenAuthenticator) => true
        case _                                       => false
      }
      if tokens then Access.Token else Access.NotOpen

  /** A body's or an answer's content, from its content type and its shape. */
  private def contentOf(contentType: String, shape: Option[ShapeOf[?]]): Content =
    val media = contentType.takeWhile(_ != ';').trim
    Content(media, shape.map(_.shape).orElse(Option.when(media.startsWith("text/"))(Shape.string)))

  private def operation(
      endpoint: HttpEndpoint,
      method: String,
      path: String,
      acl: Acl,
      shape: RouteShape
  ): Built =
    val described = endpoint.endpointDescription.andThen(shape.description)
    val where     = s"$method $path"

    val body = shape.body.map { b =>
      if b.streamed then Content("application/octet-stream", None) -> Option.empty[String]
      else contentOf(b.contentType, b.shape) -> Option.when(b.shape.isEmpty)("request body")
    }

    val answer  = shape.answer
    val status  = answer.status.fold("default")(_.toString)
    val derived = answer.kind match
      case AnswerKind.Nothing => Answer(status, "", None)                                    -> None
      case AnswerKind.Stream  => Answer(status, "", Some(Content(answer.contentType, None))) -> None
      case AnswerKind.Events => Answer(status, "", Some(Content("text/event-stream", None))) -> None
      case AnswerKind.Body   =>
        Answer(status, "", Some(contentOf(answer.contentType, answer.shape))) ->
          Option.when(answer.shape.isEmpty)(s"answer $status")

    val refused =
      Option.when(status != "default")(
        Answer("default", "refused", Some(Content(Json, Some(Shape.Ref(ProblemName)))))
      )

    val parameters =
      shape.pathParameters.map((n, kind) => Parameter(n, "path", required = true, kind))

    val shapes      = shape.body.flatMap(_.shape).toVector ++ answer.shape.toVector
    val definitions = shapes.flatMap(_.definitions)
    val raw         = (shapes.map(_.shape) ++ definitions.map(_.shape)).flatMap(Shape.problems)

    Built(
      Operation(
        method,
        path,
        described.summaries.headOption,
        described.tags.distinct,
        parameters,
        body.map(_._1),
        derived._1 +: refused.toVector,
        access(acl),
        body.flatMap(_._2).toVector ++ derived._2.toVector
      ),
      definitions,
      raw.map(p => s"$where: $p")
    )
