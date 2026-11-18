package com.thinkmorestupidless.ankka.javasdk.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An answer a route gives, by status, in the service's OpenAPI document (feature 067): for a route
 * whose return type says nothing of its answers, or one that answers more than one status. The
 * shape is a type the codec writes or JSON Schema as text; neither, the default, is no body.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(Answers.List.class)
public @interface Answers {
  /** The status, 200 by default. */
  int status() default 200;

  /** The type whose shape the answer has; {@code Void.class}, the default, is no body. */
  Class<?> shape() default Void.class;

  /** The answer's shape as JSON Schema, a JSON object; empty, the default, names none. */
  String schema() default "";

  /** Several {@code @Answers} on one element. */
  @Documented
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  @interface List {
    Answers[] value();
  }
}
