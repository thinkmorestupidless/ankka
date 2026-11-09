package shoppingcart.kotlin.samples

import com.thinkmorestupidless.ankka.javasdk.Done
import com.thinkmorestupidless.ankka.javasdk.annotations.Body
import com.thinkmorestupidless.ankka.javasdk.annotations.Command
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.annotations.Delete
import com.thinkmorestupidless.ankka.javasdk.annotations.Get
import com.thinkmorestupidless.ankka.javasdk.annotations.Post
import com.thinkmorestupidless.ankka.javasdk.annotations.Query
import com.thinkmorestupidless.ankka.javasdk.annotations.StreamBody
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest
import com.thinkmorestupidless.ankka.javasdk.component.EventSourcedEntity
import com.thinkmorestupidless.ankka.javasdk.effect.Effect
import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect
import com.thinkmorestupidless.ankka.javasdk.http.Acl
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint
import com.thinkmorestupidless.ankka.javasdk.http.Problem
import com.thinkmorestupidless.ankka.javasdk.http.ProblemException
import com.thinkmorestupidless.ankka.javasdk.http.Response
import com.thinkmorestupidless.ankka.javasdk.http.StreamedBody
import com.thinkmorestupidless.ankka.javasdk.storage.Bucket
import com.thinkmorestupidless.ankka.javasdk.storage.SignedAccess
import com.thinkmorestupidless.ankka.kotlin.dsl.effects
import com.thinkmorestupidless.ankka.kotlin.getOrNull
import com.thinkmorestupidless.ankka.kotlin.queryOrNull
import com.thinkmorestupidless.ankka.kotlin.statOrNull
import java.time.Duration

data class KeepObject(val bucket: String? = null, val name: String, val contentType: String, val text: String)

data class Described(val name: String, val contentType: String, val length: Long, val lastKept: String)

data class ListedObject(val name: String, val length: Long, val lastKept: String)

data class Listed(val objects: List<ListedObject>)

data class RecordPhoto(val name: String, val contentType: String, val text: String)

// docs:start entity
/** What a listing records about a photo: its name in the bucket, never its bytes. */
@Manifest("photo-recorded")
data class PhotoRecorded(val name: String)

@Manifest("listing")
data class ListingState(val photos: List<String> = emptyList())

@ComponentId("listing")
class Listing : EventSourcedEntity<ListingState, PhotoRecorded>() {
    override fun emptyState() = ListingState()

    override fun applyEvent(state: ListingState, event: PhotoRecorded) = state.copy(photos = state.photos + event.name)

    @Command("record-photo")
    fun recordPhoto(name: String): Effect<Done> = effects { persist(PhotoRecorded(name)).thenDone() }

    @Query("photos")
    fun photos(): ReadOnlyEffect<ListingState> = effects { reply(currentState()) }
}
// docs:end entity

// docs:start endpoint
/** An endpoint over the service's buckets: keep, read, list, describe, delete, sign and address. */
class PhotosEndpoint : HttpEndpoint() {
    override fun acl(): Acl = Acl.allowAll()

    /** The bucket `bucket` names, or the unnamed one. */
    private fun bucket(named: String? = request().queryOrNull("bucket")): Bucket =
        named?.let { storage().bucket(it) } ?: storage().defaultBucket()

    private fun name(): String = request().requiredQuery("name")

    @Post("/storage/objects")
    fun keep(@Body request: KeepObject): Done {
        bucket(request.bucket).putBytes(request.name, request.text.toByteArray(), request.contentType)
        return Done.done()
    }

    // An upload passed to the bucket as it arrives, never held whole: any size the route admits.
    @StreamBody("/storage/upload")
    fun upload(body: StreamedBody): Done {
        val length = body.length().orElseThrow { ProblemException(Problem.badRequest("an upload declares its length")) }
        bucket().put(name(), body.body(), length, body.contentType())
        return Done.done()
    }

    // A download: a Java or Kotlin route answers a body whole, so the object is read before it is sent.
    @Get("/storage/download")
    fun download(): Response {
        val name = name()
        val found = bucket().getOrNull(name) ?: throw ProblemException(Problem.notFound("no object '$name'"))
        return found.use { Response.bytes(it.body().readAllBytes(), it.contentType()) }
    }

    @Get("/storage/stat")
    fun stat(): Described {
        val name = name()
        val info = bucket().statOrNull(name) ?: throw ProblemException(Problem.notFound("no object '$name'"))
        return Described(info.name(), info.contentType(), info.length(), info.lastKept().toString())
    }

    @Get("/storage/list")
    fun list(): Listed =
        Listed(bucket().list(request().queryOrNull("prefix") ?: "").map { ListedObject(it.name(), it.length(), it.lastKept().toString()) })

    @Delete("/storage/objects")
    fun delete(): Done {
        bucket().delete(name())
        return Done.done()
    }

    @Get("/storage/signed")
    fun signed(): String {
        val access = if (request().queryOrNull("access") == "keep") SignedAccess.KEEP else SignedAccess.READ
        val seconds = request().queryOrNull("seconds")?.toLong()
        val url = if (seconds == null) bucket().signedUrl(name(), access) else bucket().signedUrl(name(), access, Duration.ofSeconds(seconds))
        return url.toString()
    }

    @Get("/storage/url")
    fun url(): String = bucket().url(name()).toString()

    // The entity keeps the name; the bucket keeps the object.
    @Post("/storage/listings/{id}/photos")
    fun record(id: String, @Body request: RecordPhoto): Done {
        storage().defaultBucket().putBytes(request.name, request.text.toByteArray(), request.contentType)
        return componentClient().forEventSourcedEntity(id).call(Listing::recordPhoto).invoke(request.name)
    }
}
// docs:end endpoint
