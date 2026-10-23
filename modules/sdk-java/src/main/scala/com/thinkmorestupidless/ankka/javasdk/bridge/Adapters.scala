package com.thinkmorestupidless.ankka.javasdk.bridge

import com.thinkmorestupidless.ankka.core.{ComponentDescriptor, ComponentId, MethodName}
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
    EventSourcedEntityDescriptor(
      ComponentId(plan.componentId),
      CodecBridge.toScala(plan.state),
      CodecBridge.toScala(plan.event.get),
      ctx =>
        val cell = EntityCell(ctx.entityId, plan.componentId, Clients.of(ctx.componentClient, refs))
        JavaEventSourcedHost(construct(plan, cell), cell)
      ,
      snapshotEvery = Some(100),
      handlers = bindings[JavaEventSourcedHost](plan, _.java)
    )

  def keyValue(plan: ComponentPlan, refs: Refs): KeyValueEntityDescriptor[JavaKeyValueHost, Any] =
    KeyValueEntityDescriptor(
      ComponentId(plan.componentId),
      CodecBridge.toScala(plan.state),
      ctx =>
        val cell = EntityCell(ctx.entityId, plan.componentId, Clients.of(ctx.componentClient, refs))
        JavaKeyValueHost(construct(plan, cell), cell)
      ,
      bindings[JavaKeyValueHost](plan, _.java)
    )

  private[bridge] def construct(plan: ComponentPlan, cell: EntityCell): AnyRef =
    EntityCell.constructing(cell)(plan.construct(cell.entityContext()))

  /** One Scala handle per Java handler, whose `run` calls the method and unwraps its effect. */
  private[bridge] def bindings[H](
      plan: ComponentPlan,
      java: H => AnyRef
  ): Map[MethodName, HandlerBinding[H]] =
    plan.handlers.map { h =>
      val where = s"${plan.cls.getSimpleName}.${h.method.getName}"
      val name  = MethodName(h.wireName)
      val out   = CodecBridge.toScala(h.output)
      val binding: HandlerBinding[H] = h.input match
        case Some(in) =>
          new CommandHandle[H, Any, Any](
            ComponentId(plan.componentId),
            name,
            h.readOnly,
            CodecBridge.toScala(in),
            out,
            (host, input) => Effects.unwrap(h.invoke(java(host), input), where)
          )
        case None =>
          new NoArgHandle[H, Any](
            ComponentId(plan.componentId),
            name,
            h.readOnly,
            out,
            host => Effects.unwrap(h.invoke(java(host), null), where)
          )
      name -> binding
    }.toMap

  /** The Java base classes' protected methods, called on the instance. */
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
