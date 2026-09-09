package nakka.sdk

import nakka.core.{ComponentId, MethodName, Serializer}

/**
 * The runtime-facing view of one registered handler.
 *
 * Serialization and the single unavoidable cast are confined here, so the sharding host can drive
 * any handler without knowing its input or output types.
 */
private[nakka] sealed trait HandlerBinding[C]:
  def componentId: ComponentId
  def name: MethodName
  def readOnly: Boolean

  /** Decodes the request payload and runs the handler, returning its Effect. */
  private[nakka] def decodeAndInvoke(component: C, payload: Array[Byte]): Any

  /** Encodes whatever the Effect finally replied with. */
  private[nakka] def encodeReply(value: Any): Array[Byte]

/**
 * A typed reference to a handler that takes one argument.
 *
 * This is what replaces Akka's `Entity::method` lambda-bytecode inspection. Because the handle
 * carries `I` and `O`, a call site is checked by the compiler; because it carries `componentId` and
 * `name`, the runtime can route it without reflection.
 */
final class CommandHandle[C, I, O] private[nakka] (
    val componentId: ComponentId,
    val name: MethodName,
    val readOnly: Boolean,
    private[nakka] val inputSerializer: Serializer[I],
    private[nakka] val outputSerializer: Serializer[O],
    private[nakka] val run: (C, I) => Any
) extends HandlerBinding[C]:

  private[nakka] def decodeAndInvoke(component: C, payload: Array[Byte]): Any =
    run(component, inputSerializer.fromBytes(payload))

  private[nakka] def encodeReply(value: Any): Array[Byte] =
    outputSerializer.toBytes(value.asInstanceOf[O])

  override def toString: String = s"$componentId#$name"

/**
 * A typed reference to a handler that takes no argument.
 *
 * Kept distinct from `CommandHandle` rather than modelled as `CommandHandle[C, Unit, O]` so that
 * call sites read `.invoke()` instead of `.invoke(())`.
 */
final class NoArgHandle[C, O] private[nakka] (
    val componentId: ComponentId,
    val name: MethodName,
    val readOnly: Boolean,
    private[nakka] val outputSerializer: Serializer[O],
    private[nakka] val run: C => Any
) extends HandlerBinding[C]:

  private[nakka] def decodeAndInvoke(component: C, payload: Array[Byte]): Any =
    run(component)

  private[nakka] def encodeReply(value: Any): Array[Byte] =
    outputSerializer.toBytes(value.asInstanceOf[O])

  override def toString: String = s"$componentId#$name"
