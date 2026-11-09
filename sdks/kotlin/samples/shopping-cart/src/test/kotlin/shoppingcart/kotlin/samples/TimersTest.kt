package shoppingcart.kotlin.samples

import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Timers through the integration kit: scheduling, firing and a recurring cadence. */
class TimersTest {
    @Test
    fun `a timer fires its action, and a recurring one is due every period`() {
        OrderTimers.OBSERVED.clear()
        // docs:start timer-start
        // The kit's timer runtime polls every 200ms and tells `timerRuns()` what it did with each timer.
        val service = Ankka.service("orders").register(OrderEntity::class.java).register(OrderTimers::class.java)
        AnkkaTestKit.start(service).use { kit ->
            // docs:end timer-start
            val orders = kit.componentClient()
            orders.forKeyValueEntity("o-1").call(OrderEntity::place).invoke("book")
            kit.timers().schedule("expire-o-1", Duration.ofMillis(300), OrderTimers::expireOrder, "o-1")
            kit.eventually(Duration.ofSeconds(10)) {
                assertEquals("cancelled", orders.forKeyValueEntity("o-1").call(OrderEntity::status).invoke())
            }
            assertTrue(OrderTimers.OBSERVED.contains("o-1:cancelled"), OrderTimers.OBSERVED.toString())

            orders.forKeyValueEntity("o-7").call(OrderEntity::place).invoke("book")
            kit.timers().scheduleRecurring("expire-o-7", Duration.ZERO, Duration.ofSeconds(1), OrderTimers::expireOrder, "o-7")
            // docs:start due-times
            // A cadence is read from the due times the runtime ran for, not from when the handler
            // was called: a run starts up to a poll interval after its due time.
            kit.eventually(Duration.ofSeconds(10)) { assertTrue(kit.timerRuns().dueTimes("expire-o-7").size >= 3) }
            val due = kit.timerRuns().dueTimes("expire-o-7")
            assertEquals(due[0].plusSeconds(1), due[1])
            assertEquals(due[1].plusSeconds(1), due[2])
            // docs:end due-times
            kit.timers().cancel("expire-o-7")
            assertFalse(kit.timers().exists("expire-o-7"))
            assertTrue(OrderTimers.OBSERVED.contains("o-7:cancelled"), OrderTimers.OBSERVED.toString())
            assertTrue(OrderTimers.OBSERVED.contains("o-7:already cancelled"), OrderTimers.OBSERVED.toString())
        }
    }
}
