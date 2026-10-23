package com.thinkmorestupidless.ankka.kotlin.reference

import com.thinkmorestupidless.ankka.javasdk.annotations.Command
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.client.ViewQuery
import com.thinkmorestupidless.ankka.javasdk.codec.Manifest
import com.thinkmorestupidless.ankka.javasdk.component.ChangeSource
import com.thinkmorestupidless.ankka.javasdk.component.EventSourcedEntity
import com.thinkmorestupidless.ankka.javasdk.component.View
import com.thinkmorestupidless.ankka.javasdk.effect.Effect
import com.thinkmorestupidless.ankka.javasdk.effect.ViewEffect
import com.thinkmorestupidless.ankka.kotlin.dsl.effects

/** A node of a tree, placed under another or at the root. */
@ComponentId("tree-node")
class TreeNode : EventSourcedEntity<TreeNode.Placement, TreeNode.TreeEvent>() {

    /** The node it is under, if any. */
    @Manifest("tree-node")
    data class Placement(val under: String?)

    @Manifest("tree-event")
    sealed interface TreeEvent {
        data class Placed(val under: String?) : TreeEvent
    }

    override fun emptyState(): Placement = Placement(null)

    override fun applyEvent(state: Placement, event: TreeEvent): Placement =
        when (event) {
            is TreeEvent.Placed -> Placement(event.under)
        }

    /** Places the node under [under]; an empty name is the root. */
    @Command("place")
    fun place(under: String): Effect<String> = effects {
        persist(TreeEvent.Placed(under.ifEmpty { null })).thenReply("placed")
    }
}

/** One row per node, walked by a declared recursive query and watched by another. */
@ComponentId("tree-rows")
class TreeRows : View<TreeNode.TreeEvent, TreeRows.TreeRow>() {

    @Manifest("tree-row")
    data class TreeRow(val key: String, val under: String?)

    override fun source(): ChangeSource<TreeNode.TreeEvent> = ChangeSource.of(TreeNode::class.java)

    override fun onChange(event: TreeNode.TreeEvent): ViewEffect<TreeRow> = effects {
        when (event) {
            is TreeNode.TreeEvent.Placed -> updateRow(TreeRow(updateContext().subject(), event.under))
        }
    }

    companion object {
        private val TABLE = ViewQuery.tableOf(TreeRows::class.java)

        /** Every row under the row `row`, to any depth, by key: the same in every language. */
        val UNDER: ViewQuery =
            ViewQuery.declare(
                "under",
                """
                WITH RECURSIVE below AS (
                  SELECT row_key, payload FROM $TABLE WHERE payload::jsonb->>'under' = :row
                  UNION
                  SELECT n.row_key, n.payload FROM $TABLE n JOIN below b ON n.payload::jsonb->>'under' = b.row_key
                )
                SELECT payload FROM below ORDER BY row_key
                """.trimIndent(),
            )

        /** Every row, by key, watchable: the same statement in every language. */
        val ALL_ROWS: ViewQuery = ViewQuery.declare("all-rows", "SELECT row_key, payload FROM $TABLE ORDER BY row_key").watched()
    }
}
