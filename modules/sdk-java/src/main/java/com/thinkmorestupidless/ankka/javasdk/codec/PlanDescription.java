package com.thinkmorestupidless.ankka.javasdk.codec;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A plan written as plain JSON, for the bridge to read as a shape (feature 067). The plan's cases
 * stay this package's; what crosses is text, as a tool's schema does.
 *
 * <pre>
 * node   := {"leaf": KIND} | {"nullable": node} | {"array": node} | {"dict": node}
 *         | {"named": full, "simple": s, "shape": body} | {"ref": full, "simple": s}
 * body   := {"object": [{"name": n, "required": bool, "shape": node}]}
 *         | {"oneOf": [{"name": n, "shape": {"object": [...]}}]}
 * </pre>
 *
 * A record, a sealed interface and an enum are named: written whole the first time, referred to
 * after, which also ends a type that contains itself.
 */
final class PlanDescription {

  private PlanDescription() {}

  static String of(Plan plan) {
    StringBuilder out = new StringBuilder();
    node(plan, out, new HashSet<>());
    return out.toString();
  }

  private static void node(Plan plan, StringBuilder out, Set<String> written) {
    switch (plan) {
      case Plan.Ref ref -> node(ref.target(), out, written);
      case Plan.Record record -> named(record.type(), out, written, () -> object(record, out, written));
      case Plan.Sum sum ->
          named(
              sum.type(),
              out,
              written,
              () -> {
                out.append("{\"oneOf\":[");
                boolean first = true;
                for (String name : declared(sum)) {
                  if (!first) out.append(',');
                  first = false;
                  out.append("{\"name\":").append(str(name)).append(",\"shape\":");
                  object(sum.cases().get(name), out, written);
                  out.append('}');
                }
                out.append("]}");
              });
      case Plan.Enumeration enumeration ->
          named(
              enumeration.type(),
              out,
              written,
              () -> {
                out.append("{\"oneOf\":[");
                boolean first = true;
                for (String name : declared(enumeration)) {
                  if (!first) out.append(',');
                  first = false;
                  out.append("{\"name\":").append(str(name)).append(",\"shape\":{\"object\":[]}}");
                }
                out.append("]}");
              });
      case Plan.Opt opt -> wrap("nullable", opt.inner(), out, written);
      case Plan.OrNull orNull -> wrap("nullable", orNull.inner(), out, written);
      case Plan.Personal personal -> node(personal.inner(), out, written);
      case Plan.Seq seq -> wrap("array", seq.element(), out, written);
      case Plan.Dictionary dictionary -> wrap("dict", dictionary.value(), out, written);
      case Plan.Leaf leaf -> out.append("{\"leaf\":").append(str(leaf.kind().name())).append('}');
    }
  }

  /** A sum's case names in declaration order, as Scala's derivation lists a sealed trait's. */
  private static List<String> declared(Plan.Sum sum) {
    List<String> names = new ArrayList<>();
    leaves(sum.type(), sum, names);
    for (String name : new TreeSet<>(sum.cases().keySet())) if (!names.contains(name)) names.add(name);
    return names;
  }

  private static void leaves(Class<?> type, Plan.Sum sum, List<String> names) {
    Class<?>[] permitted = type.getPermittedSubclasses();
    if (permitted == null) return;
    for (Class<?> c : permitted) {
      String name = sum.names().get(c);
      if (name != null) names.add(name);
      else leaves(c, sum, names);
    }
  }

  /** An enum's constant names in declaration order. */
  private static List<String> declared(Plan.Enumeration enumeration) {
    List<String> names = new ArrayList<>();
    for (Object constant : enumeration.type().getEnumConstants()) {
      for (Map.Entry<String, Object> e : enumeration.constants().entrySet()) {
        if (e.getValue() == constant) names.add(e.getKey());
      }
    }
    return names;
  }

  private static void wrap(String kind, Plan inner, StringBuilder out, Set<String> written) {
    out.append("{\"").append(kind).append("\":");
    node(inner, out, written);
    out.append('}');
  }

  private static void named(Class<?> type, StringBuilder out, Set<String> written, Runnable body) {
    String full = type.getName().replace('$', '.');
    String simple = type.getSimpleName();
    if (!written.add(full)) {
      out.append("{\"ref\":").append(str(full)).append(",\"simple\":").append(str(simple)).append('}');
      return;
    }
    out.append("{\"named\":").append(str(full)).append(",\"simple\":").append(str(simple));
    out.append(",\"shape\":");
    body.run();
    out.append('}');
  }

  private static void object(Plan.Record record, StringBuilder out, Set<String> written) {
    out.append("{\"object\":[");
    boolean first = true;
    for (Plan.Field field : record.fields()) {
      if (!first) out.append(',');
      first = false;
      out.append("{\"name\":").append(str(field.name()));
      out.append(",\"required\":").append(required(field));
      out.append(",\"shape\":");
      node(field.plan(), out, written);
      out.append('}');
    }
    out.append("]}");
  }

  /** What a reader needs: neither defaulted, nor optional, nor a collection it reads as empty. */
  private static boolean required(Plan.Field field) {
    if (field.hasDefault()) return false;
    Plan plan = field.plan();
    while (plan instanceof Plan.Personal personal) plan = personal.inner();
    return !(plan instanceof Plan.Opt
        || plan instanceof Plan.OrNull
        || plan instanceof Plan.Seq
        || plan instanceof Plan.Dictionary);
  }

  private static String str(String s) {
    StringBuilder b = new StringBuilder("\"");
    for (char c : s.toCharArray()) {
      if (c == '"' || c == '\\') b.append('\\');
      b.append(c);
    }
    return b.append('"').toString();
  }
}
