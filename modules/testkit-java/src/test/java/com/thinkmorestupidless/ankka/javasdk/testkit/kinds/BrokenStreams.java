package com.thinkmorestupidless.ankka.javasdk.testkit.kinds;

import com.thinkmorestupidless.ankka.javasdk.annotations.Post;
import com.thinkmorestupidless.ankka.javasdk.annotations.Socket;
import com.thinkmorestupidless.ankka.javasdk.annotations.Sse;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;
import com.thinkmorestupidless.ankka.javasdk.http.StreamedBody;

/** Stream routes declared wrongly, each refused at start. */
public class BrokenStreams extends HttpEndpoint {

  @Override
  protected Acl acl() {
    return Acl.allowAll();
  }

  @Sse("/broken/events")
  public String events() {
    return "not a stream";
  }

  @Socket("/broken/socket")
  public void socket() {}

  @Post("/broken/upload")
  public String upload(StreamedBody body) {
    return "";
  }
}
