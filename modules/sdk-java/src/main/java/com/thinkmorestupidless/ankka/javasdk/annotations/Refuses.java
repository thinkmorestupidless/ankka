package com.thinkmorestupidless.ankka.javasdk.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A refusal a route gives, in the service's OpenAPI document (feature 067): its status and why.
 * On a class, every route of the endpoint gives it. Its shape is {@code shape}, else the endpoint's
 * {@link RefusalShape}, else the platform's problem.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(Refuses.List.class)
public @interface Refuses {
  int status();

  String reason();

  /** The refusal's own shape; {@code Void.class}, the default, names none. */
  Class<?> shape() default Void.class;

  /** Several {@code @Refuses} on one element. */
  @Documented
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    Refuses[] value();
  }
}
