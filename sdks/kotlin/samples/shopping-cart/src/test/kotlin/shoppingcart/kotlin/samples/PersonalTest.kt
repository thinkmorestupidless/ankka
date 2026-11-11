package shoppingcart.kotlin.samples

import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.kotlin.testkit.ankkaTestKit
import com.thinkmorestupidless.ankka.kotlin.testkit.eventually
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/** A personal field through its life: written, read back, erased. */
class PersonalTest {
    @Test
    fun `an erased player's email reads as erased, and its value is in no table`() =
        ankkaTestKit(Ankka.service("players").register(PlayerEntity::class.java)) {
            val player = componentClient().forEventSourcedEntity("8c1f")
            player.call(PlayerEntity::register).invoke("ada@example.com")
            assertEquals("ada@example.com", player.call(PlayerEntity::email).invoke())

            // docs:start erase
            erase("player/8c1f") // destroys the key now and sends the notice
            restartService() // read from the database, not from memory
            assertEquals("erased", componentClient().forEventSourcedEntity("8c1f").call(PlayerEntity::email).invoke())

            assertNoPersonalValue("ada@example.com") // fails naming the table and column holding it
            // docs:end erase
        }

    @Test
    fun `the erasure handler erases the player's objects and keeps everyone else's`() =
        ankkaTestKit(players()) {
            val bucket = storage().defaultBucket()
            bucket.putBytes("subjects/player%2F8c1f/passport.png", byteArrayOf(1), "image/png")
            bucket.putBytes("subjects/player%2F77a0/passport.png", byteArrayOf(2), "image/png")
            erase("player/8c1f")
            runBlocking {
                eventually {
                    assertEquals(listOf("subjects/player%2F77a0/passport.png"), bucket.list("subjects/").map { it.name() })
                }
            }
        }

    @Test
    fun `an ask from a service that reaches no control plane is refused as unavailable`() =
        ankkaTestKit(Ankka.service("players").withEndpoint(ForgetEndpoint::class.java)) {
            val answer = send("POST", "/forget/8c1f")
            assertEquals(503, answer.status, answer.body)
            assertContains(answer.body, "control plane")
        }
}
