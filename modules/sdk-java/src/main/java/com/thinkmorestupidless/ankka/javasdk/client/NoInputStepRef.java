package com.thinkmorestupidless.ankka.javasdk.client;

import com.thinkmorestupidless.ankka.javasdk.effect.StepEffect;
import java.io.Serializable;

/** A workflow step that takes no input, named by a method reference: {@code Checkout::reserve}. */
@FunctionalInterface
public interface NoInputStepRef<W> extends Serializable {
  StepEffect apply(W workflow) throws Exception;
}
