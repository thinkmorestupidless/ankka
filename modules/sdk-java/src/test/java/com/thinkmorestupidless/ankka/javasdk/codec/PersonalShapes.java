package com.thinkmorestupidless.ankka.javasdk.codec;

import com.thinkmorestupidless.ankka.javasdk.Personal;
import java.time.LocalDate;

/** One record per value type of {@code protocol/fixtures/personal/}, each one personal field. */
public final class PersonalShapes {

  private PersonalShapes() {}

  public record Address(String line1, String city) {}

  public record OfString(Personal<String> field) {}

  public record OfDate(Personal<LocalDate> field) {}

  public record OfAddress(Personal<Address> field) {}
}
