package com.thinkmorestupidless.ankka.javasdk;

import com.thinkmorestupidless.ankka.javasdk.codec.Serializer;
import com.thinkmorestupidless.ankka.javasdk.problems.ServiceProblems;

/**
 * A growing service definition. Nothing is read or started until {@link #start}, which reads every
 * registered class once and refuses to start with any problem, listing them all.
 */
public interface ServiceBuilder {

  /** Registers a component; its id is its {@code @ComponentId}. */
  ServiceBuilder register(Class<?> componentClass);

  /** Registers a component under {@code componentId}. */
  ServiceBuilder register(Class<?> componentClass, String componentId);

  /**
   * A hand-written serializer for {@code type}, for a type the SDK's own codec refuses or one whose
   * bytes the developer decides. Every handler that names the type uses it.
   */
  <A> ServiceBuilder withSerializer(Class<A> type, Serializer<A> serializer);

  /**
   * Reads every registered class and starts the service, or throws {@link ServiceProblems} naming
   * every problem, before anything listens.
   */
  AnkkaService start() throws ServiceProblems;
}
