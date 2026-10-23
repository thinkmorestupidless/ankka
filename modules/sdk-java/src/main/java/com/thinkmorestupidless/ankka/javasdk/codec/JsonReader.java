package com.thinkmorestupidless.ankka.javasdk.codec;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Reads what {@link JsonWriter} writes, and what every other SDK writes for the same shape:
 * lenient where the encoding is (fields in any order, an unknown field ignored, an absent {@code
 * Optional} empty, {@code "type"} anywhere in a sum case, a bare string for an enum), strict where it
 * is (a missing field, an unknown case, a number that is not an integer where one is declared).
 */
final class JsonReader {

  private final String in;
  private int pos;

  private JsonReader(String in) {
    this.in = in;
  }

  static Object read(Plan plan, byte[] bytes, String root) {
    JsonReader reader = new JsonReader(new String(bytes, StandardCharsets.UTF_8));
    reader.space();
    Object value = reader.value(plan, root);
    reader.space();
    if (reader.pos != reader.in.length()) throw reader.fail(root, "unexpected text after the value");
    if (value == null) throw new EncodingException(root, "is null");
    return value;
  }

  private @Nullable Object value(Plan plan, String path) {
    switch (plan) {
      case Plan.Ref ref -> {
        return value(ref.target(), path);
      }
      case Plan.Opt opt -> {
        if (peekNull()) return Optional.empty();
        return Optional.of(nonNull(opt.inner(), path));
      }
      case Plan.OrNull orNull -> {
        if (peekNull()) return null;
        return nonNull(orNull.inner(), path);
      }
      default -> {
        if (peekNull()) throw new EncodingException(path, "is null, and is not optional");
        return nonNull(plan, path);
      }
    }
  }

  private Object nonNull(Plan plan, String path) {
    return switch (plan) {
      case Plan.Record record -> {
        expect('{', path);
        yield fields(record, path);
      }
      case Plan.Sum sum -> sum(sum, path);
      case Plan.Enumeration enumeration -> enumeration(enumeration, path);
      case Plan.Seq seq -> seq(seq, path);
      case Plan.Dictionary dictionary -> dictionary(dictionary, path);
      case Plan.Leaf leaf -> leaf(leaf.kind(), path);
      case Plan.Ref ref -> nonNull(ref.target(), path);
      case Plan.Opt opt -> Optional.of(nonNull(opt.inner(), path));
      case Plan.OrNull orNull -> nonNull(orNull.inner(), path);
    };
  }

  /** The fields of a record, after its opening brace; {@code "type"} is skipped as unknown. */
  private Object fields(Plan.Record record, String path) {
    List<Plan.Field> fields = record.fields();
    int n = fields.size();
    @Nullable Object[] values = new Object[n];
    boolean[] present = new boolean[n];
    space();
    if (peek() == '}') {
      pos++;
    } else {
      while (true) {
        space();
        String key = string(path);
        space();
        expect(':', path);
        space();
        int index = indexOf(fields, key);
        if (index < 0) {
          skipValue(path);
        } else {
          Plan.Field field = fields.get(index);
          values[index] = value(field.plan(), path + "." + key);
          present[index] = true;
        }
        space();
        char c = next(path);
        if (c == '}') break;
        if (c != ',') throw fail(path, "expected ',' or '}'");
      }
    }
    for (int i = 0; i < n; i++) {
      if (present[i]) continue;
      Plan.Field field = fields.get(i);
      if (field.plan() instanceof Plan.Opt) {
        values[i] = Optional.empty();
      } else if (field.hasDefault()) {
        values[i] = null;
      } else if (field.plan() instanceof Plan.OrNull) {
        values[i] = null;
        present[i] = true;
      } else {
        throw new EncodingException(path + "." + field.name(), "is missing");
      }
    }
    return record.shape().construct(values, present);
  }

  private static int indexOf(List<Plan.Field> fields, String key) {
    for (int i = 0; i < fields.size(); i++) if (fields.get(i).name().equals(key)) return i;
    return -1;
  }

