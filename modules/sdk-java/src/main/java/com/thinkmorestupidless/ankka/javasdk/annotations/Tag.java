package com.thinkmorestupidless.ankka.javasdk.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Route tags, by which a document is written for some of a service's routes (feature 067). On a
 * class, every route of the endpoint has them.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Tag {
  String[] value();
}
