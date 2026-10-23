package com.thinkmorestupidless.ankka.javasdk.bridge

import com.thinkmorestupidless.ankka.http
import com.thinkmorestupidless.ankka.http.{EncodedResponse, HttpProblem, PathTemplate, Route}
import com.thinkmorestupidless.ankka.javasdk.Done
import com.thinkmorestupidless.ankka.javasdk.annotations
import com.thinkmorestupidless.ankka.javasdk.client.CommandException
import com.thinkmorestupidless.ankka.javasdk.codec.{EncodingException, RecordCodec, Serializer}
import com.thinkmorestupidless.ankka.javasdk.http.{
  Caller,
  HttpEndpoint,
  Problem,
  ProblemException,
  Request,
  Response
}
import com.thinkmorestupidless.ankka.javasdk.problems.DeclarationProblem

import java.lang.reflect.{
  InvocationTargetException,
  Method,
  Modifier,
  Parameter,
  ParameterizedType,
  Type
}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.{Optional, UUID}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** How a route's parameter is filled from a request. */
private[bridge] type Binder = (Map[String, String], Array[Byte], http.RequestContext) => AnyRef

/** One route, read from its method. */
private[ankka] final case class RoutePlan(
    verb: String,
    template: PathTemplate,
    method: Method,
    binders: Vector[Binder],
    needsBody: Boolean,
    reply: Option[Serializer[Any]]
)

/** An endpoint class, read once: its routes, and how to build it. */
private[ankka] final case class EndpointPlan(
    cls: Class[?],
    routes: Vector[RoutePlan],
    construct: () => AnyRef
)