  private Object sum(Plan.Sum sum, String path) {
    expect('{', path);
    int start = pos;
    String name = findType(path);
    pos = start;
    Plan.Record record = sum.cases().get(name);
    if (record == null) {
      throw new EncodingException(
          path, "\"" + name + "\" is not a case of " + sum.type().getSimpleName());
    }
    return fields(record, path);
  }

  /** Scans the object after its brace for {@code "type"} and returns its value. */
  private String findType(String path) {
    space();
    if (peek() == '}') throw new EncodingException(path, "has no \"type\"");
    while (true) {
      space();
      String key = string(path);
      space();
      expect(':', path);
      space();
      if (key.equals("type")) return string(path);
      skipValue(path);
      space();
      char c = next(path);
      if (c == '}') throw new EncodingException(path, "has no \"type\"");
      if (c != ',') throw fail(path, "expected ',' or '}'");
    }
  }

  private Object enumeration(Plan.Enumeration enumeration, String path) {
    String name;
    if (peek() == '"') {
      name = string(path);
    } else {
      expect('{', path);
      int start = pos;
      name = findType(path);
      pos = start;
      fields(new Plan.Record(enumeration.type(), List.of(), NoFields.SHAPE), path);
    }
    Object constant = enumeration.constants().get(name);
    if (constant == null) {
      throw new EncodingException(
          path, "\"" + name + "\" is not a constant of " + enumeration.type().getSimpleName());
    }
    return constant;
  }

  private Object seq(Plan.Seq seq, String path) {
    expect('[', path);
    List<@Nullable Object> items = new ArrayList<>();
    space();
    if (peek() == ']') {
      pos++;
    } else {
      while (true) {
        space();
        items.add(value(seq.element(), path + "[" + items.size() + "]"));
        space();
        char c = next(path);
        if (c == ']') break;
        if (c != ',') throw fail(path, "expected ',' or ']'");
      }
    }
    return switch (seq.kind()) {
      case LIST -> Collections.unmodifiableList(items);
      case SET -> {
        Set<@Nullable Object> set = new LinkedHashSet<>(items);
        yield Collections.unmodifiableSet(set);
      }
      case ARRAY -> {
        Class<?> type = seq.arrayType();
        Object array = Array.newInstance(type == null ? Object.class : type, items.size());
        for (int i = 0; i < items.size(); i++) Array.set(array, i, items.get(i));
        yield array;
      }
    };
  }

  private Object dictionary(Plan.Dictionary dictionary, String path) {
    expect('{', path);
    Map<String, @Nullable Object> map = new LinkedHashMap<>();
    space();
    if (peek() == '}') {
      pos++;
    } else {
      while (true) {
        space();
        String key = string(path);
        space();
        expect(':', path);
        space();
        map.put(key, value(dictionary.value(), path + "." + key));
        space();
        char c = next(path);
        if (c == '}') break;
        if (c != ',') throw fail(path, "expected ',' or '}'");
      }
    }
    return Collections.unmodifiableMap(map);
  }

  private Object leaf(Plan.LeafKind kind, String path) {
    try {
      return switch (kind) {
        case STRING -> string(path);
        case BOOLEAN -> bool(path);
        case INT -> {
          String token = number(path);
          if (!integral(token)) throw new EncodingException(path, token + " is not an integer");
          try {
            yield Integer.parseInt(token);
          } catch (NumberFormatException e) {
            throw new EncodingException(path, token + " does not fit an int");
          }
        }
        case LONG -> {
          String token = number(path);
          if (!integral(token)) throw new EncodingException(path, token + " is not an integer");
          try {
            yield Long.parseLong(token);
          } catch (NumberFormatException e) {
            throw new EncodingException(path, token + " does not fit a long");
          }
        }
        case DOUBLE -> Double.parseDouble(number(path));
        case BIG_DECIMAL -> {
          if (peek() == '"') yield new BigDecimal(string(path));
          yield new BigDecimal(number(path));
        }
        case INSTANT -> Instant.parse(string(path));
        case DURATION -> Duration.parse(string(path));
        case LOCAL_DATE -> LocalDate.parse(string(path));
        case LOCAL_DATE_TIME -> LocalDateTime.parse(string(path));
        case UUID -> java.util.UUID.fromString(string(path));
        case BYTES -> Base64.getDecoder().decode(string(path));
      };
    } catch (DateTimeParseException | IllegalArgumentException e) {
      if (e instanceof EncodingException encoding) throw encoding;
      throw new EncodingException(path, e.getMessage() == null ? e.toString() : e.getMessage(), e);
    }
  }

