package com.thinkmorestupidless.ankka.javasdk.codec;

import com.thinkmorestupidless.ankka.javasdk.Done;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The SDK's own serializers: the payload {@code protocol/ENCODING.md} gives each type, the same
 * bytes the Scala SDK writes. A top-level {@code String}, number, boolean or {@code Duration} is
 * text; {@code Done}, {@code Void}, {@code byte[]} and a top-level {@code Optional} are binary;
 * everything else is JSON, planned once from its type.
 */
public final class RecordCodec {

  private static final Map<Type, Serializer<?>> CACHE = new ConcurrentHashMap<>();

  private RecordCodec() {}

  /** The serializer for {@code type}; refuses a type the encoding has no form for. */
  @SuppressWarnings("unchecked")
  public static <A> Serializer<A> forType(Class<A> type) {
    return (Serializer<A>) forType((Type) type);
  }

  /** The serializer for a type that may be generic, such as {@code List<LineItem>}. */
  public static Serializer<?> forType(Type type) {
    Serializer<?> cached = CACHE.get(type);
    if (cached != null) return cached;
    Serializer<?> built = build(type);
    Serializer<?> raced = CACHE.putIfAbsent(type, built);
    return raced == null ? built : raced;
  }

  /** The manifest {@code type} is written under without a {@link Manifest} of its own. */
  public static String defaultManifest(Type type) {
    if (type instanceof ParameterizedType p) {
      StringBuilder sb = new StringBuilder(kebab(Planner.simpleName(p.getRawType()))).append('[');
      Type[] args = p.getActualTypeArguments();
      for (int i = 0; i < args.length; i++) {
        if (i > 0) sb.append(',');
        sb.append(manifestOf(args[i]));
      }
      return sb.append(']').toString();
    }
    return kebab(Planner.simpleName(type));
  }

