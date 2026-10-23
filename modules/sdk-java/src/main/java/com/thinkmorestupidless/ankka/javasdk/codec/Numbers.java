package com.thinkmorestupidless.ankka.javasdk.codec;

/** Numbers as the encoding writes and reads them. */
final class Numbers {

  private Numbers() {}

  /**
   * A double's shortest form that reads back to the same value: {@code 1.5}, {@code 0.1}, {@code
   * 1.0E10}. The JDK's {@code Double.toString} has given the shortest form since 19 and switches to
   * an exponent at the thresholds jsoniter uses, which the fixtures pin; NaN and the infinities
   * have no form in JSON.
   */
  static String render(double d) {
    if (Double.isNaN(d) || Double.isInfinite(d)) {
      throw new EncodingException("", d + " has no form in JSON");
    }
    return Double.toString(d);
  }
}
