package com.thinkmorestupidless.ankka.javasdk.bridge

import com.thinkmorestupidless.ankka.javasdk.spi.HandlerAdapter

import java.lang.reflect.{InvocationTargetException, Method, ParameterizedType, Type, WildcardType}

/**
 * A handler method as the developer wrote it. A Java method is read as it is. A Kotlin
 * `suspend fun` compiles to a method with a last `Continuation<? super R>` parameter returning
 * `Object`: its parameters are the others, its reply `R` is read from that continuation's type, and
 * it is called through the Kotlin SDK's `HandlerAdapter`, found by name.
 */
private[ankka] object Invocable:

  private val ContinuationName = "kotlin.coroutines.Continuation"

  /** Whether `m` is a Kotlin `suspend fun`. */
  def suspending(m: Method): Boolean =
    m.getParameterTypes.lastOption.exists(_.getName == ContinuationName)

  def parameterCount(m: Method): Int =
    if suspending(m) then m.getParameterCount - 1 else m.getParameterCount

  def parameterTypes(m: Method): Array[Type] =
    val all = m.getGenericParameterTypes
    if suspending(m) then all.dropRight(1) else all

  def parameters(m: Method): Array[java.lang.reflect.Parameter] =
    val all = m.getParameters
    if suspending(m) then all.dropRight(1) else all

  def genericReturnType(m: Method): Type =
    if !suspending(m) then m.getGenericReturnType
    else
      m.getGenericParameterTypes.last match
        case p: ParameterizedType =>
          p.getActualTypeArguments.head match
            case w: WildcardType if w.getLowerBounds.nonEmpty => w.getLowerBounds.head
            case other                                        => other
        case _ => classOf[Object]

  def returnType(m: Method): Class[?] =
    Planner.rawClass(genericReturnType(m)).getOrElse(classOf[Object])

  /**
   * Why `m` cannot be called, if it is a `suspend fun` and the Kotlin SDK is not on the class path.
   */
  def problem(m: Method): Option[String] =
    if suspending(m) && adapter(m).isLeft then
      Some(
        "is a suspend fun, which the Kotlin SDK calls: add com.thinkmorestupidless:ankka-kotlin " +
          "to the build"
      )
    else None

  /** Calls `m` on `instance`; a handler's own exception is rethrown as itself. */
  def invoke(m: Method, instance: AnyRef, arguments: AnyRef*): Any =
    if suspending(m) then
      adapter(m) match
        case Right(a)  => a.call(m, instance, arguments.toArray)
        case Left(why) => throw IllegalStateException(why)
    else
      try m.invoke(instance, arguments*)
      catch case e: InvocationTargetException => throw e.getCause

  @volatile private var loaded: Option[HandlerAdapter] = None

  private def adapter(m: Method): Either[String, HandlerAdapter] =
    loaded match
      case Some(a) => Right(a)
      case None =>
        val loader = Option(m.getDeclaringClass.getClassLoader).getOrElse(getClass.getClassLoader)
        try
          val a = Class
            .forName(HandlerAdapter.KOTLIN, true, loader)
            .getDeclaredConstructor()
            .newInstance()
            .asInstanceOf[HandlerAdapter]
          loaded = Some(a)
          Right(a)
        catch
          case _: ClassNotFoundException =>
            Left(s"${HandlerAdapter.KOTLIN} is not on the class path")