/** Reads an endpoint class's routes once, at start, collecting every problem. */
private[ankka] object Routes:

  private val verbs
      : Vector[(Class[? <: java.lang.annotation.Annotation], String, Method => String)] =
    Vector(
      (classOf[annotations.Get], "GET", _.getAnnotation(classOf[annotations.Get]).value),
      (classOf[annotations.Post], "POST", _.getAnnotation(classOf[annotations.Post]).value),
      (classOf[annotations.Put], "PUT", _.getAnnotation(classOf[annotations.Put]).value),
      (classOf[annotations.Delete], "DELETE", _.getAnnotation(classOf[annotations.Delete]).value),
      (classOf[annotations.Patch], "PATCH", _.getAnnotation(classOf[annotations.Patch]).value)
    )

  def read(
      cls: Class[?],
      serializers: Declarations.Serializers
  ): Either[Vector[DeclarationProblem], EndpointPlan] =
    val name     = cls.getSimpleName
    val problems = mutable.ArrayBuffer.empty[DeclarationProblem]
    def problem(member: String, message: String): Unit =
      problems += DeclarationProblem(name, member, message)

    if !classOf[HttpEndpoint].isAssignableFrom(cls) then
      problem("", "is not an endpoint: extend HttpEndpoint")
    if Modifier.isAbstract(cls.getModifiers) then
      problem("", "is abstract; register a concrete class")
    val construct = cls.getDeclaredConstructors.find(_.getParameterCount == 0) match
      case Some(c) =>
        c.setAccessible(true)
        Some(() =>
          try c.newInstance().asInstanceOf[AnyRef]
          catch case e: InvocationTargetException => throw e.getCause
        )
      case None =>
        problem("", "has no constructor taking nothing")
        None

    def serializer(t: Type): Either[String, Serializer[Any]] =
      Planner.rawClass(t).flatMap(serializers.get) match
        case Some(s) => Right(s.asInstanceOf[Serializer[Any]])
        case None =>
          try Right(RecordCodec.forType(t).asInstanceOf[Serializer[Any]])
          catch case e: EncodingException => Left(e.getMessage)

    def replyOf(m: Method): Either[String, Option[Serializer[Any]]] =
      val raw = m.getReturnType
      if raw == java.lang.Void.TYPE || raw == classOf[Void] || raw == classOf[Done] ||
        raw == classOf[Response]
      then Right(None)
      else
        serializer(m.getGenericReturnType)
          .map(s => Option(s))
          .left
          .map(why => s"its reply has no serializer: $why")

    val routes = mutable.ArrayBuffer.empty[RoutePlan]
    val seen   = mutable.Map.empty[(String, String), String]
    cls.getDeclaredMethods.sortBy(_.getName).foreach { m =>
      val declared = verbs.filter((a, _, _) => m.isAnnotationPresent(a))
      if declared.sizeIs > 1 then problem(m.getName, "has more than one verb annotation")
      declared.headOption.foreach { (_, verb, raw) =>
        val member = m.getName
        val parsed =
          try Some(PathTemplate.parse(raw(m)))
          catch
            case e: IllegalArgumentException =>
              problem(member, e.getMessage)
              None
        parsed.foreach { template =>
          // Two templates differing only in their parameters' names match the same paths.
          val key = verb -> template.segments
            .map {
              case http.PathSegment.Literal(v) => v
              case http.PathSegment.Param(_)   => "{}"
            }
            .mkString("/")
          seen.get(key) match
            case Some(other) => problem(member, s"$verb ${template.render} is also $other's route")
            case None        => seen(key) = member
          if Modifier.isStatic(m.getModifiers) then
            problem(member, "is static; a route is an instance method")
          val placeholders = template.parameterNames.toSet
          val bound        = mutable.Set.empty[String]
          var bodies       = 0
          val binders = m.getParameters.toVector.flatMap { p =>
            binder(p, placeholders, serializer) match
              case Right((b, path, body)) =>
                path.foreach(bound += _)
                if body then bodies += 1
                Some(b)
              case Left(why) =>
                problem(member, why)
                None
          }
          if bodies > 1 then problem(member, "has more than one @Body")
          (placeholders -- bound)
            .foreach(p => problem(member, s"binds nothing to {$p}: add a parameter @Path(\"$p\")"))
          val reply = replyOf(m) match
            case Right(r) => r
            case Left(why) =>
              problem(member, why)
              None
          m.setAccessible(true)
          routes += RoutePlan(verb, template, m, binders, bodies > 0, reply)
        }
      }
    }
    if problems.nonEmpty then Left(problems.toVector)
    else Right(EndpointPlan(cls, routes.toVector, construct.get))

  /** A parameter's binder, the template segment it reads if any, and whether it is the body. */
  private def binder(
      p: Parameter,
      placeholders: Set[String],
      serializer: Type => Either[String, Serializer[Any]]
  ): Either[String, (Binder, Option[String], Boolean)] =
    val tpe = p.getParameterizedType
    Option(p.getAnnotation(classOf[annotations.Path]))
      .map(_.value)
      .orElse(
        Option.when(
          p.isNamePresent && placeholders.contains(p.getName) &&
            noBindingAnnotation(p)
        )(p.getName)
      ) match
      case Some(segment) =>
        if !placeholders.contains(segment) then
          Left(s"@Path(\"$segment\") is not in the route's template")
        else
          converter(p.getType, segment).map(convert =>
            ((args, _, _) => convert(args(segment)), Some(segment), false)
          )
      case None =>
        if p.isAnnotationPresent(classOf[annotations.QueryParam]) then
          val name = p.getAnnotation(classOf[annotations.QueryParam]).value
          optional(p, tpe, name, "query parameter", (ctx, n) => ctx.query.raw(n))
        else if p.isAnnotationPresent(classOf[annotations.Header]) then
          val name = p.getAnnotation(classOf[annotations.Header]).value
          optional(p, tpe, name, "header", (ctx, n) => ctx.header(n))
        else if p.isAnnotationPresent(classOf[annotations.Body]) then
          serializer(tpe) match
            case Right(s) =>
              Right(
                (
                  (_, body, _) =>
                    try s.fromBytes(body).asInstanceOf[AnyRef]
                    catch
                      case e: EncodingException =>
                        throw HttpProblem.badRequest(s"the body: ${e.getMessage}")
                  ,
                  None,
                  true
                )
              )
            case Left(why) => Left(s"its @Body has no serializer: $why")
        else if p.getType == classOf[Request] then
          Right(((_, _, ctx) => JavaRequest(ctx), None, false))
        else if p.getType == classOf[Caller] then
          Right(((_, _, ctx) => Acls.javaCaller(ctx.caller), None, false))
        else
          Left(
            s"cannot bind parameter '${p.getName}': annotate it @Path, @QueryParam, @Header or @Body" +
              (if p.isNamePresent then "" else " (compile with -parameters to bind by name)")
          )

  private def noBindingAnnotation(p: Parameter): Boolean =
    !p.isAnnotationPresent(classOf[annotations.QueryParam]) &&
      !p.isAnnotationPresent(classOf[annotations.Header]) &&
      !p.isAnnotationPresent(classOf[annotations.Body])

  private def optional(
      p: Parameter,
      tpe: Type,
      name: String,
      what: String,
      read: (http.RequestContext, String) => Option[String]
  ): Either[String, (Binder, Option[String], Boolean)] =
    tpe match
      case pt: ParameterizedType if pt.getRawType == classOf[Optional[?]] =>
        val inner = pt.getActualTypeArguments()(0) match
          case c: Class[?] => c
          case _           => classOf[Object]
        converter(inner, name).map(convert =>
          (
            (_, _, ctx) =>
              read(ctx, name).map(convert).fold(Optional.empty[AnyRef]())(Optional.of(_)),
            None,
            false
          )
        )
      case _ =>
        converter(p.getType, name).map(convert =>
          (
            (_, _, ctx) =>
              convert(
                read(ctx, name).getOrElse(
                  throw HttpProblem.badRequest(s"$what '$name' is required")
                )
              ),
            None,
            false
          )
        )

  /** Text from a path, query or header as the parameter's type. */
  private def converter(c: Class[?], name: String): Either[String, String => AnyRef] =
    def parse[A <: AnyRef](f: String => A): Either[String, String => AnyRef] =
      Right(text =>
        try f(text)
        catch
          case _: IllegalArgumentException =>
            throw HttpProblem.badRequest(s"'$name' cannot be read as ${c.getSimpleName}: $text")
      )
    if c == classOf[String] then Right(identity)
    else if c == classOf[Int] || c == classOf[Integer] then parse(s => Integer.valueOf(s))
    else if c == classOf[Long] || c == classOf[java.lang.Long] then
      parse(s => java.lang.Long.valueOf(s))
    else if c == classOf[Double] || c == classOf[java.lang.Double] then
      parse(s => java.lang.Double.valueOf(s))
    else if c == classOf[Boolean] || c == classOf[java.lang.Boolean] then
      parse(s =>
        if s.equalsIgnoreCase("true") then java.lang.Boolean.TRUE
        else if s.equalsIgnoreCase("false") then java.lang.Boolean.FALSE
        else throw IllegalArgumentException(s)
      )
    else if c == classOf[UUID] then parse(UUID.fromString)
    else if c.isEnum then
      val constants =
        c.getEnumConstants.map(e => e.asInstanceOf[Enum[?]].name -> e.asInstanceOf[AnyRef]).toMap
      parse(s => constants.getOrElse(s, throw IllegalArgumentException(s)))
    else Left(s"'$name' is a ${c.getSimpleName}, which a path, query or header cannot hold")

  /** The Scala routes of a read endpoint, its instance built and its ACL asked once. */
  def scalaRoutes(
      plan: EndpointPlan,
      instance: HttpEndpoint,
      serializers: Declarations.Serializers
  ): Vector[Route] =
    val acl = Acls.scalaOf(EndpointAcl(instance))
    plan.routes.map { r =>
      Route(
        r.verb,
        r.template,
        r.needsBody,
        (args, body) =>
          val ctx =
            http.RequestScope.currentContext.getOrElse(throw IllegalStateException("no request"))
          val named  = r.template.parameterNames.zip(args).toMap
          val params = r.binders.map(_(named, body, ctx))
          respond(invoke(r.method, instance, params), r.reply, serializers)
        ,
        Some(acl)
      )
    }

  private def invoke(m: Method, instance: AnyRef, params: Vector[AnyRef]): Any =
    try m.invoke(instance, params*)
    catch
      case e: InvocationTargetException =>
        e.getCause match
          case p: ProblemException => throw problem(p.problem)
          case c: CommandException => throw problem(c.problem)
          case other               => throw other

  private def problem(p: Problem): HttpProblem =
    HttpProblem(p.status, if p.detail.isEmpty then p.title else p.detail)

  private def respond(
      value: Any,
      reply: Option[Serializer[Any]],
      serializers: Declarations.Serializers
  ): EncodedResponse =
    value match
      case null | _: Done => EncodedResponse(204, "text/plain", Array.emptyByteArray)
      case r: Response =>
        val headers = r.headers.asScala.toVector.map(e => e.getKey -> e.getValue)
        r.body.toScala match
          case None => EncodedResponse(r.statusCode, "text/plain", Array.emptyByteArray, headers)
          case Some(p: Problem) => throw problem(p)
          case Some(bytes: Array[Byte]) =>
            EncodedResponse(
              r.statusCode,
              r.contentType.orElse("application/octet-stream"),
              bytes,
              headers
            )
          case Some(text: String) if r.contentType.isPresent =>
            EncodedResponse(r.statusCode, r.contentType.get, text.getBytes(UTF_8), headers)
          case Some(body) =>
            val s = serializers
              .get(body.getClass)
              .map(_.asInstanceOf[Serializer[Any]])
              .getOrElse(RecordCodec.forType(body.getClass).asInstanceOf[Serializer[Any]])
            EncodedResponse(
              r.statusCode,
              r.contentType.orElse(s.contentType.mediaType),
              s.toBytes(body),
              headers
            )
      case other =>
        val s = reply.getOrElse(RecordCodec.forType(other.getClass).asInstanceOf[Serializer[Any]])
        EncodedResponse(200, s.contentType.mediaType, s.toBytes(other))

/** Every Java endpoint of a service, served as one Scala endpoint whose routes carry their ACLs. */
private[ankka] final class JavaHttpEndpoint(served: Vector[Route]) extends http.HttpEndpoint(""):
  def acl: http.Acl                                 = http.Acl.DenyAll
  override private[ankka] def routes: Vector[Route] = served

/** Reads an endpoint's protected `acl()`. */
private object EndpointAcl:
  private val method =
    val m = classOf[HttpEndpoint].getDeclaredMethod("acl")
    m.setAccessible(true)
    m

  def apply(instance: HttpEndpoint): com.thinkmorestupidless.ankka.javasdk.http.Acl =
    try method.invoke(instance).asInstanceOf[com.thinkmorestupidless.ankka.javasdk.http.Acl]
    catch case e: InvocationTargetException => throw e.getCause
