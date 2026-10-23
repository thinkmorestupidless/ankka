package com.thinkmorestupidless.ankka.javasdk.codec;

import com.thinkmorestupidless.ankka.javasdk.Done;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

/**
 * Builds the {@link Plan} for a type, once, and refuses a type the encoding has no form for, naming
 * the member that has none: {@code ShoppingCart.items[]: float has no form in the encoding}.
 */
final class Planner {

  private static final Map<Type, Plan> CACHE = new ConcurrentHashMap<>();

  private Planner() {}

  /** The plan for {@code type} inside a JSON payload. */
  static Plan plan(Type type) {
    Plan cached = CACHE.get(type);
    if (cached != null) return cached;
    Plan built = new Building().plan(type, simpleName(type));
    CACHE.putIfAbsent(type, built);
    return built;
  }

  static String simpleName(Type type) {
    if (type instanceof Class<?> c) return c.getSimpleName();
    if (type instanceof ParameterizedType p) return simpleName(p.getRawType());
    return type.getTypeName();
  }

  /** One planning run: holds the types being planned, so a type that contains itself is a ref. */
  private static final class Building {
    private final Map<Type, Plan.Ref> inProgress = new HashMap<>();

    Plan plan(Type type, String path) {
      if (type instanceof Class<?> c) return planClass(c, path);
      if (type instanceof ParameterizedType p) return planParameterized(p, path);
      if (type instanceof GenericArrayType g) {
        Plan element = plan(g.getGenericComponentType(), path + "[]");
        Class<?> raw = rawClass(g.getGenericComponentType());
        return new Plan.Seq(element, Plan.SeqKind.ARRAY, raw);
      }
      if (type instanceof WildcardType w && w.getUpperBounds().length == 1) {
        return plan(w.getUpperBounds()[0], path);
      }
      if (type instanceof TypeVariable<?> v) {
        throw new EncodingException(
            path, "the type variable " + v.getName() + " is not known; name the concrete type");
      }
      throw new EncodingException(path, type.getTypeName() + " has no form in the encoding");
    }

    private Plan planParameterized(ParameterizedType p, String path) {
      Class<?> raw = rawClass(p.getRawType());
      Type[] args = p.getActualTypeArguments();
      if (raw == Optional.class) return new Plan.Opt(plan(args[0], path));
      if (raw == List.class || raw == java.util.Collection.class || raw == Iterable.class) {
        return new Plan.Seq(plan(args[0], path + "[]"), Plan.SeqKind.LIST, null);
      }
      if (raw == Set.class) return new Plan.Seq(plan(args[0], path + "[]"), Plan.SeqKind.SET, null);
      if (raw == Map.class) {
        if (args[0] != String.class) {
          throw new EncodingException(
              path,
              "a map's key must be a String; "
                  + args[0].getTypeName()
                  + " has no form as a JSON object's key");
        }
        return new Plan.Dictionary(plan(args[1], path + "{}"));
      }
      throw new EncodingException(
          path,
          p.getTypeName()
              + " has no form in the encoding; a generic record is not read, name a concrete one");
    }

    private Plan planClass(Class<?> c, String path) {
      Plan leaf = leaf(c, path);
      if (leaf != null) return leaf;
      if (c.isArray()) {
        return new Plan.Seq(
            plan(c.getComponentType(), path + "[]"), Plan.SeqKind.ARRAY, c.getComponentType());
      }
      if (c == Optional.class || c == List.class || c == Set.class || c == Map.class) {
        throw new EncodingException(path, c.getSimpleName() + " needs its type argument");
      }
      if (c.isEnum()) return enumeration(c);
      Plan.Ref existing = inProgress.get(c);
      if (existing != null) return existing;
      if (c.isSealed() && c.isInterface() || c.isSealed() && java.lang.reflect.Modifier.isAbstract(c.getModifiers())) {
        Plan.Ref ref = new Plan.Ref();
        inProgress.put(c, ref);
        Plan sum = sum(c, path);
        ref.set(sum);
        inProgress.remove(c);
        return sum;
      }
      RecordShapes.Shape shape = RecordShapes.find(c);
      if (shape != null) {
        Plan.Ref ref = new Plan.Ref();
        inProgress.put(c, ref);
        Plan.Record record = record(c, shape, path);
        ref.set(record);
        inProgress.remove(c);
        return record;
      }
      throw new EncodingException(
          path,
          c.getName()
              + " has no form in the encoding: use a record, a sealed interface or an enum, or"
              + " register a Serializer for it");
    }

