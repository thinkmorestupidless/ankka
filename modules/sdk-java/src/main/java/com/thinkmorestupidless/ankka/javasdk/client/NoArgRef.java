package com.thinkmorestupidless.ankka.javasdk.client;

import com.thinkmorestupidless.ankka.javasdk.effect.Effect;
import java.io.Serializable;

/** A handler that takes no argument, named by a method reference: {@code ShoppingCart::getCart}. */
@FunctionalInterface
public interface NoArgRef<C, O> extends Serializable {
  Effect<O> apply(C component) throws Exception;
}
