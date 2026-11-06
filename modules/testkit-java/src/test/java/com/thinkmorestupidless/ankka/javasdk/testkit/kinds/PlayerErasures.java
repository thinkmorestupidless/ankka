package com.thinkmorestupidless.ankka.javasdk.testkit.kinds;

import com.thinkmorestupidless.ankka.javasdk.DataSubject;
import com.thinkmorestupidless.ankka.javasdk.annotations.Post;
import com.thinkmorestupidless.ankka.javasdk.erasure.ErasureAsk;
import com.thinkmorestupidless.ankka.javasdk.erasure.ErasureAsked;
import com.thinkmorestupidless.ankka.javasdk.erasure.ErasureContext;
import com.thinkmorestupidless.ankka.javasdk.erasure.ErasureReport;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * {@code players}' part of an erasure in Java (feature 042): its handler erases the objects it
 * keeps of the data subject in its bucket, and its endpoint asks for an erasure as the service.
 */
public final class PlayerErasures {

  private PlayerErasures() {}

  /** Each run: the data subject, the erasure's sequence, whether it was a re-run. */
  public record Run(String subject, long seq, boolean rerun) {}

  public static final Queue<Run> runs = new ConcurrentLinkedQueue<>();

  /** The handler: erases the subject's objects in the service's own bucket. */
  public static ErasureReport handle(ErasureContext ctx, DataSubject subject) {
    runs.add(new Run(subject.value(), ctx.erasureSeq(), ctx.rerun()));
    return ErasureReport.of(ctx.storage().defaultBucket().erase(subject));
  }

  /** Asks for erasures as the service. */
  public static class Asking extends HttpEndpoint {

    @Override
    protected Acl acl() {
      return Acl.allowAll();
    }

    @Post("/forget/{id}")
    public String forget(String id) {
      ErasureAsked asked = erasures().ask(DataSubject.of("player/" + id));
      return asked.id() + " " + asked.state();
    }

    @Post("/forget-cardholder/{id}")
    public String forgetCardholder(String id) {
      ErasureAsked asked =
          erasures()
              .ask(
                  DataSubject.of("cardholder/" + id),
                  ErasureAsk.inProject("payments")
                      .withReason("account closed")
                      .withCorrelation("closure-4411"));
      return asked.id() + " " + asked.state();
    }
  }
}
