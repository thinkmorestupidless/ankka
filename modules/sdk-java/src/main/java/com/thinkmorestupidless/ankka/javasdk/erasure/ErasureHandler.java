package com.thinkmorestupidless.ankka.javasdk.erasure;

import com.thinkmorestupidless.ankka.javasdk.DataSubject;

/**
 * The service's own part of an erasure: what the platform cannot see, such as the objects it keeps
 * in a bucket. Run once for each application of an erasure in the service's project, on one
 * instance of the service, and again on each re-run, so it must do nothing the second time that it
 * did not do the first. A run that throws is recorded as failed and run again on the next pass;
 * the platform's own duties do not wait for it.
 *
 * <pre>{@code
 * Ankka.service("players")
 *     .withErasureHandler((ctx, subject) ->
 *         ErasureReport.of(ctx.storage().bucket("kyc").erase(subject)));
 * }</pre>
 */
@FunctionalInterface
public interface ErasureHandler {

  /** Erases what the service keeps of {@code subject} outside its personal fields. */
  ErasureReport handle(ErasureContext context, DataSubject subject);
}
