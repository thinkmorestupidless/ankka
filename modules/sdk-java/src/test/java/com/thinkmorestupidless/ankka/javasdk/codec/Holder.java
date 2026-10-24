package com.thinkmorestupidless.ankka.javasdk.codec;

import java.lang.reflect.Type;
import java.util.Optional;

/** Generic types for the fixture suite, read from fields as a developer's would be. */
public final class Holder {

  private Holder() {}

  public static Optional<Integer> optionalIntField = Optional.empty();

  public static final Type optionalInt = genericTypeOf("optionalIntField");

  private static Type genericTypeOf(String field) {
    try {
      return Holder.class.getField(field).getGenericType();
    } catch (NoSuchFieldException e) {
      throw new IllegalStateException(e);
    }
  }
}
