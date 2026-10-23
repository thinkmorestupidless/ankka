package com.thinkmorestupidless.ankka.javasdk.testkit;

import com.thinkmorestupidless.ankka.javasdk.http.Problem;
import java.util.List;

/**
 * What one command did in a component test kit: its reply or its refusal, the events it persisted
 * and the state after it.
 */
public interface Result<O> {

  /** The reply; throws {@link AssertionError} when the command was refused or replied nothing. */
  O reply();

  boolean isError();

  /** The refusal's message; throws {@link AssertionError} when the command succeeded. */
  String error();

  /** The refusal as the caller would see it; throws {@link AssertionError} when it succeeded. */
  Problem problem();

  /** The events this command persisted, in order; none for a key value entity. */
  List<?> events();

  /** The state after the command. */
  Object state();
}
