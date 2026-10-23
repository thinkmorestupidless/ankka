package com.thinkmorestupidless.ankka.javasdk.bridge

import com.thinkmorestupidless.ankka.javasdk.client.CallerError

import java.lang.invoke.SerializedLambda
import java.util.concurrent.ConcurrentHashMap

/**
 * A method reference (`ShoppingCart::addItem`) resolved to the handler it names. A serializable
 * lambda carries what it refers to in its `SerializedLambda`: the class, the method and its
 * descriptor, which the registered plans are keyed by. Resolved once per lambda class.
 */
private[ankka] final class Refs(plans: Iterable[ComponentPlan]):

  private val byKey: Map[(String, String, String), (ComponentPlan, HandlerPlan)] =
    plans.iterator.flatMap(c => c.handlers.map(h => h.javaKey -> (c, h))).toMap

  private val cache = ConcurrentHashMap[Class[?], (ComponentPlan, HandlerPlan)]()

  def resolve(ref: AnyRef): (ComponentPlan, HandlerPlan) =
    val known = cache.get(ref.getClass)
    if known != null then known
    else
      val found = lookup(Refs.serialized(ref))
      cache.put(ref.getClass, found)
      found

  private def lookup(lambda: SerializedLambda): (ComponentPlan, HandlerPlan) =
    val implClass = lambda.getImplClass.replace('/', '.')
    val method    = lambda.getImplMethodName
    if method.startsWith("lambda$") then
      throw CallerError(
        s"a call in ${lambda.getCapturingClass.replace('/', '.')} names its handler with a lambda; " +
          "name it with a method reference, such as ShoppingCart::addItem"
      )
    byKey.getOrElse(
      (implClass, method, lambda.getImplMethodSignature),
      throw CallerError(
        s"$implClass::$method is not a registered handler: it has no @Command or @Query, or " +
          s"${implClass.split('.').last} is not registered with this service"
      )
    )

private[ankka] object Refs:

  /** The `SerializedLambda` a serializable lambda writes in place of itself. */
  def serialized(ref: AnyRef): SerializedLambda =
    try
      val writeReplace = ref.getClass.getDeclaredMethod("writeReplace")
      writeReplace.setAccessible(true)
      writeReplace.invoke(ref) match
        case lambda: SerializedLambda => lambda
        case other                    => throw CallerError(s"$other is not a method reference")
    catch
      case _: NoSuchMethodException =>
        throw CallerError(
          s"${ref.getClass.getName} is not a method reference; name the handler as Class::method"
        )
