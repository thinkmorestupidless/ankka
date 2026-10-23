package com.thinkmorestupidless.ankka.javasdk.reference;

import com.thinkmorestupidless.ankka.javasdk.annotations.Get;
import com.thinkmorestupidless.ankka.javasdk.annotations.Post;
import com.thinkmorestupidless.ankka.javasdk.annotations.Sse;
import com.thinkmorestupidless.ankka.javasdk.client.EntityRef;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;
import com.thinkmorestupidless.ankka.javasdk.http.SseEvent;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;

/** Places nodes of a tree, asks what is under one, and streams and watches the rows. */
public class TreeEndpoint extends HttpEndpoint {

  @Override
  protected Acl acl() {
    return Acl.allowAll();
  }

  private EntityRef node(String id) {
    return componentClient().forEventSourcedEntity(id);
  }

  @Post("/tree/{nodeId}")
  public String place(String nodeId) {
    return node(nodeId).call(TreeNode::place).invoke("");
  }

  @Post("/tree/{nodeId}/under/{parentId}")
  public String placeUnder(String nodeId, String parentId) {
    return node(nodeId).call(TreeNode::place).invoke(parentId);
  }

  @Get("/tree/{nodeId}/below")
  public List<String> below(String nodeId) {
    return viewClient().forView(TreeRows.class).ask(TreeRows.UNDER, Map.of("row", nodeId)).stream()
        .map(TreeRows.TreeRow::key)
        .toList();
  }

  // Every row's key, as the database yields the rows: one event each, then the end.
  @Sse("/tree/streamed")
  public Flow.Publisher<SseEvent> streamed() {
    var rows = viewClient().forView(TreeRows.class).askStream(TreeRows.ALL_ROWS, Map.of());
    return Wire.map(rows, row -> SseEvent.text(row.key()));
  }

  // The rows now, `caught-up`, then each row as it is written, as named events.
  @Sse("/tree/watched")
  public Flow.Publisher<SseEvent> watched() {
    return viewClient().forView(TreeRows.class).watch(TreeRows.ALL_ROWS, Map.of()).asSse();
  }
}
