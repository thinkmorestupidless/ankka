package com.thinkmorestupidless.ankka.javasdk.bridge

import com.thinkmorestupidless.ankka.grpc as sgrpc
import com.thinkmorestupidless.ankka.http as shttp
import com.thinkmorestupidless.ankka.javasdk.client.{
  CommandException,
  ComponentClient,
  ServiceClients,
  ViewClient
}
import com.thinkmorestupidless.ankka.javasdk.grpc.{GrpcClients, GrpcEndpoint}
import com.thinkmorestupidless.ankka.javasdk.http.{Acl, Caller, ProblemException}
import com.thinkmorestupidless.ankka.javasdk.problems.DeclarationProblem
import com.thinkmorestupidless.ankka.javasdk.secret.SecretStore
import com.thinkmorestupidless.ankka.javasdk.storage.ObjectStorage
import io.grpc.{Channel, MethodDescriptor, ServiceDescriptor}
import org.apache.pekko.stream.scaladsl.JavaFlowSupport

import java.lang.reflect.{InvocationTargetException, Modifier}
import java.util.Optional
import java.util.concurrent.Flow
import java.util.function.Function as JFunction
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** A Java gRPC endpoint class, read once. */
private[ankka] final case class GrpcPlan(cls: Class[?], construct: () => AnyRef)

/** One method a Java gRPC endpoint declared. */
private final case class GrpcDeclared(
    method: MethodDescriptor[Any, Any],
    kind: GrpcDeclared.Kind,
    acl: Option[Acl]
)

private object GrpcDeclared:
  enum Kind:
    case Unary(run: JFunction[Any, Any])
    case ServerStream(run: JFunction[Any, Flow.Publisher[Any]])
    case ClientStream(run: JFunction[java.util.Iterator[Any], Any])
    case BidiStream(run: JFunction[java.util.Iterator[Any], Flow.Publisher[Any]])

/**
 * What a Java gRPC endpoint declares and reads: its service and methods, collected while its
 * constructor runs, and what its handlers may call. Handed to the constructor through a
 * thread-local, as an HTTP endpoint's cell is.
 */
final class GrpcCell private[bridge] (
    client: ComponentClient = Clients.unavailable,
    serviceClients: ServiceClients = Services.unavailable,
    views: ViewClient = JavaViews.unavailable,
    secretStore: SecretStore = Stores.noSecrets,
    objectStorage: ObjectStorage = Stores.noStorage
):
  private[bridge] var service: Option[ServiceDescriptor] = None
  private[bridge] val declared                           = mutable.ListBuffer.empty[GrpcDeclared]
  private var scoped: Option[Acl]                        = None

  def bind(descriptor: ServiceDescriptor): Unit = service = Some(descriptor)

  private def add(method: MethodDescriptor[?, ?], kind: GrpcDeclared.Kind): Unit =
    declared += GrpcDeclared(method.asInstanceOf[MethodDescriptor[Any, Any]], kind, scoped)

  def unary[Q, R](method: MethodDescriptor[Q, R], handler: JFunction[Q, R]): Unit =
    add(method, GrpcDeclared.Kind.Unary(handler.asInstanceOf[JFunction[Any, Any]]))

  def serverStream[Q, R](
      method: MethodDescriptor[Q, R],
      handler: JFunction[Q, Flow.Publisher[R]]
  ): Unit =
    add(
      method,
      GrpcDeclared.Kind.ServerStream(handler.asInstanceOf[JFunction[Any, Flow.Publisher[Any]]])
    )

  def clientStream[Q, R](
      method: MethodDescriptor[Q, R],
      handler: JFunction[java.util.Iterator[Q], R]
  ): Unit =
    add(
      method,
      GrpcDeclared.Kind.ClientStream(handler.asInstanceOf[JFunction[java.util.Iterator[Any], Any]])
    )

  def bidiStream[Q, R](
      method: MethodDescriptor[Q, R],
      handler: JFunction[java.util.Iterator[Q], Flow.Publisher[R]]
  ): Unit =
    add(
      method,
      GrpcDeclared.Kind.BidiStream(
        handler.asInstanceOf[JFunction[java.util.Iterator[Any], Flow.Publisher[Any]]]
      )
    )

  def withAcl(acl: Acl, declare: Runnable): Unit =
    val enclosing = scoped
    scoped = Some(acl)
    try declare.run()
    finally scoped = enclosing

  private def call: shttp.RequestContext =
    shttp.RequestScope.currentContext.getOrElse(
      throw IllegalStateException("the call is only available inside a handler, on its own thread")
    )

  def caller(): Caller = Acls.javaCaller(call.caller)

  def metadata(key: String): Optional[String] =
    call.header(key).fold(Optional.empty[String]())(Optional.of)

  def componentClient(): ComponentClient = client
  def viewClient(): ViewClient           = views
  def services(): ServiceClients         = serviceClients
  def secrets(): SecretStore             = secretStore
  def storage(): ObjectStorage           = objectStorage

