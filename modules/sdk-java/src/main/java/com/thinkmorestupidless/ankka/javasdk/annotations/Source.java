package com.thinkmorestupidless.ankka.javasdk.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A keyed view's handler for one source: a component of this service named by {@link #value}, or
 * a topic named by {@link #topic}, whose messages are decoded as the method's first parameter.
 * With {@link #deleted} the method handles the component's deletions instead of its changes.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Source {

  /** The entity or workflow class; unset for a topic. */
  Class<?> value() default Void.class;

  /** The topic's name; empty for a component. */
  String topic() default "";

  /** The project that grants the topic; empty for this project's own. */
  String project() default "";

  /** Whether this method handles the component's deletions. */
  boolean deleted() default false;
}
