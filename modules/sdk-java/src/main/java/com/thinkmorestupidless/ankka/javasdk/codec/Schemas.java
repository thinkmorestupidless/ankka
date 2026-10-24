package com.thinkmorestupidless.ankka.javasdk.codec;

import java.util.IdentityHashMap;
import java.util.Map;

/** A JSON Schema from a plan: what a model is told a tool's input looks like. */
final class Schemas {

  private Schemas() {}

  static String of(Plan plan) {
    StringBuilder out = new StringBuilder();
    write(plan, out, new IdentityHashMap<>());
    return out.toString();
  }

  private static void write(Plan plan, StringBuilder out, Map<Plan, Boolean> seen) {
    switch (plan) {
      case Plan.Ref ref -> {
        if (seen.containsKey(ref)) {
          out.append("{}");
          return;
        }
        seen.put(ref, Boolean.TRUE);
        write(ref.target(), out, seen);
        seen.remove(ref);
      }
      case Plan.Record record -> object(record, null, out, seen);
      case Plan.Sum sum -> {
        out.append("{\"oneOf\":[");
        boolean first = true;
        for (Map.Entry<String, Plan.Record> c : sum.cases().entrySet()) {
          if (!first) out.append(',');
          first = false;
          object(c.getValue(), c.getKey(), out, seen);
        }
        out.append("]}");
      }
      case Plan.Enumeration enumeration -> {
        out.append("{\"type\":\"object\",\"properties\":{\"type\":{\"type\":\"string\",\"enum\":[");
        boolean first = true;
        for (String name : enumeration.constants().keySet()) {
          if (!first) out.append(',');
          first = false;
          out.append('"').append(name).append('"');
        }
        out.append("]}},\"required\":[\"type\"]}");
      }
      case Plan.Opt opt -> write(opt.inner(), out, seen);
      case Plan.OrNull orNull -> write(orNull.inner(), out, seen);
      case Plan.Seq seq -> {
        out.append("{\"type\":\"array\",\"items\":");
        write(seq.element(), out, seen);
        out.append('}');
      }
      case Plan.Dictionary dictionary -> {
        out.append("{\"type\":\"object\",\"additionalProperties\":");
        write(dictionary.value(), out, seen);
        out.append('}');
      }
      case Plan.Leaf leaf ->
          out.append(
              switch (leaf.kind()) {
                case STRING, BYTES -> "{\"type\":\"string\"}";
                case BOOLEAN -> "{\"type\":\"boolean\"}";
                case INT, LONG -> "{\"type\":\"integer\"}";
                case DOUBLE, BIG_DECIMAL -> "{\"type\":\"number\"}";
                case INSTANT, LOCAL_DATE_TIME -> "{\"type\":\"string\",\"format\":\"date-time\"}";
                case LOCAL_DATE -> "{\"type\":\"string\",\"format\":\"date\"}";
                case DURATION -> "{\"type\":\"string\",\"format\":\"duration\"}";
                case UUID -> "{\"type\":\"string\",\"format\":\"uuid\"}";
              });
    }
  }

  private static void object(
      Plan.Record record,
      @org.jspecify.annotations.Nullable String caseName,
      StringBuilder out,
      Map<Plan, Boolean> seen) {
    out.append("{\"type\":\"object\",\"properties\":{");
    boolean first = true;
    if (caseName != null) {
      out.append("\"type\":{\"const\":\"").append(caseName).append("\"}");
      first = false;
    }
    for (Plan.Field field : record.fields()) {
      if (!first) out.append(',');
      first = false;
      out.append('"').append(field.name()).append("\":");
      write(field.plan(), out, seen);
    }
    out.append("},\"required\":[");
    first = true;
    if (caseName != null) {
      out.append("\"type\"");
      first = false;
    }
    for (Plan.Field field : record.fields()) {
      if (field.plan() instanceof Plan.Opt
          || field.plan() instanceof Plan.OrNull
          || field.hasDefault()) continue;
      if (!first) out.append(',');
      first = false;
      out.append('"').append(field.name()).append('"');
    }
    out.append("]}");
  }
}