object GrpcCell:
  private val constructing = ThreadLocal[GrpcCell]()

  def take(): GrpcCell =
    val cell = constructing.get()
    if cell == null then GrpcCell()
    else
      constructing.remove()
      cell

  private[bridge] def constructing[A](cell: GrpcCell)(build: => A): A =
    constructing.set(cell)
    try build
    finally constructing.remove()

private[ankka] object Grpc:

  def read(cls: Class[?]): Either[Vector[DeclarationProblem], GrpcPlan] =
    val problems = Vector.newBuilder[DeclarationProblem]
    def problem(message: String): Unit =
      problems += DeclarationProblem(cls.getSimpleName, "", message)
    if !classOf[GrpcEndpoint].isAssignableFrom(cls) then
      problem("is not a gRPC endpoint: extend GrpcEndpoint")
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
    if found.nonEmpty then Left(found) else Right(GrpcPlan(cls, construct.get))

  /** The Scala endpoint of a Java one, built for the clients the server hands it. */
  def scalaEndpoint(plan: GrpcPlan, cell: GrpcCell): sgrpc.GrpcEndpoint =
    val java = GrpcCell.constructing(cell)(plan.construct()).asInstanceOf[GrpcEndpoint]
    val service = cell.service.getOrElse(
      throw IllegalStateException(s"${plan.cls.getSimpleName} named no service descriptor")
    )
    ScalaGrpcEndpoint(service, Acls.scalaOf(GrpcAcl(java)), cell.declared.toVector)

  /** A Java handler's refusal as the command error whose status the caller is answered. */
  private[bridge] def refusals[A](run: => A): A =
    try run
    catch
      case e: CommandException => throw Effects.toCommandError(e.problem)
      case e: ProblemException => throw Effects.toCommandError(e.problem)

  def clients(scala: sgrpc.GrpcClients): GrpcClients = new GrpcClients:
    def channel(service: String): Channel                  = scala(service)
    def channel(project: String, service: String): Channel = scala(project, service)

  val noClients: GrpcClients = new GrpcClients:
    private def refuse = throw IllegalStateException(
      "this service calls no gRPC: start it with withGrpcClients()"
    )
    def channel(service: String): Channel                  = refuse
    def channel(project: String, service: String): Channel = refuse

private final class ScalaGrpcEndpoint(
    service: ServiceDescriptor,
    endpointAcl: shttp.Acl,
    declared: Vector[GrpcDeclared]
) extends sgrpc.GrpcEndpoint(service):
  import Grpc.refusals

  def acl: shttp.Acl = endpointAcl

  declared.foreach { d =>
    d.acl match
      case Some(a) => withAcl(Acls.scalaOf(a))(declare(d))
      case None    => declare(d)
  }

  private def declare(d: GrpcDeclared): Unit = d.kind match
    case GrpcDeclared.Kind.Unary(run) =>
      unary(d.method)(request => refusals(run.apply(request)))
    case GrpcDeclared.Kind.ServerStream(run) =>
      serverStream(d.method)(request =>
        JavaFlowSupport.Source.fromPublisher(refusals(run.apply(request)))
      )
    case GrpcDeclared.Kind.ClientStream(run) =>
      clientStream(d.method)(requests => refusals(run.apply(requests.asJava)))
    case GrpcDeclared.Kind.BidiStream(run) =>
      bidiStream(d.method)(requests =>
        JavaFlowSupport.Source.fromPublisher(refusals(run.apply(requests.asJava)))
      )

/** Reads a gRPC endpoint's protected `acl()`. */
private object GrpcAcl:
  private val method =
    val m = classOf[GrpcEndpoint].getDeclaredMethod("acl")
    m.setAccessible(true)
    m

  def apply(instance: GrpcEndpoint): Acl =
    try method.invoke(instance).asInstanceOf[Acl]
    catch case e: InvocationTargetException => throw e.getCause
