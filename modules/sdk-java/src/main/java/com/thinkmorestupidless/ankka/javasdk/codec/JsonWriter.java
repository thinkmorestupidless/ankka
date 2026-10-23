package com.thinkmorestupidless.ankka.javasdk.codec;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Writes a value as {@code protocol/ENCODING.md} says, following its plan: every field written, an
 * empty {@code Optional} as {@code null}, a sum case's {@code "type"} first, an enum as {@code
 * {"type":"A"}}. A {@code null} where the plan allows none is refused, naming where.
 */
final class JsonWriter {

  private final StringBuilder out = new StringBuilder(128);

  private JsonWriter() {}

  static byte[] write(Plan plan, Object value, String root) {
    JsonWriter writer = new JsonWriter();
    writer.value(plan, value, root);
    return writer.out.toString().getBytes(StandardCharsets.UTF_8);
  }

  static String writeString(Plan plan, Object value, String root) {
    JsonWriter writer = new JsonWriter();
    writer.value(plan, value, root);
    return writer.out.toString();
  }

  private void value(Plan plan, @Nullable Object value, String path) {
    switch (plan) {
      case Plan.Ref ref -> value(ref.target(), value, path);
      case Plan.Opt opt -> {
        if (value == null) {
          throw new EncodingException(
              path, "is null; an absent value is Optional.empty(), never a null Optional");
        }
        Optional<?> optional = (Optional<?>) value;
        if (optional.isPresent()) value(opt.inner(), optional.get(), path);
        else out.append("null");
      }
      case Plan.OrNull orNull -> {
        if (value == null) out.append("null");
        else value(orNull.inner(), value, path);
      }
      default -> {
        if (value == null) {
          throw new EncodingException(
              path, "is null; a value the encoding writes may not be null (use Optional)");
        }
        nonNull(plan, value, path);
      }
    }
  }

  private void nonNull(Plan plan, Object value, String path) {
    switch (plan) {
      case Plan.Record record -> {
        out.append('{');
        fields(record, value, path, false);
        out.append('}');
      }
      case Plan.Sum sum -> {
        String name = sum.names().get(value.getClass());
        if (name == null) {
          throw new EncodingException(
              path, value.getClass().getName() + " is not a case of " + sum.type().getSimpleName());
        }
        out.append("{\"type\":");
        string(name);
        fields(sum.cases().get(name), value, path, true);
        out.append('}');
      }
      case Plan.Enumeration ignored -> {
        out.append("{\"type\":");
        string(((Enum<?>) value).name());
        out.append('}');
      }
      case Plan.Seq seq -> {
        out.append('[');
        int i = 0;
        if (seq.kind() == Plan.SeqKind.ARRAY) {
          int length = Array.getLength(value);
          for (; i < length; i++) {
            if (i > 0) out.append(',');
            value(seq.element(), Array.get(value, i), path + "[" + i + "]");
          }
        } else {
          for (Object element : (Collection<?>) value) {
            if (i > 0) out.append(',');
            value(seq.element(), element, path + "[" + i + "]");
            i++;
          }
        }
        out.append(']');
      }
      case Plan.Dictionary dictionary -> {
        out.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
          if (!first) out.append(',');
          first = false;
          String key = (String) entry.getKey();
          if (key == null) throw new EncodingException(path, "a map's key is null");
          string(key);
          out.append(':');
          value(dictionary.value(), entry.getValue(), path + "." + key);
        }
        out.append('}');
      }
      case Plan.Leaf leaf -> leaf(leaf.kind(), value);
      case Plan.Ref ref -> value(ref.target(), value, path);
      case Plan.Opt opt -> value(opt, value, path);
      case Plan.OrNull orNull -> value(orNull.inner(), value, path);
    }
  }

  private void fields(Plan.Record record, Object value, String path, boolean afterType) {
    boolean first = !afterType;
    for (Plan.Field field : record.fields()) {
      if (!first) out.append(',');
      first = false;
      string(field.name());
      out.append(':');
      value(field.plan(), field.get().apply(value), path + "." + field.name());
    }
  }

  private void leaf(Plan.LeafKind kind, Object value) {
    switch (kind) {
      case STRING -> string((String) value);
      case BOOLEAN -> out.append(((Boolean) value).booleanValue());
      case INT -> out.append(((Integer) value).intValue());
      case LONG -> out.append(((Long) value).longValue());
      case DOUBLE -> out.append(Numbers.render(((Double) value).doubleValue()));
      case BIG_DECIMAL -> out.append(((BigDecimal) value).toString());
      case INSTANT, DURATION, LOCAL_DATE, LOCAL_DATE_TIME, UUID -> string(value.toString());
      case BYTES -> string(Base64.getEncoder().encodeToString((byte[]) value));
    }
  }

  private void string(String s) {
    out.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append("\\u00");
            out.append(Character.forDigit(c >> 4, 16));
            out.append(Character.forDigit(c & 0xF, 16));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }
}
