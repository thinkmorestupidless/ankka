package com.thinkmorestupidless.ankka.javasdk.codec;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * How the codec reads a class as a record: its fields in order, and how to build one. A Java
 * {@code record} is read by the SDK itself; another kind of class (a Kotlin data class) is read by
 * a {@code RecordShapes} the {@link ServiceLoader} finds, which is how the Kotlin SDK plugs in
 * without the Java SDK knowing Kotlin.
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

  /** The shape of a Java record, or {@code null} for any other class; then the loaded readers. */
  static @Nullable Shape find(Class<?> type) {
    if (type.isRecord()) return JavaRecords.shapeOf(type);
    for (RecordShapes reader : Loaded.READERS) {
      Shape shape = reader.shapeOf(type);
      if (shape != null) return shape;
    }
    return null;
  }

  /** The readers on the class path, loaded once. */
  final class Loaded {
    static final List<RecordShapes> READERS = load();

    private Loaded() {}

    private static List<RecordShapes> load() {
      List<RecordShapes> found = new ArrayList<>();
      ServiceLoader.load(RecordShapes.class, RecordShapes.class.getClassLoader()).forEach(found::add);
      return List.copyOf(found);
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
