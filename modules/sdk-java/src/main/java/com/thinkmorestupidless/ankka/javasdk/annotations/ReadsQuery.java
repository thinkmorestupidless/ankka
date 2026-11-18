package com.thinkmorestupidless.ankka.javasdk.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A query parameter a handler reads through {@code Request}, in the service's OpenAPI document
 * (feature 067). A parameter bound with {@link QueryParam} is known already and is not named again.
 * On a class, every route of the endpoint reads it.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Repeatable(ReadsQuery.List.class)
public @interface ReadsQuery {
  String name();

  /**
   * What it is read as: {@code String}, {@code Integer}, {@code Long}, {@code Double}, {@code
   * Boolean} or {@code UUID}. {@code String} by default.
   */
  Class<?> type() default String.class;

  /** Whether it must be sent; {@code false} by default. */
  boolean required() default false;

  /** Several {@code @ReadsQuery} on one element. */
  @Documented
  @Retention(RetentionPolicy.RUNTIME)
  @Target({ElementType.TYPE, ElementType.METHOD})
  @interface List {
    ReadsQuery[] value();
  }
}
