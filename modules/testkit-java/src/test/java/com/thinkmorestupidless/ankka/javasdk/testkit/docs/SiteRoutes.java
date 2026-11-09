package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.javasdk.annotations.Get;
import com.thinkmorestupidless.ankka.javasdk.annotations.Post;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;
import com.thinkmorestupidless.ankka.javasdk.http.Response;
import java.nio.charset.StandardCharsets;

/** An endpoint that serves a website rather than an API. */
public class SiteRoutes extends HttpEndpoint {

  static final String PAGE = "<h1>Your account</h1>";
  static final byte[] STYLESHEET = "h1 { color: teal }".getBytes(StandardCharsets.UTF_8);
  static final String COOKIE = "session=abc; Path=/; HttpOnly";

  @Override
  protected Acl acl() {
    return Acl.allowAll();
  }

  // docs:start pages
  @Get("/site/account")
  public Response account() {
    return Response.html(PAGE).header("Set-Cookie", COOKIE);
  }

  @Post("/site/logout")
  public Response logout() {
    return Response.redirect("/"); // 303 See Other, Location: /
  }

  @Get("/site/style.css")
  public Response stylesheet() {
    return Response.bytes(STYLESHEET, "text/css");
  }
  // docs:end pages
}
