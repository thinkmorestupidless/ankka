package com.thinkmorestupidless.ankka.javasdk.bridge

import com.thinkmorestupidless.ankka.javasdk.{AnkkaService, ServiceBuilder}
import com.thinkmorestupidless.ankka.javasdk.client.ComponentClient
import com.thinkmorestupidless.ankka.javasdk.codec.Serializer
import com.thinkmorestupidless.ankka.javasdk.problems.{DeclarationProblem, ServiceProblems}
import com.thinkmorestupidless.ankka.runtime
import com.thinkmorestupidless.ankka.core.ComponentDescriptor

import java.time.Duration
import java.util.concurrent.CompletionStage
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import scala.jdk.FutureConverters.*

/** A Java service definition: the classes it names, read at start. */
final class JavaServiceBuilder private (
    val name: String,
    private val classes: Vector[(Class[?], Option[String])],
    private val serializers: Declarations.Serializers
) extends ServiceBuilder:

  def this(name: String) = this(name, Vector.empty, Map.empty)

  def register(componentClass: Class[?]): ServiceBuilder =
    JavaServiceBuilder(name, classes :+ (componentClass -> None), serializers)

  def register(componentClass: Class[?], componentId: String): ServiceBuilder =
    JavaServiceBuilder(name, classes :+ (componentClass -> Some(componentId)), serializers)

  def withSerializer[A](tpe: Class[A], serializer: Serializer[A]): ServiceBuilder =
    JavaServiceBuilder(name, classes, serializers + (tpe -> serializer))

  def start(): AnkkaService =
    val read    = JavaServiceBuilder.read(classes, serializers)
    val service = JavaServiceBuilder.scalaBuilder(read).start()
    JavaAnkkaService(name, service, read.refs)

private[ankka] object JavaServiceBuilder:

  /** Every class read, its descriptor built, and the refs that resolve calls to its handlers. */
  final case class Read(
      plans: Vector[ComponentPlan],
      descriptors: Vector[ComponentDescriptor],
      refs: Refs
  )

  /** Reads every class, or throws every problem at once. */
  def read(
      classes: Vector[(Class[?], Option[String])],
      serializers: Declarations.Serializers
  ): Read =
    val results  = classes.map((cls, id) => Declarations.read(cls, id, serializers))
    val problems = results.collect { case Left(ps) => ps }.flatten
    if problems.nonEmpty then throw ServiceProblems(problems.asJava)
    val plans       = results.collect { case Right(plan) => plan }
    val refs        = Refs(plans)
    val descriptors = plans.map(Adapters.descriptor(_, refs))
    Read(plans, descriptors, refs)

  /** The Scala service of the read classes, its own checks reported as declaration problems. */
  def scalaBuilder(read: Read): runtime.ServiceBuilder =
    val builder = runtime.Ankka.service.registerAll(read.descriptors)
    builder.validate match
      case Left(problems) =>
        throw ServiceProblems(problems.map(p => DeclarationProblem("service", "", p)).asJava)
      case Right(_) => builder

/** A running Java service. */
private[ankka] final class JavaAnkkaService(
    name: String,
    val scala: runtime.AnkkaService,
    refs: Refs
) extends AnkkaService:
  private val client = Clients.of(scala.componentClient, refs)

  def componentClient(): ComponentClient  = client
  def awaitReady(): Unit                  = scala.awaitReady()
  def awaitReady(timeout: Duration): Unit = scala.awaitReady(Effects.finite(timeout))
  def whenTerminated(): CompletionStage[Void] =
    scala.whenTerminated.map(_ => null.asInstanceOf[Void])(using ExecutionContext.parasitic).asJava
  def terminate(): Unit         = scala.terminate()
  override def toString: String = s"AnkkaService($name)"
  def close(): Unit =
    scala.terminate()
    Await.ready(scala.whenTerminated, 30.seconds): Unit
