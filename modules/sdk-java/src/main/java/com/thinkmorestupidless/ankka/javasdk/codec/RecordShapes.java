package com.thinkmorestupidless.ankka.javasdk.codec;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * How the codec reads a class as a record: its fields in order, and how to build one. A Java
 * {@code record} is read by the SDK itself. A Kotlin class is read by the Kotlin SDK's reader,
 * found by its one fixed name the first time a Kotlin class is met: nothing is discovered from the
 * class path, and a Kotlin service started through {@link
 * com.thinkmorestupidless.ankka.javasdk.Ankka#service(String)} is read the same as one started
 * through the Kotlin SDK.
 */
public interface RecordShapes {

  /** The shape of {@code type}, or {@code null} when this reader does not know the kind of class. */
  @Nullable Shape shapeOf(Class<?> type);

  /** A class read as a record. */
  interface Shape {
    List<FieldShape> fields();

    /**
     * Builds a value from {@code values} in field order; {@code present[i]} is false where the
     * encoded form had no field, so a reader that knows a default can apply it.
     */
    Object construct(@Nullable Object[] values, boolean[] present);
  }

  /**
   * One field: its name, its declared type, how to read it, whether it may be null (a Kotlin
   * {@code T?}) and whether an absent field takes a default.
   */
  record FieldShape(
      String name,
      Type type,
      Function<Object, @Nullable Object> get,
      boolean nullable,
      boolean hasDefault) {}

  /**
   * The shape of a Java record or a Kotlin class, or {@code null} for any other class.
   *
   * @throws EncodingException for a Kotlin class when the Kotlin SDK is not on the class path
   */
  static @Nullable Shape find(Class<?> type) {
    if (type.isRecord()) return JavaRecords.shapeOf(type);
    if (Kotlin.isKotlin(type)) return Kotlin.reader(type).shapeOf(type);
    return null;
  }

  /** The Kotlin SDK's reader, by name, loaded the first time a Kotlin class is met. */
  final class Kotlin {
    static final String READER = "com.thinkmorestupidless.ankka.kotlin.codec.DataClassShapes";

    private static volatile @Nullable RecordShapes reader;

    private Kotlin() {}

    /** Whether {@code type} was compiled by Kotlin, read without loading any Kotlin class. */
    static boolean isKotlin(Class<?> type) {
      for (java.lang.annotation.Annotation a : type.getDeclaredAnnotations()) {
        if (a.annotationType().getName().equals("kotlin.Metadata")) return true;
      }
      return false;
    }

    static RecordShapes reader(Class<?> type) {
      RecordShapes found = reader;
      if (found != null) return found;
      try {
        ClassLoader loader = type.getClassLoader() != null ? type.getClassLoader() : RecordShapes.class.getClassLoader();
        found = (RecordShapes) Class.forName(READER, true, loader).getDeclaredConstructor().newInstance();
      } catch (ClassNotFoundException e) {
        throw new EncodingException(
            type.getName(),
            "is a Kotlin class, which the codec reads through the Kotlin SDK: add"
                + " com.thinkmorestupidless:ankka-kotlin to the build");
      } catch (ReflectiveOperationException | ClassCastException e) {
        throw new IllegalStateException("the Kotlin SDK's reader " + READER + " could not be made", e);
      }
      reader = found;
      return found;
    }
  }

  /** Java records: components and the canonical constructor. */
  final class JavaRecords {
    private JavaRecords() {}

    static Shape shapeOf(Class<?> type) {
      RecordComponent[] components = type.getRecordComponents();
      List<FieldShape> fields = new ArrayList<>(components.length);
      Class<?>[] parameterTypes = new Class<?>[components.length];
      for (int i = 0; i < components.length; i++) {
        RecordComponent component = components[i];
        parameterTypes[i] = component.getType();
        java.lang.reflect.Method accessor = component.getAccessor();
        accessor.setAccessible(true);
        fields.add(
            new FieldShape(
                component.getName(),
                component.getGenericType(),
                value -> {
                  try {
                    return accessor.invoke(value);
                  } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                  } catch (InvocationTargetException e) {
                    throw rethrow(e);
                  }
                },
                false,
                false));
      }
      Constructor<?> constructor;
      try {
        constructor = type.getDeclaredConstructor(parameterTypes);
        constructor.setAccessible(true);
      } catch (NoSuchMethodException e) {
        throw new IllegalStateException("a record with no canonical constructor: " + type, e);
      }
      List<FieldShape> frozen = List.copyOf(fields);
      return new Shape() {
        @Override
        public List<FieldShape> fields() {
          return frozen;
        }

        @Override
        public Object construct(@Nullable Object[] values, boolean[] present) {
          try {
            return constructor.newInstance(values);
          } catch (InstantiationException | IllegalAccessException e) {
            throw new IllegalStateException(e);
          } catch (InvocationTargetException e) {
            throw rethrow(e);
          }
        }
      };
    }

    private static RuntimeException rethrow(InvocationTargetException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException runtime) return runtime;
      if (cause instanceof Error error) throw error;
      return new IllegalStateException(cause);
    }
  }
}
