package com.thinkmorestupidless.ankka.javasdk.bridge

import com.thinkmorestupidless.ankka.core.{ComponentDescriptor, ComponentId}
import com.thinkmorestupidless.ankka.javasdk.component.{EventSourcedEntity, KeyValueEntity}
import com.thinkmorestupidless.ankka.sdk
import com.thinkmorestupidless.ankka.sdk.*

import java.lang.reflect.{InvocationTargetException, Method}

/**
 * A Java component as the Scala hosts see it: an adapter around the Java instance, built by the
 * descriptor's `create` and driven through the same hooks a Scala component is.
 */
private[ankka] object Adapters:

  def descriptor(plan: ComponentPlan, refs: Refs): ComponentDescriptor = plan.kind match
    case JavaKind.EventSourced => eventSourced(plan, refs)
    case JavaKind.KeyValue     => keyValue(plan, refs)

  def eventSourced(
      plan: ComponentPlan,
      refs: Refs
  ): EventSourcedEntityDescriptor[JavaEventSourcedHost, Any, Any] =
    eventSourcedCompanion(plan, refs).descriptor

  def keyValue(plan: ComponentPlan, refs: Refs): KeyValueEntityDescriptor[JavaKeyValueHost, Any] =
    keyValueCompanion(plan, refs).descriptor

  /**
   * A Java event sourced entity as the Scala SDK's own companion: its handlers declared through
   * `command` and `query` as a Scala entity's are, so the runtime and the Scala test kits take it
   * unchanged.
   */
  def eventSourcedCompanion(
      plan: ComponentPlan,
      refs: Refs
  ): sdk.EventSourcedEntity.Companion[JavaEventSourcedHost, Any, Any] =
    new sdk.EventSourcedEntity.Companion[JavaEventSourcedHost, Any, Any](
      ComponentId(plan.componentId),
      CodecBridge.state(plan.state),
      CodecBridge.toScala(plan.event.get)
    ):
      def create(ctx: EventSourcedEntityContext): JavaEventSourcedHost =
        val cell = EntityCell(ctx.entityId, plan.componentId, Clients.of(ctx.componentClient, refs))
        JavaEventSourcedHost(construct(plan, cell), cell)

      plan.handlers.foreach { h =>
        val run = invoker(plan, h)
        val out = CodecBridge.toScala(h.output)
        (h.input, h.readOnly) match
          case (Some(in), false) =>
            command[Any, Any](h.wireName)(host => i => effect(run(host.java, i)))(using
              CodecBridge.toScala(in),
              out
            ): Unit
          case (Some(in), true) =>
            query[Any, Any](h.wireName)(host => i => effect(run(host.java, i)))(using
              CodecBridge.toScala(in),
              out
            ): Unit
          case (None, false) =>
            command[Any](h.wireName)(host => effect(run(host.java, null)))(using out): Unit
          case (None, true) =>
            query[Any](h.wireName)(host => effect(run(host.java, null)))(using out): Unit
      }

  /** A Java key value entity as the Scala SDK's own companion. */
  def keyValueCompanion(
      plan: ComponentPlan,
      refs: Refs
  ): sdk.KeyValueEntity.Companion[JavaKeyValueHost, Any] =
    new sdk.KeyValueEntity.Companion[JavaKeyValueHost, Any](
      ComponentId(plan.componentId),
      CodecBridge.state(plan.state)
    ):
      def create(ctx: KeyValueEntityContext): JavaKeyValueHost =
        val cell = EntityCell(ctx.entityId, plan.componentId, Clients.of(ctx.componentClient, refs))
        JavaKeyValueHost(construct(plan, cell), cell)

      plan.handlers.foreach { h =>
        val run = invoker(plan, h)
        val out = CodecBridge.toScala(h.output)
        (h.input, h.readOnly) match
          case (Some(in), false) =>
            command[Any, Any](h.wireName)(host => i => effect(run(host.java, i)))(using
              CodecBridge.toScala(in),
              out
            ): Unit
          case (Some(in), true) =>
            query[Any, Any](h.wireName)(host => i => effect(run(host.java, i)))(using
              CodecBridge.toScala(in),
              out
            ): Unit
          case (None, false) =>
            command[Any](h.wireName)(host => effect(run(host.java, null)))(using out): Unit
          case (None, true) =>
            query[Any](h.wireName)(host => effect(run(host.java, null)))(using out): Unit
      }

  /** The Scala effect a Java handler returned, as whichever effect type the caller expects. */
  private def effect[A](value: Any): A = value.asInstanceOf[A]

  private def invoker(plan: ComponentPlan, h: HandlerPlan): (AnyRef, Any) => Any =
    val where = s"${plan.cls.getSimpleName}.${h.method.getName}"
    (java, input) => Effects.unwrap(h.invoke(java, input), where)

  private[bridge] def construct(plan: ComponentPlan, cell: EntityCell): AnyRef =
    EntityCell.constructing(cell)(plan.construct(cell.entityContext()))

  private[bridge] object Hooks:
    private def method(cls: Class[?], name: String, params: Class[?]*): Method =
      val m = cls.getDeclaredMethod(name, params*)
      m.setAccessible(true)
      m

    private val esEmpty = method(classOf[EventSourcedEntity[?, ?]], "emptyState")
    private val esApply =
      method(classOf[EventSourcedEntity[?, ?]], "applyEvent", classOf[Object], classOf[Object])
    private val kvEmpty = method(classOf[KeyValueEntity[?]], "emptyState")

    private def call(m: Method, target: AnyRef, args: AnyRef*): Any =
      try m.invoke(target, args*)
      catch case e: InvocationTargetException => throw e.getCause

    def emptyEventSourced(java: AnyRef): Any = call(esEmpty, java)
    def applyEvent(java: AnyRef, state: Any, event: Any): Any =
      call(esApply, java, state.asInstanceOf[AnyRef], event.asInstanceOf[AnyRef])
    def emptyKeyValue(java: AnyRef): Any = call(kvEmpty, java)

/** A Java event sourced entity, hosted. */
private[ankka] final class JavaEventSourcedHost(val java: AnyRef, cell: EntityCell)
    extends sdk.EventSourcedEntity[Any, Any]:
  def emptyState: Any             = Adapters.Hooks.emptyEventSourced(java)
  def applyEvent(event: Any): Any = Adapters.Hooks.applyEvent(java, currentState, event)

  override private[ankka] def _setState(state: Any): Unit =
    super._setState(state)
    cell.state = state
    cell.stateSet = true

  override private[ankka] def _setContext(ctx: Option[CommandContext]): Unit =
    super._setContext(ctx)
    cell.command = ctx

/** A Java key value entity, hosted. */
private[ankka] final class JavaKeyValueHost(val java: AnyRef, cell: EntityCell)
    extends sdk.KeyValueEntity[Any]:
  def emptyState: Any = Adapters.Hooks.emptyKeyValue(java)

  override private[ankka] def _setState(state: Any): Unit =
    super._setState(state)
    cell.state = state
    cell.stateSet = true

  override private[ankka] def _setContext(ctx: Option[CommandContext]): Unit =
    super._setContext(ctx)
    cell.command = ctx
