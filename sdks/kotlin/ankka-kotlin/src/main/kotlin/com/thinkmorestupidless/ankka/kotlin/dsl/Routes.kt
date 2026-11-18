package com.thinkmorestupidless.ankka.kotlin.dsl

import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint
import com.thinkmorestupidless.ankka.javasdk.http.Request
import com.thinkmorestupidless.ankka.javasdk.spi.RouteRegistrar
import com.thinkmorestupidless.ankka.kotlin.suspend.onHandlerThread
import java.lang.reflect.Type
import kotlin.reflect.KType
import kotlin.reflect.javaType
import kotlin.reflect.typeOf

// Routes declared in code, the programmatic form beside the annotations:
//
//   class CartEndpoint : HttpEndpoint(), RouteRegistrar {
//     override fun acl() = Acl.allowAll()
//     override fun routes() = routes {
//       get("/carts/{id}") { componentClient().forEventSourcedEntity(path("id")).call(...).invokeSuspending() }
//       post<LineItem>("/carts/{id}/items") { item -> ... }
//     }
//   }
//
// Each handler is a suspending block run on the request's virtual thread. Its answer is encoded as an
// annotated route's is. The routes are read once, when the service starts.

/** The routes an endpoint declares in code: the answer to [RouteRegistrar.routes]. */
@Suppress("UnusedReceiverParameter") // only an endpoint declares routes
fun HttpEndpoint.routes(build: RouteScope.() -> Unit): List<RouteRegistrar.Declaration> =
    RouteScope().apply(build).declarations.toList()

/** One request to a route declared in code. */
class RouteCall(
    /** The request, as an annotated route reads it. */
    val request: Request,
    private val path: Map<String, String>,
) {
    /** The path parameter [name], as it stood in the request's path. */
    fun path(name: String): String =
        path[name] ?: throw IllegalArgumentException("the route's template has no parameter '$name'")
}

/**
 * What a scope of routes says of them in the service's OpenAPI document (feature 067), as Scala's
 * `described` says it: given to [RouteScope.described], and carried by every route declared inside.
 * A route declared in code has no answer type of its own, so it is incomplete until a scope around
 * it says what it [answers].
 */
class RouteDescription @PublishedApi internal constructor() {
    private val summaries = mutableListOf<String>()
    private val bodies = mutableListOf<RouteRegistrar.Shape>()
    private val answers = mutableListOf<RouteRegistrar.Answer>()
    private val refusals = mutableListOf<RouteRegistrar.Refusal>()
    private val refusalShapes = mutableListOf<RouteRegistrar.Shape>()
    private val queries = mutableListOf<RouteRegistrar.Query>()
    private val tags = mutableListOf<String>()

    /** One line saying what the route is for. */
    fun summary(text: String) {
        summaries += text
    }

    /** The body's shape, as the codec writes a [T]. */
    inline fun <reified T : Any> body() = bodyOf(javaTypeOf(typeOf<T>()))

    /** The body's shape as JSON Schema. */
    fun body(schema: String) {
        bodies += RouteRegistrar.Shape(null, schema)
    }

    /** An answer by status, its shape as the codec writes a [T]. */
    inline fun <reified T : Any> answers(status: Int = 200) = answering(status, javaTypeOf(typeOf<T>()))

    /** An answer by status with no body. */
    @JvmName("answersNothing") // the reified form erases to the same signature
    fun answers(status: Int) {
        answers += RouteRegistrar.Answer(status, null)
    }

    /** An answer by status, its shape as JSON Schema. */
    fun answers(status: Int, schema: String) {
        answers += RouteRegistrar.Answer(status, RouteRegistrar.Shape(null, schema))
    }

    /** A refusal, with the endpoint's refusal shape, else the platform's. */
    fun refuses(status: Int, reason: String) {
        refusals += RouteRegistrar.Refusal(status, reason, null)
    }

    /** The shape of every refusal that names none, as the codec writes a [T]. */
    inline fun <reified T : Any> refusalShape() = refusalShapeOf(javaTypeOf(typeOf<T>()))

    /** A query parameter the handler reads, as a [T]: `String`, `Int`, `Long`, `Double`, `Boolean`, `UUID`. */
    inline fun <reified T : Any> query(name: String, required: Boolean = false) =
        querying(name, T::class.javaObjectType, required)

