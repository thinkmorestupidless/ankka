package com.thinkmorestupidless.ankka.javasdk.codec;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The manifest a type is written under. Without it the manifest is the simple name in kebab case
 * ({@code ShoppingCartEvent} is {@code shopping-cart-event}), which changes if the type is renamed;
 * name it here once a journal holds the type.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Manifest {
  String value();
}
