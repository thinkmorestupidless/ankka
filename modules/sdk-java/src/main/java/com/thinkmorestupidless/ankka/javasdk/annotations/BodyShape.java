package com.thinkmorestupidless.ankka.javasdk.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The shape of a route's body, for a body whose type says none, in the service's OpenAPI document
 * (feature 067): a type the codec writes, or JSON Schema as text. Name one of the two.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface BodyShape {
  /** The type whose shape the body has; {@code Void.class}, the default, names none. */
  Class<?> value() default Void.class;

  /** The body's shape as JSON Schema, a JSON object; empty, the default, names none. */
  String schema() default "";
}