    private Plan.Record record(Class<?> c, RecordShapes.Shape shape, String path) {
      List<Plan.Field> fields = new ArrayList<>();
      for (RecordShapes.FieldShape f : shape.fields()) {
        if (f.name().equals("type")) {
          throw new EncodingException(
              path + "." + f.name(),
              "a field named \"type\" is refused: the encoding writes a sum case's name there");
        }
        Plan plan = plan(f.type(), path + "." + f.name());
        if (f.nullable() && !(plan instanceof Plan.Opt)) plan = new Plan.OrNull(plan);
        fields.add(new Plan.Field(f.name(), plan, f.get(), f.hasDefault()));
      }
      return new Plan.Record(c, List.copyOf(fields), shape);
    }

    private Plan sum(Class<?> c, String path) {
      Map<String, Plan.Record> cases = new LinkedHashMap<>();
      Map<Class<?>, String> names = new HashMap<>();
      collectCases(c, c, path, cases, names);
      return new Plan.Sum(c, Map.copyOf(cases), Map.copyOf(names));
    }

    private void collectCases(
        Class<?> root,
        Class<?> c,
        String path,
        Map<String, Plan.Record> cases,
        Map<Class<?>, String> names) {
      Class<?>[] permitted = c.getPermittedSubclasses();
      if (permitted == null) return;
      for (Class<?> sub : permitted) {
        if (sub.isSealed()) {
          collectCases(root, sub, path, cases, names);
          continue;
        }
        String name = sub.getSimpleName();
        if (name.equals("type")) {
          throw new EncodingException(path, "a case named \"type\" is refused");
        }
        if (cases.containsKey(name)) {
          throw new EncodingException(
              path, "two cases of " + root.getSimpleName() + " are named " + name);
        }
        RecordShapes.Shape shape = RecordShapes.find(sub);
        if (shape == null) {
          throw new EncodingException(
              path + "." + name,
              "a case of a sealed type must be a record; " + sub.getName() + " is not");
        }
        Plan.Ref ref = new Plan.Ref();
        inProgress.put(sub, ref);
        Plan.Record record = record(sub, shape, path + "." + name);
        ref.set(record);
        inProgress.remove(sub);
        cases.put(name, record);
        names.put(sub, name);
      }
    }

    private static Plan enumeration(Class<?> c) {
      Map<String, Object> constants = new LinkedHashMap<>();
      for (Object constant : c.getEnumConstants()) constants.put(((Enum<?>) constant).name(), constant);
      return new Plan.Enumeration(c, Map.copyOf(constants));
    }

    private static Plan.@Nullable Leaf leaf(Class<?> c, String path) {
      if (c == String.class) return new Plan.Leaf(Plan.LeafKind.STRING);
      if (c == boolean.class || c == Boolean.class) return new Plan.Leaf(Plan.LeafKind.BOOLEAN);
      if (c == int.class || c == Integer.class) return new Plan.Leaf(Plan.LeafKind.INT);
      if (c == long.class || c == Long.class) return new Plan.Leaf(Plan.LeafKind.LONG);
      if (c == double.class || c == Double.class) return new Plan.Leaf(Plan.LeafKind.DOUBLE);
      if (c == BigDecimal.class) return new Plan.Leaf(Plan.LeafKind.BIG_DECIMAL);
      if (c == Instant.class) return new Plan.Leaf(Plan.LeafKind.INSTANT);
      if (c == Duration.class) return new Plan.Leaf(Plan.LeafKind.DURATION);
      if (c == LocalDate.class) return new Plan.Leaf(Plan.LeafKind.LOCAL_DATE);
      if (c == LocalDateTime.class) return new Plan.Leaf(Plan.LeafKind.LOCAL_DATE_TIME);
      if (c == UUID.class) return new Plan.Leaf(Plan.LeafKind.UUID);
      if (c == byte[].class) return new Plan.Leaf(Plan.LeafKind.BYTES);
      if (c == float.class
          || c == Float.class
          || c == short.class
          || c == Short.class
          || c == byte.class
          || c == Byte.class
          || c == char.class
          || c == Character.class) {
        throw new EncodingException(
            path,
            c.getSimpleName()
                + " has no form in the encoding; use int, long or double, which every SDK reads");
      }
      if (c == Done.class || c == Void.class || c == void.class) {
        throw new EncodingException(
            path, c.getSimpleName() + " is a reply of its own, never a field of a record");
      }
      return null;
    }
  }

  static Class<?> rawClass(Type type) {
    if (type instanceof Class<?> c) return c;
    if (type instanceof ParameterizedType p) return rawClass(p.getRawType());
    if (type instanceof GenericArrayType g) {
      return java.lang.reflect.Array.newInstance(rawClass(g.getGenericComponentType()), 0)
          .getClass();
    }
    return Object.class;
  }
}