    /** Route tags, by which a document is written for some of a service's routes. */
    fun tag(vararg names: String) {
        tags += names
    }

    @PublishedApi
    internal fun bodyOf(type: Type) {
        bodies += RouteRegistrar.Shape(type, null)
    }

    @PublishedApi
    internal fun answering(status: Int, type: Type) {
        answers += RouteRegistrar.Answer(status, RouteRegistrar.Shape(type, null))
    }

    @PublishedApi
    internal fun refusalShapeOf(type: Type) {
        refusalShapes += RouteRegistrar.Shape(type, null)
    }

    @PublishedApi
    internal fun querying(name: String, type: Class<*>, required: Boolean) {
        queries += RouteRegistrar.Query(name, type, required)
    }

    // `javaType` is still marked experimental; opted into here, so no caller of the inline forms must.
    @PublishedApi
    @OptIn(ExperimentalStdlibApi::class)
    internal fun javaTypeOf(type: KType): Type = type.javaType

    @PublishedApi
    internal fun build(): RouteRegistrar.Description =
        RouteRegistrar.Description(summaries, bodies, answers, refusals, refusalShapes, queries, tags)
}

class RouteScope internal constructor() {
    @PublishedApi
    internal val declarations = mutableListOf<RouteRegistrar.Declaration>()

    // The `described` scopes around the routes being declared, outermost first.
    private val scopes = ArrayDeque<RouteRegistrar.Description>()

    /**
     * Routes that carry [describe]'s description into the service's OpenAPI document; scopes nest,
     * outermost first. A summary, a body or an answer is for one route, and is refused when the
     * scope holds more.
     *
     * ```kotlin
     * described({ summary("A cart"); answers<ShoppingCart>(); refuses(404, "not found") }) {
     *     get("/carts/{cartId}") { ... }
     * }
     * ```
     */
    fun described(describe: RouteDescription.() -> Unit, routes: RouteScope.() -> Unit) {
        scopes.addLast(RouteDescription().apply(describe).build())
        try {
            routes()
        } finally {
            scopes.removeLast()
        }
    }

    fun get(template: String, handler: suspend RouteCall.() -> Any?) = without("GET", template, handler)

    fun delete(template: String, handler: suspend RouteCall.() -> Any?) = without("DELETE", template, handler)

    /** A POST that reads no body. */
    fun post(template: String, handler: suspend RouteCall.() -> Any?) = without("POST", template, handler)

    /** A POST whose body is read as [B], type arguments and all (`post<List<String>>`). */
    inline fun <reified B : Any> post(template: String, noinline handler: suspend RouteCall.(B) -> Any?) =
        with("POST", template, bodyType(typeOf<B>()), handler)

    /** A PUT whose body is read as [B]. */
    inline fun <reified B : Any> put(template: String, noinline handler: suspend RouteCall.(B) -> Any?) =
        with("PUT", template, bodyType(typeOf<B>()), handler)

    /** A PATCH whose body is read as [B]. */
    inline fun <reified B : Any> patch(template: String, noinline handler: suspend RouteCall.(B) -> Any?) =
        with("PATCH", template, bodyType(typeOf<B>()), handler)

    // `javaType` is still marked experimental; opted into here, so no caller of the inline forms must.
    @PublishedApi
    @OptIn(ExperimentalStdlibApi::class)
    internal fun bodyType(type: KType): Type = type.javaType

    private fun without(verb: String, template: String, handler: suspend RouteCall.() -> Any?) {
        declarations +=
            RouteRegistrar.Declaration(verb, template, null, { request, path, _ ->
                onHandlerThread { RouteCall(request, path).handler() }
            }, scopes.toList())
    }

    @PublishedApi
    internal fun <B : Any> with(
        verb: String,
        template: String,
        body: Type,
        handler: suspend RouteCall.(B) -> Any?,
    ) {
        declarations +=
            RouteRegistrar.Declaration(verb, template, body, { request, path, read ->
                @Suppress("UNCHECKED_CAST")
                onHandlerThread { RouteCall(request, path).handler(read as B) }
            }, scopes.toList())
    }
}
