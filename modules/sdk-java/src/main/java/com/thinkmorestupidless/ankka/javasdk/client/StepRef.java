package com.thinkmorestupidless.ankka.javasdk.client;

import com.thinkmorestupidless.ankka.javasdk.effect.StepEffect;
import java.io.Serializable;

/**
 * A workflow step that takes an input, named by a method reference: {@code Checkout::charge}. It is
 * resolved to the step's {@code @Step} name, so a transition survives the method's renaming.
 */
@FunctionalInterface
public interface StepRef<W, I> extends Serializable {
  StepEffect apply(W workflow, I input) throws Exception;
}
