package com.thinkmorestupidless.ankka.javasdk.client;

/** One entity instance, to call. */
public interface EntityRef {

  /** The handler {@code handler} names, which takes one argument. */
  <C, I, O> Invocation<I, O> call(CommandRef<C, I, O> handler);

  /** The handler {@code handler} names, which takes none; invoke it with {@code invoke()}. */
  <C, O> Invocation<Void, O> call(NoArgRef<C, O> handler);

  /**
   * A handler named by its component id and wire name, for a call the compiler cannot check: the
   * escape hatch when the component's class is not on this classpath.
   */
  <I, O> Invocation<I, O> call(
      String componentId, String wireName, Class<I> input, Class<O> output);
}
