package com.thinkmorestupidless.ankka.javasdk.problems;

/**
 * One thing wrong with a registered class, found when the service starts and before anything
 * listens: {@code component} is the class or component id, {@code member} the method or field
 * ({@code ""} for the class itself), {@code message} what is wrong and what to write instead.
 */
public record DeclarationProblem(String component, String member, String message) {

  @Override
  public String toString() {
    return member.isEmpty() ? component + ": " + message : component + "." + member + ": " + message;
  }
}
