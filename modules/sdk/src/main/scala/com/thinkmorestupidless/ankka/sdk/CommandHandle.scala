package com.thinkmorestupidless.ankka.sdk

import com.thinkmorestupidless.ankka.core.{ComponentId, MethodName, Serializer}

/**
 * The runtime-facing view of one registered handler.
 *
 * Serialization and the single unavoidable cast are confined here, so the sharding host can drive
 * any handler without knowing its input or output types.
 */
private[ankka] sealed trait HandlerBinding[C]:
  def componentId: ComponentId
  def name: MethodName
  def readOnly: Boolean

  /** Decodes the request payload and runs the handler, returning its Effect. */
  private[ankka] def decodeAndInvoke(component: C, payload: Array[Byte]): Any

  /** Encodes whatever the Effect finally replied with. */
  private[ankka] def encodeReply(value: Any): Array[Byte]

/**
 * A typed reference to a handler that takes one argument.
 *
 * This is what replaces Akka's `Entity::method` lambda-bytecode inspection. Because the handle
 * carries `I` and `O`, a call site is checked by the compiler; because it carries `componentId` and
 * `name`, the runtime can route it without reflection.
 */
final class CommandHandle[C, I, O] private[ankka] (
    val componentId: ComponentId,
    val name: MethodName,
    val readOnly: Boolean,
    private[ankka] val inputSerializer: Serializer[I],
    private[ankka] val outputSerializer: Serializer[O],
    private[ankka] val run: (C, I) => Any
) extends HandlerBinding[C]:

  private[ankka] def decodeAndInvoke(component: C, payload: Array[Byte]): Any =
    run(component, inputSerializer.fromBytes(payload))

  private[ankka] def encodeReply(value: Any): Array[Byte] =
    outputSerializer.toBytes(value.asInstanceOf[O])

  override def toString: String = s"$componentId#$name"

/**
 * A typed reference to a handler that takes no argument.
 *
 * Kept distinct from `CommandHandle` rather than modelled as `CommandHandle[C, Unit, O]` so that
 * call sites read `.invoke()` instead of `.invoke(())`.
 */
final class NoArgHandle[C, O] private[ankka] (
    val componentId: ComponentId,
    val name: MethodName,
    val readOnly: Boolean,
    private[ankka] val outputSerializer: Serializer[O],
    private[ankka] val run: C => Any
) extends HandlerBinding[C]:

  private[ankka] def decodeAndInvoke(component: C, payload: Array[Byte]): Any =
    run(component)

  private[ankka] def encodeReply(value: Any): Array[Byte] =
    outputSerializer.toBytes(value.asInstanceOf[O])

  override def toString: String = s"$componentId#$name"
