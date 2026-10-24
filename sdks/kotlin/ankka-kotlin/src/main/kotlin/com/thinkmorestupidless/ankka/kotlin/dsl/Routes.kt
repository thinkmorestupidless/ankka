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

class RouteScope internal constructor() {
    @PublishedApi
    internal val declarations = mutableListOf<RouteRegistrar.Declaration>()

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
            RouteRegistrar.Declaration(verb, template, null) { request, path, _ ->
                onHandlerThread { RouteCall(request, path).handler() }
            }
    }

    @PublishedApi
    internal fun <B : Any> with(
        verb: String,
        template: String,
        body: Type,
        handler: suspend RouteCall.(B) -> Any?,
    ) {
        declarations +=
            RouteRegistrar.Declaration(verb, template, body) { request, path, read ->
                @Suppress("UNCHECKED_CAST")
                onHandlerThread { RouteCall(request, path).handler(read as B) }
            }
    }
}
