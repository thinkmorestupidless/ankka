package shoppingcart.kotlin.samples

import com.thinkmorestupidless.ankka.javasdk.testkit.KeyedViewTestKit
import com.thinkmorestupidless.ankka.kotlin.orNull
import kotlin.test.Test
import kotlin.test.assertEquals

/** The keyed view in its kit: no actor system, no database, the query answered by the test. */
class JoinedRowsTest {
    @Test
    fun `the right's change notes every row holding it, found by the view's own query`() {
        // docs:start keyed-view-test
        val kit = KeyedViewTestKit.of(JoinedRows::class.java)
        kit.change(JoinedLeft::class.java, "a", Noted("r1|b"))
        kit.change(JoinedLeft::class.java, "a", Noted("r2|b"))
        kit.change(JoinedLeft::class.java, "a", Noted("r3|c"))
        // The query is the database's to run; the test says what it answers.
        kit.answering(JoinedRows.OF_RIGHT) { values -> kit.rows().values.filter { it.holding == values["holding"] } }
        kit.change(JoinedRight::class.java, "b", Noted("anything"))
        assertEquals(listOf("left", "right"), kit.row("r1").orNull()?.notes)
        assertEquals(listOf("left", "right"), kit.row("r2").orNull()?.notes)
        assertEquals(listOf("left"), kit.row("r3").orNull()?.notes)
        // docs:end keyed-view-test
    }
}