  private static boolean integral(String token) {
    for (int i = 0; i < token.length(); i++) {
      char c = token.charAt(i);
      if (c == '.' || c == 'e' || c == 'E') return false;
    }
    return true;
  }

  // ---- the lexer

  private void space() {
    while (pos < in.length()) {
      char c = in.charAt(pos);
      if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++;
      else break;
    }
  }

  private char peek() {
    return pos < in.length() ? in.charAt(pos) : '\0';
  }

  private boolean peekNull() {
    if (in.startsWith("null", pos)) {
      pos += 4;
      return true;
    }
    return false;
  }

  private char next(String path) {
    if (pos >= in.length()) throw fail(path, "the input ended");
    return in.charAt(pos++);
  }

  private void expect(char c, String path) {
    if (pos >= in.length() || in.charAt(pos) != c) throw fail(path, "expected '" + c + "'");
    pos++;
  }

  private boolean bool(String path) {
    if (in.startsWith("true", pos)) {
      pos += 4;
      return true;
    }
    if (in.startsWith("false", pos)) {
      pos += 5;
      return false;
    }
    throw fail(path, "expected true or false");
  }

  private String number(String path) {
    int start = pos;
    if (peek() == '-') pos++;
    while (pos < in.length()) {
      char c = in.charAt(pos);
      if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') pos++;
      else break;
    }
    if (pos == start) throw fail(path, "expected a number");
    return in.substring(start, pos);
  }

  private String string(String path) {
    expect('"', path);
    StringBuilder sb = null;
    int start = pos;
    while (true) {
      if (pos >= in.length()) throw fail(path, "a string was not closed");
      char c = in.charAt(pos);
      if (c == '"') {
        String s = sb == null ? in.substring(start, pos) : sb.append(in, start, pos).toString();
        pos++;
        return s;
      }
      if (c == '\\') {
        if (sb == null) sb = new StringBuilder();
        sb.append(in, start, pos);
        pos++;
        char e = next(path);
        switch (e) {
          case '"' -> sb.append('"');
          case '\\' -> sb.append('\\');
          case '/' -> sb.append('/');
          case 'b' -> sb.append('\b');
          case 'f' -> sb.append('\f');
          case 'n' -> sb.append('\n');
          case 'r' -> sb.append('\r');
          case 't' -> sb.append('\t');
          case 'u' -> {
            if (pos + 4 > in.length()) throw fail(path, "a \\u escape was cut short");
            sb.append((char) Integer.parseInt(in.substring(pos, pos + 4), 16));
            pos += 4;
          }
          default -> throw fail(path, "an unknown escape \\" + e);
        }
        start = pos;
      } else {
        pos++;
      }
    }
  }

  private void skipValue(String path) {
    space();
    char c = peek();
    switch (c) {
      case '"' -> string(path);
      case '{', '[' -> {
        char close = c == '{' ? '}' : ']';
        pos++;
        space();
        if (peek() == close) {
          pos++;
          return;
        }
        while (true) {
          space();
          if (c == '{') {
            string(path);
            space();
            expect(':', path);
          }
          skipValue(path);
          space();
          char d = next(path);
          if (d == close) return;
          if (d != ',') throw fail(path, "expected ',' or '" + close + "'");
        }
      }
      case 't', 'f' -> bool(path);
      case 'n' -> {
        if (!peekNull()) throw fail(path, "expected a value");
      }
      default -> number(path);
    }
  }

  private EncodingException fail(String path, String message) {
    return new EncodingException(path, message + " at offset " + pos);
  }

  /** The shape of an object with no fields, read for an enum written as {"type":"A"}. */
  private static final class NoFields implements RecordShapes.Shape {
    static final NoFields SHAPE = new NoFields();

    @Override
    public List<RecordShapes.FieldShape> fields() {
      return List.of();
    }

    @Override
    public Object construct(@Nullable Object[] values, boolean[] present) {
      return this;
    }
  }
}
