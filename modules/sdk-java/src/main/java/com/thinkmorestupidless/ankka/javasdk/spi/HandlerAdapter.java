package com.thinkmorestupidless.ankka.javasdk.spi;

import java.lang.reflect.Method;
import org.jspecify.annotations.Nullable;

/**
 * How the SDK calls a handler it cannot call itself: a Kotlin {@code suspend fun}, whose last JVM
 * parameter is a {@code kotlin.coroutines.Continuation}. The SDK reads such a handler's input and
 * reply types from its signature as it reads any handler's, and hands the call to the Kotlin SDK's
 * adapter, which it finds by the one name {@link #KOTLIN} the first time it meets one. A developer
 * never implements this.
 */
public interface HandlerAdapter {

  /** The Kotlin SDK's adapter, loaded by this name. */
  String KOTLIN = "com.thinkmorestupidless.ankka.kotlin.suspend.SuspendingHandlers";

  /**
   * Calls {@code method} on {@code instance} with {@code arguments} (not including the
   * continuation) on the calling thread, and returns its result once it has one. The calling thread
   * is the handler's virtual thread, which parks while the function waits.
   *
   * @throws Throwable whatever the function threw, as itself
   */
  @Nullable Object call(Method method, Object instance, @Nullable Object[] arguments)
      throws Throwable;
}
