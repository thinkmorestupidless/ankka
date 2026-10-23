package com.thinkmorestupidless.ankka.javasdk.routes;

import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.annotations.Body;
import com.thinkmorestupidless.ankka.javasdk.annotations.Get;
import com.thinkmorestupidless.ankka.javasdk.annotations.Header;
import com.thinkmorestupidless.ankka.javasdk.annotations.Path;
import com.thinkmorestupidless.ankka.javasdk.annotations.Post;
import com.thinkmorestupidless.ankka.javasdk.annotations.Put;
import com.thinkmorestupidless.ankka.javasdk.annotations.QueryParam;
import com.thinkmorestupidless.ankka.javasdk.http.Acl;
import com.thinkmorestupidless.ankka.javasdk.http.Caller;
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint;
import com.thinkmorestupidless.ankka.javasdk.http.Problem;
import com.thinkmorestupidless.ankka.javasdk.http.ProblemException;
import com.thinkmorestupidless.ankka.javasdk.http.Request;
import com.thinkmorestupidless.ankka.javasdk.http.Response;
import java.util.Optional;

/** One route per way of binding a parameter and per way of answering. */
public class Things extends HttpEndpoint {

  @Override
  protected Acl acl() {
    return Acl.allowAll();
  }

  @Get("/things/special")
  public String special() {
    return "literal";
  }

  @Get("/things/{id}")
  public Thing byName(String id) {
    return new Thing(id, id.length());
  }

  @Get("/sized/{n}")
  public Thing byAnnotation(@Path("n") int size) {
    return new Thing("sized", size);
  }

  @Get("/search")
  public String search(@QueryParam("q") String q, @QueryParam("limit") Optional<Integer> limit) {
    return q + ":" + limit.map(String::valueOf).orElse("none");
  }

  @Get("/echo-header")
  public String header(@Header("X-Greeting") String greeting, Caller caller) {
    return greeting + " from " + caller.getClass().getSimpleName();
  }

  @Post("/things")
  public Response create(@Body Thing thing) {
    return Response.created(thing).header("Location", "/things/" + thing.id());
  }

  @Put("/things/{id}")
  public Done replace(String id, @Body Thing thing) {
    if (!id.equals(thing.id())) throw new ProblemException(Problem.conflict("the id is " + id));
    return Done.DONE;
  }

  @Get("/request")
  public String request(Request request) {
    return request.method() + " " + request.path();
  }

  @Get("/from-request")
  public String fromBase() {
    return request().header("X-Greeting").orElse("none");
  }
}