  /** {@code ShoppingCartEvent} → {@code shopping-cart-event}. */
  public static String kebab(String name) {
    StringBuilder sb = new StringBuilder(name.length() + 4);
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (Character.isUpperCase(c)) {
        boolean boundary =
            i > 0
                && (Character.isLowerCase(name.charAt(i - 1))
                    || Character.isDigit(name.charAt(i - 1))
                    || (i + 1 < name.length() && Character.isLowerCase(name.charAt(i + 1))));
        if (boundary && sb.length() > 0 && sb.charAt(sb.length() - 1) != '-') sb.append('-');
        sb.append(Character.toLowerCase(c));
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  /**
   * A JSON Schema for {@code type}, as the agent's tools and an MCP endpoint's tools describe their
   * input: an object with {@code required} for each field that is not optional, a sum as {@code
   * oneOf} with a constant {@code type}.
   */
  public static String schemaOf(Type type) {
    return Schemas.of(Planner.plan(type));
  }

  private static String manifestOf(Type type) {
    Serializer<?> serializer = forType(type);
    return serializer.manifest();
  }

  private static Serializer<?> build(Type type) {
    Class<?> raw = Planner.rawClass(type);
    if (type instanceof Class<?> c) {
      Serializer<?> text = text(c);
      if (text != null) return text;
      if (c == Done.class) return binary("done", Done.DONE);
      if (c == Void.class || c == void.class) return binary("unit", null);
      if (c == byte[].class) return new Bytes();
    }
    if (raw == Optional.class && type instanceof ParameterizedType p) {
      return new Opt<>(forType(p.getActualTypeArguments()[0]));
    }
    Manifest named = raw.getAnnotation(Manifest.class);
    String manifest = named != null && type instanceof Class<?> ? named.value() : defaultManifest(type);
    Plan plan = Planner.plan(type);
    return new Json<>(manifest, plan, Planner.simpleName(type));
  }

  private static Serializer<?> text(Class<?> c) {
    if (c == String.class) {
      return new Text<String>("string", s -> s, s -> s);
    }
    if (c == Integer.class || c == int.class) {
      return new Text<Integer>("int", String::valueOf, Integer::parseInt);
    }
    if (c == Long.class || c == long.class) {
      return new Text<Long>("long", String::valueOf, Long::parseLong);
    }
    if (c == Double.class || c == double.class) {
      return new Text<Double>("double", Numbers::render, Double::parseDouble);
    }
    if (c == Boolean.class || c == boolean.class) {
      return new Text<Boolean>("boolean", String::valueOf, RecordCodec::parseBoolean);
    }
    if (c == Duration.class) {
      return new Text<Duration>(
          "duration-millis", d -> String.valueOf(d.toMillis()), s -> Duration.ofMillis(Long.parseLong(s)));
    }
    return null;
  }

  private static Boolean parseBoolean(String s) {
    if (s.equals("true")) return Boolean.TRUE;
    if (s.equals("false")) return Boolean.FALSE;
    throw new EncodingException("", "\"" + s + "\" is not a boolean");
  }

  private static <A> Serializer<A> binary(String manifest, A only) {
    return new Serializer<>() {
      @Override
      public String manifest() {
        return manifest;
      }

      @Override
      public ContentType contentType() {
        return ContentType.BINARY;
      }

      @Override
      public byte[] toBytes(A value) {
        return new byte[0];
      }

      @Override
      public A fromBytes(byte[] bytes) {
        return only;
      }
    };
  }

  /** JSON, planned from the type. */
  private record Json<A>(String manifest, Plan plan, String root) implements Serializer<A> {
    @Override
    public byte[] toBytes(A value) {
      return JsonWriter.write(plan, value, root);
    }

    @Override
    @SuppressWarnings("unchecked")
    public A fromBytes(byte[] bytes) {
      return (A) JsonReader.read(plan, bytes, root);
    }
  }

  /** A top-level primitive as UTF-8 text. */
  private record Text<A>(
      String manifest,
      java.util.function.Function<A, String> write,
      java.util.function.Function<String, A> read)
      implements Serializer<A> {
    @Override
    public ContentType contentType() {
      return ContentType.TEXT;
    }

    @Override
    public byte[] toBytes(A value) {
      if (value == null) throw new EncodingException("", "is null");
      return write.apply(value).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public A fromBytes(byte[] bytes) {
      String s = new String(bytes, StandardCharsets.UTF_8);
      try {
        return read.apply(s);
      } catch (NumberFormatException e) {
        throw new EncodingException("", "\"" + s + "\" is not a " + manifest, e);
      }
    }
  }

  private static final class Bytes implements Serializer<byte[]> {
    @Override
    public String manifest() {
      return "bytes";
    }

    @Override
    public ContentType contentType() {
      return ContentType.BINARY;
    }

    @Override
    public byte[] toBytes(byte[] value) {
      return value.clone();
    }

    @Override
    public byte[] fromBytes(byte[] bytes) {
      return bytes.clone();
    }
  }

  /** A top-level {@code Optional}: no bytes for empty, {@code 0x01} and the value's for present. */
  private static final class Opt<A> implements Serializer<Optional<A>> {
    private final Serializer<A> inner;

    Opt(Serializer<A> inner) {
      this.inner = inner;
    }

    @Override
    public String manifest() {
      return "option[" + inner.manifest() + "]";
    }

    @Override
    public ContentType contentType() {
      return ContentType.BINARY;
    }

    @Override
    public byte[] toBytes(Optional<A> value) {
      if (value.isEmpty()) return new byte[0];
      byte[] bytes = inner.toBytes(value.get());
      byte[] out = new byte[bytes.length + 1];
      out[0] = 1;
      System.arraycopy(bytes, 0, out, 1, bytes.length);
      return out;
    }

    @Override
    public Optional<A> fromBytes(byte[] bytes) {
      if (bytes.length == 0) return Optional.empty();
      if (bytes[0] != 1) throw new EncodingException("", "an optional value must begin 0x01");
      return Optional.of(inner.fromBytes(Arrays.copyOfRange(bytes, 1, bytes.length)));
    }
  }
}
