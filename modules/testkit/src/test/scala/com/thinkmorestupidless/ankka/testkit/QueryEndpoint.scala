package com.thinkmorestupidless.ankka.testkit

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.Codecs
import com.thinkmorestupidless.ankka.http.*

final case class SearchResult(term: String, limit: Int, tags: List[String], verbose: Boolean)

/** Exercises query parameters and headers from inside handlers. */
// No constructor parameter: these handlers call no components, and `HttpServer.of` takes
// a factory, so an endpoint need not accept clients it does not use.
final class QueryEndpoint extends HttpEndpoint("/search"):

  private given JsonValueCodec[SearchResult] = Codecs.make[SearchResult]

  val acl: Acl = Acl.AllowAll

  // docs:start query
  /** Required, optional-with-default, repeated, and flag parameters. */
  get("/") { () =>
    SearchResult(
      term = query.required[String]("q"),
      limit = query.optional[Int]("limit").getOrElse(20),
      tags = query.all[String]("tag").toList,
      verbose = query.flag("verbose")
    )
  }
  // docs:end query

  /** Query parameters alongside a path parameter. */
  get("/in/{category}") { (category: String) =>
    s"$category:${query.required[String]("q")}:${query.optional[Int]("limit").getOrElse(0)}"
  }

  // docs:start header
  /** Headers. */
  get("/trace") { () =>
    request.header("X-Trace-Id").getOrElse("none")
  }
  // docs:end header

  /** The rest of the request context. */
  get("/describe") { () =>
    s"${request.method} ${request.path} params=${query.toSeq.size}"
  }

  /**
   * A streaming route reading query parameters.
   *
   * The values are read while *building* the source, which is the only point the request context is
   * available — elements are pulled later, by pekko-http, on another thread.
   */
  // docs:start sse-query
  sse("/stream") { () =>
    val term  = query.required[String]("q")
    val count = query.optional[Int]("count").getOrElse(2)
    org.apache.pekko.stream.scaladsl.Source((1 to count).map(n => s"$term-$n").toVector)
  }
  // docs:end sse-query

  /** Query parameters with a body. */
  postBody("/submit/{category}") { (category: String, payload: SearchResult) =>
    s"$category:${payload.term}:${query.optional[String]("mode").getOrElse("default")}"
  }

// docs:start gated
/** An endpoint whose ACL inspects the request — the same context the handler sees. */
final class GatedEndpoint extends HttpEndpoint("/gated"):

  val acl: Acl = Acl.AllowIf(context =>
    context.header("X-Api-Key").contains("let-me-in") || context.query.flag("public")
  )

  get("/")(() => "allowed")
// docs:end gated

// docs:start route-acl
/**
 * One endpoint, two audiences: reading a cart is public, purging one is not.
 *
 * `withAcl` replaces the endpoint's ACL for the routes declared inside it, so neither audience
 * needs an endpoint of its own at a second prefix.
 */
final class MixedAclEndpoint extends HttpEndpoint("/mixed"):

  val acl: Acl = Acl.AllowAll

  get("/{cartId}")((cartId: String) => s"cart:$cartId")

  withAcl(
    Acl.Authenticate(context =>
      context.header("X-Support-Id") match
        case Some(id) => AuthDecision.Allow(Principal(id))
        case None     => AuthDecision.Unauthenticated("""realm="support"""")
    )
  ) {
    delete("/{cartId}")((cartId: String) => s"purged:$cartId by ${principal.subject}")
  }
// docs:end route-acl
