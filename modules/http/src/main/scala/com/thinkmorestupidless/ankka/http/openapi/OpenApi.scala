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
              .sortBy(o => (o.path, Document.Methods.indexOf(o.method.toLowerCase)))
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

  private def shown(shape: ShapeOf[?]): String = Shape.write(Shape.render(shape.shape, _.simple))

  /**
   * A described shape's content: under the route's own text type when it has one, else JSON (a
   * route answering bytes it built says through its description what they are).
   */
  private def describedContent(contentType: String, shape: ShapeOf[?]): Content =
    val media = contentType.takeWhile(_ != ';').trim
    Content(if media.startsWith("text/") then media else Json, Some(shape.shape))

  private def operation(
      endpoint: HttpEndpoint,
      method: String,
      path: String,
      acl: Acl,
      shape: RouteShape
  ): Built =
    val described                  = endpoint.endpointDescription.andThen(shape.description)
    val where                      = s"$method $path"
    val problems                   = Vector.newBuilder[String]
    def refuse(what: String): Unit = problems += s"$where: $what"

    // What only one route may say, said by an endpoint or by a scope around several routes.
    if endpoint.endpointDescription.namesOneRoute then
      refuse(
        "the endpoint's description gives a summary, a body or an answer, which only a route may"
      )
    shape.scopes.filter(s => s.held > 1 && s.description.namesOneRoute).foreach { s =>
      refuse(s"a summary, a body or an answer is described for ${s.held} routes at once")
    }
    if described.summaries.size > 1 then refuse("two summaries are described")

    val body = shape.body.map { b =>
      val stated = described.bodies.lastOption
      if described.bodies.size > 1 then refuse("two body shapes are described")
      (b.shape, stated) match
        case (Some(typed), Some(other)) if typed.shape != other.shape =>
          refuse(
            s"the body's two shapes differ: its type gives ${shown(typed)}, its description ${shown(other)}"
          )
        case _ => ()
      if b.streamed then
        Content("application/octet-stream", stated.map(_.shape)) -> Option.empty[String]
      else
        (b.shape, stated) match
          case (None, Some(d)) => describedContent(b.contentType, d) -> None
          case _               =>
            contentOf(b.contentType, b.shape) -> Option.when(b.shape.isEmpty)("request body")
    }
    if shape.body.isEmpty && described.bodies.nonEmpty then
      refuse("a body is described for a route that takes none")

    val answer = shape.answer
    val fixed  = answer.status.map(_.toString)
    described.answered
      .groupBy(_._1)
      .collect { case (s, more) if more.size > 1 => s }
      .toVector
      .sorted
      .foreach(s => refuse(s"the answer $s is described twice"))
    val byStatus = described.answered.map((s, d) => s.toString -> d).toMap

    // The answer the route's type gives; a status the handler decides is under `default` unless an
    // answer is described, when the described answers take its place.
    val derived: Vector[(Answer, Option[String])] =
      val status = fixed.getOrElse("default")
      if fixed.isEmpty && described.answered.nonEmpty then Vector.empty
      else
        Vector(answer.kind match
          case AnswerKind.Nothing => Answer(status, "", None) -> None
          case AnswerKind.Stream  =>
            Answer(status, "", Some(Content(answer.contentType, None))) -> None
          case AnswerKind.Events =>
            Answer(status, "", Some(Content("text/event-stream", None))) -> None
          case AnswerKind.Body =>
            (answer.shape, byStatus.get(status).flatten) match
              case (Some(typed), Some(other)) if typed.shape != other.shape =>
                refuse(
                  s"the answer $status's two shapes differ: its type gives ${shown(typed)}, its description ${shown(other)}"
                )
                Answer(status, "", Some(contentOf(answer.contentType, answer.shape))) -> None
              case (None, Some(d)) =>
                Answer(status, "", Some(describedContent(answer.contentType, d))) -> None
              case _ =>
                Answer(status, "", Some(contentOf(answer.contentType, answer.shape))) ->
                  Option.when(answer.shape.isEmpty)(s"answer $status"))
    val typedStatuses    = derived.map(_._1.status).toSet
    val describedAnswers = described.answered.distinctBy(_._1).collect {
      case (s, d) if !typedStatuses(s.toString) =>
        val content = d.map(shapeOf =>
          if fixed.isEmpty then
            Content(answer.contentType.takeWhile(_ != ';').trim, Some(shapeOf.shape))
          else describedContent(answer.contentType, shapeOf)
        )
        Answer(s.toString, "", content)
    }

    val refusalShape = described.refusalShapes.lastOption
    val refusalOf    = refusalShape.map(_.shape).getOrElse(Shape.Ref(ProblemName))
    val answered     = (derived.map(_._1.status) ++ describedAnswers.map(_.status)).toSet
    val refusals     = described.refusals.distinctBy(_._1).flatMap { (s, reason, own) =>
      if answered(s.toString) then
        refuse(s"the status $s is both an answer and the refusal \"$reason\"")
        None
      else Some(Answer(s.toString, reason, Some(Content(Json, Some(own.fold(refusalOf)(_.shape))))))
    }
    described.refusals
      .groupBy(_._1)
      .collect { case (s, more) if more.size > 1 => s }
      .toVector
      .sorted
      .foreach(s => refuse(s"the refusal $s is described twice"))
    val refused =
      Option.when(!answered("default"))(
        Answer("default", "refused", Some(Content(Json, Some(refusalOf))))
      )

    val pathNames = shape.pathParameters.map(_._1).toSet
    described.queries
      .groupBy(_.name)
      .collect { case (n, more) if more.size > 1 => n }
      .toVector
      .sorted
      .foreach(n => refuse(s"the query parameter \"$n\" is described twice"))
    described.queries.map(_.name).distinct.filter(pathNames).foreach { n =>
      refuse(s"the parameter \"$n\" is described as a query parameter and is one of the path's")
    }
    val parameters =
      shape.pathParameters.map((n, kind) => Parameter(n, "path", required = true, kind)) ++
        described.queries
          .distinctBy(_.name)
          .filterNot(q => pathNames(q.name))
          .map(q => Parameter(q.name, "query", q.required, q.kind))

    val shapes = shape.body.flatMap(_.shape).toVector ++ answer.shape.toVector ++
      described.bodies ++ described.answered.flatMap(_._2) ++ described.refusals.flatMap(_._3) ++
      described.refusalShapes
    val definitions = shapes.flatMap(_.definitions)
    val raw         = (shapes.map(_.shape) ++ definitions.map(_.shape)).flatMap(Shape.problems)
    raw.foreach(refuse)

    Built(
      Operation(
        method,
        path,
        described.summaries.headOption,
        described.tags.distinct,
        parameters,
        body.map(_._1),
        derived.map(_._1) ++ describedAnswers ++ refusals ++ refused.toVector,
        access(acl),
        body.flatMap(_._2).toVector ++ derived.flatMap(_._2)
      ),
      definitions,
      problems.result()
    )
