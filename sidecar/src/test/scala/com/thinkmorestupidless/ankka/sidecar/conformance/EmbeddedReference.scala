package com.thinkmorestupidless.ankka.sidecar.conformance

import com.thinkmorestupidless.ankka.testkit.ClasspathReference
import com.thinkmorestupidless.ankka.core.ComponentDescriptor
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients, HttpEndpoint}
import com.thinkmorestupidless.ankka.javasdk.conformance.ConformanceEntry
import com.thinkmorestupidless.ankka.runtime.RuntimeExtension
import com.thinkmorestupidless.ankka.sdk.TimerScheduler

import java.nio.file.Path

/**
 * A reference service the runtime in this JVM hosts itself, with no sidecar and no protocol: the
 * Scala one, or one written through the Java SDK. Every embedded reference declares the same
 * components, routes and wire names; what differs is only how its components are built.
 */
trait EmbeddedReference:

  /** The language the reference is written in, as the suite's report names it. */
  def language: String

  /** Its components, beside which the target registers the agent and MCP runtimes' own. */
  def descriptors: Seq[ComponentDescriptor]

  /** Its routes, apart from the MCP endpoints, given the target's timers and problems. */
  def endpoints(
      timers: () => TimerScheduler,
      problems: () => Vector[String]
  ): Seq[EndpointClients => HttpEndpoint]

  /** Its MCP endpoints, behind client keys or a token `authenticated` admits. */
  def mcpEndpoints(authenticated: Acl): Seq[EndpointClients => HttpEndpoint]

  /** Extensions the reference needs beyond the ones every embedded target starts. */
  def extensions: Seq[RuntimeExtension] = Seq.empty

  /** The handlers it declares read only, as (component, handler). */
  def readOnlyHandlers: Set[(String, String)]

  /**
   * One call of `contract-relay`'s handler on an instance of its own, outside the runtime: the
   * number the message it produces carries, or `None` when it produces nothing.
   */
  def relay(n: Int): Option[Int]

  /** Cases this reference cannot run yet, by name, each with the reason the suite reports. */
  def ranOutside: Map[String, String] = Map.empty

object EmbeddedReference:

  /** The class `-Dankka.conformance.reference=java` names: the Java reference's entry. */
  val JavaEntry = "com.thinkmorestupidless.ankka.javasdk.reference.JavaAgentReference"

  /** The reference `ankka.conformance.reference` names; `scala` when it names none. */
  def fromProperty: EmbeddedReference =
    named(sys.props.get("ankka.conformance.reference"))

  /**
   * `scala`, `java`, or `classpath:<file>:<class>`, a class implementing `ConformanceEntry` in a
   * jar, a directory, or a `.txt` listing several (`ClasspathReference`). Anything else is an
   * error, never the Scala reference: a run that asked for another language and drove Scala would
   * report Scala's result as that language's.
   */
  def named(value: Option[String]): EmbeddedReference =
    value.map(_.trim).filter(_.nonEmpty).getOrElse("scala") match
      case "scala" => ConformanceReference.Embedded
      case "java"  => adapt(entry(getClass.getClassLoader, JavaEntry))
      case other if other.startsWith("classpath:") =>
        other.stripPrefix("classpath:").split(":", 2) match
          case Array(file, cls) if file.nonEmpty && cls.nonEmpty =>
            adapt(entry(ClasspathReference.loader(Path.of(file), getClass.getClassLoader), cls))
          case _ =>
            throw IllegalArgumentException(
              s"ankka.conformance.reference=$other: expected classpath:<file>:<class>"
            )
      case other =>
        throw IllegalArgumentException(
          s"ankka.conformance.reference=$other names no reference: " +
            "scala, java, or classpath:<file>:<class>"
        )

  private def entry(loader: ClassLoader, name: String): ConformanceEntry =
    val cls =
      try Class.forName(name, true, loader)
      catch
        case _: ClassNotFoundException =>
          throw IllegalArgumentException(s"ankka.conformance.reference: no class $name")
    if !classOf[ConformanceEntry].isAssignableFrom(cls) then
      throw IllegalArgumentException(
        s"ankka.conformance.reference: $name does not implement ConformanceEntry"
      )
    cls.getDeclaredConstructor().newInstance().asInstanceOf[ConformanceEntry]

  /** A Java entry as an embedded reference. */
  private def adapt(entry: ConformanceEntry): EmbeddedReference = JavaReference(entry)
