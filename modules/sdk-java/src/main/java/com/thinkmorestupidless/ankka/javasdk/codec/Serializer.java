package com.thinkmorestupidless.ankka.javasdk.codec;

import java.lang.reflect.Type;
import java.util.Objects;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * How a value crosses a boundary: into the journal, to another component, onto a topic.
 *
 * <p>{@link #manifest()} is the name written beside the bytes. Keep it stable and the class may be
 * renamed freely: a journal is read back by manifest, never by class name.
 *
 * <p>The SDK derives one for every record, sealed interface and enum a handler names ({@link
 * #of(Class)}), writing exactly what the Scala SDK writes for the same shape. Supply one of your own
 * with {@link #of(String, Function, Function)} for a type the derivation refuses; portability with
 * other languages is then yours to keep.
 *
 * <p>{@code A} may be a nullable type: the serializer of {@code Void} (the reply of a handler that
 * answers nothing) reads every value as {@code null}.
 */
public interface Serializer<A extends @Nullable Object> {

  /** The logical type name written beside the bytes. */
  String manifest();

  /** How the bytes are to be read: JSON, text or binary. */
  default ContentType contentType() {
    return ContentType.JSON;
  }

  byte[] toBytes(A value);

  A fromBytes(byte[] bytes);

  /** The SDK's own serializer for {@code type}, derived once and cached. */
  static <A> Serializer<A> of(Class<A> type) {
    return RecordCodec.forType(type);
  }

  /** The SDK's own serializer for a generic type, such as {@code List<LineItem>}. */
  static Serializer<?> of(Type type) {
    return RecordCodec.forType(type);
  }

  /** A serializer written by hand: {@code write} and {@code read} are the whole of it. */
  static <A> Serializer<A> of(
      String manifest, Function<A, byte[]> write, Function<byte[], A> read) {
    Objects.requireNonNull(manifest, "manifest");
    Objects.requireNonNull(write, "write");
    Objects.requireNonNull(read, "read");
    return new Serializer<>() {
      @Override
      public String manifest() {
        return manifest;
      }

      @Override
      public byte[] toBytes(A value) {
        return write.apply(value);
      }

      @Override
      public A fromBytes(byte[] bytes) {
        return read.apply(bytes);
      }

      @Override
      public String toString() {
        return "Serializer.of(" + manifest + ")";
      }
    };
  }
}
