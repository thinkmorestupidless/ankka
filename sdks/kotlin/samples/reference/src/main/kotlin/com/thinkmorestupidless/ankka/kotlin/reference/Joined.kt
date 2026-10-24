package com.thinkmorestupidless.ankka.kotlin.reference

import com.thinkmorestupidless.ankka.javasdk.annotations.Command
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.annotations.Get
import com.thinkmorestupidless.ankka.javasdk.annotations.Post
import com.thinkmorestupidless.ankka.javasdk.annotations.Source
import com.thinkmorestupidless.ankka.javasdk.client.ViewQuery
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest
import com.thinkmorestupidless.ankka.javasdk.component.EventSourcedEntity
import com.thinkmorestupidless.ankka.javasdk.component.KeyedChange
import com.thinkmorestupidless.ankka.javasdk.component.KeyedView
import com.thinkmorestupidless.ankka.javasdk.effect.Effect
import com.thinkmorestupidless.ankka.javasdk.effect.KeyedViewEffect
import com.thinkmorestupidless.ankka.javasdk.http.Acl
import com.thinkmorestupidless.ankka.javasdk.http.HttpEndpoint
import com.thinkmorestupidless.ankka.javasdk.http.Problem
import com.thinkmorestupidless.ankka.javasdk.http.ProblemException
import com.thinkmorestupidless.ankka.kotlin.dsl.effects
import com.thinkmorestupidless.ankka.kotlin.getOrNull

/** What either side of the joined view records. */
@Manifest("noted")
data class Noted(val text: String)

/** The left of the keyed view's two sources: it counts what it noted. */
@ComponentId("joined-left")
class JoinedLeft : EventSourcedEntity<Int, Noted>() {

    override fun emptyState(): Int = 0

    override fun applyEvent(state: Int, event: Noted): Int = state + 1

    @Command("record")
    fun record(text: String): Effect<String> = effects { persist(Noted(text)).thenReply("recorded") }
}

/** The right of the keyed view's two sources: it counts what it noted. */
@ComponentId("joined-right")
class JoinedRight : EventSourcedEntity<Int, Noted>() {

    override fun emptyState(): Int = 0

    override fun applyEvent(state: Int, event: Noted): Int = state + 1

    @Command("record")
    fun record(text: String): Effect<String> = effects { persist(Noted(text)).thenReply("recorded") }
}

/** A keyed view of two sources: rows the left names, which the right finds by a query. */
// docs:start keyed-view
@ComponentId("joined-rows")
class JoinedRows : KeyedView<JoinedRows.JoinedRow>() {

    /** A row the left writes under the key it names, holding a right entity's id. */
    @Manifest("joined-row")
    data class JoinedRow(val key: String, val holding: String, val notes: List<String>) {
        fun noting(who: String): JoinedRow = copy(notes = notes + who)
    }

    /** The left names a row `key|holding`, and writes it from what it held, noting itself. */
    @Source(JoinedLeft::class)
    fun onLeft(event: Noted, change: KeyedChange<JoinedRow>): KeyedViewEffect<JoinedRow> {
        val (key, holding) = event.text.split("|", limit = 2)
        val held = change.rows().getOrNull(key)?.notes ?: emptyList()
        return effects { updateRow(key, JoinedRow(key, holding, held).noting("left")) }
    }

    /** The right finds every row holding it by asking the view's own query, and notes itself. */
    @Source(JoinedRight::class)
    fun onRight(event: Noted, change: KeyedChange<JoinedRow>): KeyedViewEffect<JoinedRow> {
        val theirs = change.rows().ask(OF_RIGHT, mapOf("holding" to change.subject()))
        return effects { updateRows(theirs.associate { it.key to it.noting("right") }) }
    }

    companion object {
        /** The rows holding one right entity, by key: the same statement in every language. */
        val OF_RIGHT: ViewQuery =
            ViewQuery.declare(
                "of-right",
                "SELECT payload FROM ${ViewQuery.tableOf(JoinedRows::class.java)} WHERE payload::jsonb->>'holding' = :holding ORDER BY row_key",
            )
    }
}
// docs:end keyed-view

/** Records on either side of the keyed view, and reads its rows. */
class JoinedEndpoint : HttpEndpoint() {

    override fun acl(): Acl = Acl.allowAll()

    // The left entity is the row's own key: one left per row.
    @Post("/joined/left/{key}/{holding}")
    fun left(key: String, holding: String): String =
        componentClient().forEventSourcedEntity(key).call(JoinedLeft::record).invoke("$key|$holding")

    @Post("/joined/right/{rightId}")
    fun right(rightId: String): String = componentClient().forEventSourcedEntity(rightId).call(JoinedRight::record).invoke("")

    @Get("/joined/rows/{key}")
    fun row(key: String): JoinedRows.JoinedRow =
        viewClient().forKeyedView(JoinedRows::class.java).getOrNull(key) ?: throw ProblemException(Problem.notFound("no row '$key'"))
}
