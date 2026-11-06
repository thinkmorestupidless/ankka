package com.thinkmorestupidless.ankka.javasdk;

import java.util.Objects;

/**
 * A personal field (feature 042): a value about one data subject, held in every store encrypted
 * under that data subject's subject key, and read back as {@link Erased} once the data subject is
 * erased. A record field of this type needs nothing else declared.
 *
 * <p>It prints as {@code Personal(<subject>)}, never its value, so a log line or a trace cannot
 * carry what it holds.
 */
public sealed interface Personal<A> permits Personal.Present, Personal.Erased {

  /** Whom the value is about. */
  DataSubject subject();

  /** The value, or {@code other} once the data subject is erased. */
  A orElse(A other);

  /** Whether the data subject was erased. */
  boolean isErased();

  static <A> Personal<A> of(DataSubject subject, A value) {
    return new Present<>(subject, value);
  }

  /** A value of a data subject that has not been erased. */
  record Present<A>(DataSubject subject, A value) implements Personal<A> {
    public Present {
      Objects.requireNonNull(subject, "subject");
      Objects.requireNonNull(value, "value");
    }

    @Override
    public A orElse(A other) {
      return value;
    }

    @Override
    public boolean isErased() {
      return false;
    }

    @Override
    public String toString() {
      return "Personal(" + subject + ")";
    }
  }

  /** What a field of an erased data subject is read as: the data subject alone. */
  record Erased<A>(DataSubject subject) implements Personal<A> {
    public Erased {
      Objects.requireNonNull(subject, "subject");
    }

    @Override
    public A orElse(A other) {
      return other;
    }

    @Override
    public boolean isErased() {
      return true;
    }

    @Override
    public String toString() {
      return "Personal(" + subject + ")";
    }
  }
}
