package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.javasdk.annotations.Get;
import com.thinkmorestupidless.ankka.javasdk.annotations.Post;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;
import com.thinkmorestupidless.ankka.javasdk.http.Problem;
import com.thinkmorestupidless.ankka.javasdk.http.ProblemException;

/** Records on either side of the keyed view, and reads its rows. */
public class JoinedEndpoint extends HttpEndpoint {

  @Override
  protected Acl acl() {
    return Acl.allowAll();
  }

  // The left entity is the row's own key: one left per row.
  @Post("/joined/left/{key}/{holding}")
  public String left(String key, String holding) {
    return componentClient()
        .forEventSourcedEntity(key)
        .call(JoinedLeft::record)
        .invoke(key + "|" + holding);
  }

  @Post("/joined/right/{rightId}")
  public String right(String rightId) {
    return componentClient().forEventSourcedEntity(rightId).call(JoinedRight::record).invoke("");
  }

  @Get("/joined/rows/{key}")
  public JoinedRows.JoinedRow row(String key) {
    return viewClient()
        .forKeyedView(JoinedRows.class)
        .get(key)
        .orElseThrow(() -> new ProblemException(Problem.notFound("no row '" + key + "'")));
  }
}
