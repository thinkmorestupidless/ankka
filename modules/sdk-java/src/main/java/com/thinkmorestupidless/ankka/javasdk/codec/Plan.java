package com.thinkmorestupidless.ankka.javasdk.codec;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * What the codec does with one type, decided once from the type and read on every value. The
 * writer and the reader follow the plan and never look at a class again.
 */
sealed interface Plan
    permits Plan.Record,
        Plan.Sum,
        Plan.Enumeration,
        Plan.Opt,
        Plan.OrNull,
        Plan.Seq,
        Plan.Dictionary,
        Plan.Leaf,
        Plan.Ref {

  /** A record, or a Kotlin data class: an object with every field written. */
  record Record(Class<?> type, List<Field> fields, RecordShapes.Shape shape) implements Plan {}

  /** One field of a record, in declaration order. */
  record Field(String name, Plan plan, Function<Object, @Nullable Object> get, boolean hasDefault) {}

  /** A sealed interface: the case's object with {@code "type"} first. */
  record Sum(Class<?> type, Map<String, Record> cases, Map<Class<?>, String> names)
      implements Plan {}

  /** An enum, written as {@code {"type":"A"}}. */
  record Enumeration(Class<?> type, Map<String, Object> constants) implements Plan {}

  /** {@code Optional<T>}: {@code null} when empty. */
  record Opt(Plan inner) implements Plan {}

  /** A Kotlin nullable type: {@code null} when null. */
  record OrNull(Plan inner) implements Plan {}

  /** A list, a set or an array. */
  record Seq(Plan element, SeqKind kind, @Nullable Class<?> arrayType)
      implements Plan {}

  enum SeqKind {
    LIST,
    SET,
    ARRAY
  }

  /** A map with string keys. */
  record Dictionary(Plan value) implements Plan {}

  /** A value the encoding writes directly. */
  record Leaf(LeafKind kind) implements Plan {}

  enum LeafKind {
    STRING,
    BOOLEAN,
    INT,
    LONG,
    DOUBLE,
    BIG_DECIMAL,
    INSTANT,
    DURATION,
    LOCAL_DATE,
    LOCAL_DATE_TIME,
    UUID,
    BYTES
  }

  /** A type still being planned, for a type that contains itself. */
  final class Ref implements Plan {
    private @Nullable Plan target;

    Plan target() {
      Plan t = target;
      if (t == null) throw new IllegalStateException("a recursive plan was read before it was built");
      return t;
    }

    void set(Plan plan) {
      this.target = plan;
    }
  }
}
