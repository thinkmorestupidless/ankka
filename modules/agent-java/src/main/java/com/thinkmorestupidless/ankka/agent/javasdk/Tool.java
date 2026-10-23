package com.thinkmorestupidless.ankka.agent.javasdk;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A method the model may call, on an object or class handed to {@link AgentBuilder#tools}. It
 * takes nothing, one record whose components are the tool's arguments, or plain parameters, each
 * an argument by its name (compile with {@code -parameters}); {@code @Describe} on a component or
 * a parameter is what the model is told it is. It answers a {@code String}, given to the model as
 * it is, or any other value, given as its JSON. An exception it throws is told to the model as the
 * tool's error, which the model usually corrects.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Tool {

  /** The name the model calls the tool by. */
  String value();

  /** What the tool does: the whole basis on which the model decides to call it. */
  String description();

  /** Whether a person decides each call before it runs. */
  boolean requiresApproval() default false;

  /**
   * How long a call waits for a person, as an ISO-8601 duration ({@code "PT10M"}); refused by the
   * platform when it passes. No limit when empty. Only with {@link #requiresApproval()}.
   */
  String approvalWithin() default "";
}
