package com.thinkmorestupidless.ankka.javasdk.erasure;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * How an erasure is asked for: in which project (this service's own when empty), held until a
 * date, with a reason, and with a correlation id that a request in another project can share.
 *
 * <pre>{@code
 * erasures().ask(subject, ErasureAsk.inProject("payments").withCorrelation("closure-4411"));
 * }</pre>
 */
public record ErasureAsk(
    Optional<String> project,
    Optional<LocalDate> notBefore,
    Optional<String> reason,
    Optional<String> correlation) {

  public ErasureAsk {
    Objects.requireNonNull(project, "project");
    Objects.requireNonNull(notBefore, "notBefore");
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(correlation, "correlation");
  }

  /** In this service's own project, at once, with no reason or correlation. */
  public static ErasureAsk defaults() {
    return new ErasureAsk(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
  }

  /** In {@code project}, under its grant. */
  public static ErasureAsk inProject(String project) {
    return defaults().withProject(project);
  }

  public ErasureAsk withProject(String project) {
    return new ErasureAsk(Optional.of(project), notBefore, reason, correlation);
  }

  /** Held until {@code date}, and applied by the platform when it passes. */
  public ErasureAsk withNotBefore(LocalDate date) {
    return new ErasureAsk(project, Optional.of(date), reason, correlation);
  }

  public ErasureAsk withReason(String reason) {
    return new ErasureAsk(project, notBefore, Optional.of(reason), correlation);
  }

  public ErasureAsk withCorrelation(String correlation) {
    return new ErasureAsk(project, notBefore, reason, Optional.of(correlation));
  }
}
