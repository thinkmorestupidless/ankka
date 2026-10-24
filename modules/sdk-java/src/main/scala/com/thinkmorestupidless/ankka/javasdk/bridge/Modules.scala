package com.thinkmorestupidless.ankka.javasdk.bridge

import com.thinkmorestupidless.ankka.core.ComponentDescriptor
import com.thinkmorestupidless.ankka.javasdk.problems.DeclarationProblem
import com.thinkmorestupidless.ankka.javasdk.spi.ServiceModule
import com.thinkmorestupidless.ankka.runtime

/**
 * What a `ServiceModule` is to the read: the agent library's module, say. It claims the registered
 * classes of its kinds and reads them, collecting every problem, as the SDK reads its own.
 */
private[ankka] trait JavaModule extends ServiceModule:

  /** What a developer is told to give when a class of this module's is registered without it. */
  def name: String

  /** Whether `cls` is one of this module's kinds. */
  def claims(cls: Class[?]): Boolean

  /** Every claimed class read, or every problem found. */
  def read(
      classes: Vector[(Class[?], Option[String])],
      serializers: Declarations.Serializers
  ): Either[Vector[DeclarationProblem], ModulePlan]

/** A module's classes, read. */
private[ankka] trait ModulePlan:

  /** The classes it registered: a call to one is a call to a registered component. */
  def classes: Set[Class[?]]

  /** Its components, given the refs every Java client the components hold resolves calls with. */
  def descriptors(refs: Refs, serializers: Declarations.Serializers): Vector[ComponentDescriptor]

  /** The platform's extensions its components need beside the service's own. */
  def extensions: Vector[runtime.RuntimeExtension]

  def needsProjections: Boolean
  def needsTimers: Boolean

private[ankka] object Modules:

  /**
   * The modules the SDK's libraries offer, by the base class of their kind, so a class registered
   * without its module is refused naming the module rather than as "not a component".
   */
  private val Known: Vector[(String, String)] = Vector(
    "com.thinkmorestupidless.ankka.agent.javasdk.Agent"           -> "Agents.module()",
    "com.thinkmorestupidless.ankka.agent.javasdk.AutonomousAgent" -> "Agents.module()"
  )

  /** The module `cls` needs, when it is one of a library's kinds. */
  def needed(cls: Class[?]): Option[String] =
    Iterator
      .iterate[Class[?] | Null](cls.getSuperclass)(c => if c == null then null else c.getSuperclass)
      .takeWhile(_ != null)
      .map(_.nn.getName)
      .flatMap(n => Known.collectFirst { case (base, module) if base == n => module })
      .nextOption()

  def asJava(module: ServiceModule): JavaModule = module match
    case m: JavaModule => m
    case other         =>
      throw IllegalArgumentException(s"$other is not a module from one of the SDK's libraries")
