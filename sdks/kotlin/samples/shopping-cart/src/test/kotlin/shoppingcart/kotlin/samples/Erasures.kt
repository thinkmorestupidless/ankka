package shoppingcart.kotlin.samples

import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.ServiceBuilder
import com.thinkmorestupidless.ankka.javasdk.annotations.Post
import com.thinkmorestupidless.ankka.javasdk.erasure.ErasureAsk
import com.thinkmorestupidless.ankka.javasdk.erasure.ErasureReport
import com.thinkmorestupidless.ankka.javasdk.http.Acl
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint
import com.thinkmorestupidless.ankka.kotlin.subject

// docs:start erasure-handler
/** The players service, whose part of an erasure is the subject's objects in its bucket. */
fun players(): ServiceBuilder =
    Ankka.service("players")
        .register(PlayerEntity::class.java)
        .withErasureHandler { ctx, subject ->
            ErasureReport.of(ctx.storage().defaultBucket().erase(subject))
        }
// docs:end erasure-handler

/** Asks for an erasure as the service, when its domain decides a player is to be forgotten. */
class ForgetEndpoint : HttpEndpoint() {
    override fun acl(): Acl = Acl.allowAll()

    @Post("/forget/{id}")
    fun forget(id: String): String {
        // docs:start ask
        val asked = erasures().ask(
            subject("player/$id"),
            ErasureAsk.defaults().withReason("aml-retention").withCorrelation("closure-4411"),
        )
        // docs:end ask
        return "${asked.id()} ${asked.state()}"
    }
}
