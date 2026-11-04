package com.thinkmorestupidless.ankka.javasdk;

import com.thinkmorestupidless.ankka.core.PersonalJson;
import java.util.Objects;

/**
 * Whom a personal field is about (feature 042): 1 to 253 characters from letters, digits, {@code
 * .}, {@code _}, {@code -}, {@code /} and {@code :}, such as {@code player/8c1f}. An erasure names
 * one, and every personal field of it becomes unreadable.
 */
public record DataSubject(String value) {

  /** Refuses a value outside the rule, with the message every SDK refuses it with. */
  public DataSubject {
    Objects.requireNonNull(value, "value");
    String problem = PersonalJson.subjectProblem(value);
    if (problem != null) throw new IllegalArgumentException(problem);
  }

  public static DataSubject of(String value) {
    return new DataSubject(value);
  }

  @Override
  public String toString() {
    return value;
  }
}
