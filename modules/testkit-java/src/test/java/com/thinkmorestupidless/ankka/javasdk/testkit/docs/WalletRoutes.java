package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.javasdk.annotations.Body;
import com.thinkmorestupidless.ankka.javasdk.annotations.Post;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.Callers;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;
import com.thinkmorestupidless.ankka.javasdk.http.Problem;
import com.thinkmorestupidless.ankka.javasdk.http.ProblemException;

// docs:start granted-callers
/**
 * A player's wallet, which another project may be granted. The endpoint's ACL admits granted
 * callers: whether anyone outside the project may ever call these routes is decided here, in
 * reviewed code; who may, today, is a grant the project's owners make and revoke as data.
 */
public class WalletRoutes extends HttpEndpoint {

  public record DepositRequest(long amount) {}

  public record DepositMade(String key, String currency, long amount, String by) {}

  @Override
  protected Acl acl() {
    return Acl.allowCallers(Callers.granted());
  }

  /**
   * A deposit, with the caller's idempotency key: a granted call is delivered at least once, and
   * the wallet applies each key once. The answer names the caller as the platform established it.
   */
  @Post("/v1/wallets/{player}/{currency}/deposits")
  public DepositMade deposit(String player, String currency, @Body DepositRequest body) {
    var key =
        request()
            .header("Idempotency-Key")
            .orElseThrow(
                () ->
                    new ProblemException(
                        Problem.badRequest("a deposit needs an Idempotency-Key header")));
    return new DepositMade(key, currency, body.amount(), CallerRoutes.describe(caller()));
  }
}
// docs:end granted-callers
