package com.thinkmorestupidless.ankka.javasdk.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * One line saying what a route is for, in the service's OpenAPI document (feature 067). Describes
 * the route and never changes what it does.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Summary {
  String value();
}
