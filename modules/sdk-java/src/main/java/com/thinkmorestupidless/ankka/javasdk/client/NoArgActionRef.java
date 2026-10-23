package com.thinkmorestupidless.ankka.javasdk.client;

import com.thinkmorestupidless.ankka.javasdk.effect.TimedActionEffect;
import java.io.Serializable;

/** A timed action's handler that takes no input, named by a method reference. */
@FunctionalInterface
public interface NoArgActionRef<A> extends Serializable {
  TimedActionEffect apply(A action) throws Exception;
}
