package nakka.testkit

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import nakka.core.Codecs
import nakka.http.*

final case class SearchResult(term: String, limit: Int, tags: List[String], verbose: Boolean)

/** Exercises query parameters and headers from inside handlers. */
// No ComponentClient parameter: these handlers call no components, and `HttpServer.of`
// takes a factory, so an endpoint need not accept one it does not use.
final class QueryEndpoint extends HttpEndpoint("/search"):

  private given JsonValueCodec[SearchResult] = Codecs.make[SearchResult]

  val acl: Acl = Acl.AllowAll

  /** Required, optional-with-default, repeated, and flag parameters. */
  get("/") { () =>
    SearchResult(
      term = query.required[String]("q"),
      limit = query.optional[Int]("limit").getOrElse(20),
      tags = query.all[String]("tag").toList,
      verbose = query.flag("verbose")
    )
  }

  /** Query parameters alongside a path parameter. */
  get("/in/{category}") { (category: String) =>
    s"$category:${query.required[String]("q")}:${query.optional[Int]("limit").getOrElse(0)}"
  }

  /** Headers. */
  get("/trace") { () =>
    request.header("X-Trace-Id").getOrElse("none")
  }

  /** The rest of the request context. */
  get("/describe") { () =>
    s"${request.method} ${request.path} params=${query.toSeq.size}"
  }

  /**
   * A streaming route reading query parameters.
   *
   * The values are read while *building* the source, which is the only point the request
   * context is available — elements are pulled later, by pekko-http, on another thread.
   */
  sse("/stream") { () =>
    val term  = query.required[String]("q")
    val count = query.optional[Int]("count").getOrElse(2)
    org.apache.pekko.stream.scaladsl.Source((1 to count).map(n => s"$term-$n").toVector)
  }

  /** Query parameters with a body. */
  postBody("/submit/{category}") { (category: String, payload: SearchResult) =>
    s"$category:${payload.term}:${query.optional[String]("mode").getOrElse("default")}"
  }

/** An endpoint whose ACL inspects the request — the same context the handler sees. */
final class GatedEndpoint extends HttpEndpoint("/gated"):

  val acl: Acl = Acl.AllowIf(context =>
    context.header("X-Api-Key").contains("let-me-in") || context.query.flag("public")
  )

  get("/") { () => "allowed" }
