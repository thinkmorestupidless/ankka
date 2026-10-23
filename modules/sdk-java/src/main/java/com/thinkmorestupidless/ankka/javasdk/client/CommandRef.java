package com.thinkmorestupidless.ankka.javasdk.client;

import com.thinkmorestupidless.ankka.javasdk.effect.Effect;
import java.io.Serializable;

/**
 * A handler that takes one argument, named by a method reference: {@code ShoppingCart::addItem}.
 * The reference is resolved to the handler's wire name once; a lambda that is not a method
 * reference is refused with a {@link CallerError}.
 */
@FunctionalInterface
public interface CommandRef<C, I, O> extends Serializable {
  Effect<O> apply(C component, I input);
}
