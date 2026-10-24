package com.thinkmorestupidless.ankka.kotlin.steps

import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.Done
import com.thinkmorestupidless.ankka.javasdk.ServiceBuilder
import com.thinkmorestupidless.ankka.javasdk.annotations.Command
import com.thinkmorestupidless.ankka.javasdk.codec.EncodingException
import com.thinkmorestupidless.ankka.javasdk.codec.RecordCodec
import com.thinkmorestupidless.ankka.javasdk.problems.ServiceProblems
import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit
import com.thinkmorestupidless.ankka.kotlin.testkit.eventSourcedTestKit
import com.thinkmorestupidless.ankka.kotlin.testkit.platformThreads
import com.thinkmorestupidless.ankka.javasdk.testkit.EventSourcedTestKit
import com.thinkmorestupidless.ankka.kotlin.KotlinFeaturesTest
import io.cucumber.java.After
import io.cucumber.java.AfterAll
import io.cucumber.java.Before
import io.cucumber.java.Scenario
import java.util.concurrent.ConcurrentLinkedQueue
import io.cucumber.java.en.Given
import io.cucumber.java.en.Then
import io.cucumber.java.en.When
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The steps of `features/java-kotlin` for a service written in Kotlin. A Java row is skipped here:
 * `JavaKotlinFeatures` runs it.
 */
class KotlinSteps {

    private lateinit var scenario: String

    @Before
    fun named(scenario: Scenario) {
        this.scenario = scenario.name
    }

    private fun kotlin(language: String) {
        assumeTrue(language == "Kotlin", "the $language row is run by JavaKotlinFeatures")
        kotlinRows += scenario
    }

    private var kit: AnkkaTestKit? = null

    private fun running(): AnkkaTestKit = kit ?: fail("no service is running")

    @After
    fun stop() {
        kit?.close()
        kit = null
    }

    private fun functionNamed(cls: KClass<*>, handler: String): String? =
        cls.java.declaredMethods.find { it.getAnnotation(Command::class.java)?.value == handler }?.name

    // ── kotlin.feature: Kotlin's own form against the Java SDK's ─────────────────

    private lateinit var kotlinCart: EventSourcedTestKit<CartState, CartEvent>
    private lateinit var javaCart: EventSourcedTestKit<JavaCart.State, JavaCart.Event>
    private var answers = Pair<Any?, Any?>(null, null)

    @Given("a service {string} written in {string} whose entity {string} is declared in Kotlin's own form")
    fun kotlinsOwnForm(service: String, language: String, entity: String) {
        kotlin(language)
        assertEquals("cart", entity)
        kotlinCart = eventSourcedTestKit(KotlinCart::class, "c1")
    }

    @Given("the same entity {string} declared in {string}")
    fun sameEntityInJava(entity: String, language: String) {
        assertEquals("Java", language)
        javaCart = eventSourcedTestKit(JavaCart::class, "c1")
    }

    @When("a caller sends the command {string} with the item {string} to the cart {string} of each")
    fun sendsToEach(command: String, item: String, cartId: String) {
        assertEquals("add-item", command)
        assertEquals("addItem", functionNamed(KotlinCart::class, command))
        assertEquals("addItem", functionNamed(JavaCart::class, command))
        answers = kotlinCart.call(KotlinCart::addItem, Item(item)).reply() to javaCart.call(JavaCart::addItem, JavaCart.Item(item)).reply()
    }

    @Then("each cart {string} has recorded the same event")
    fun sameEvent(cartId: String) {
        val kotlinEvents = kotlinCart.events().map { String(RecordCodec.forType(CartEvent::class.java).toBytes(it)) }
        val javaEvents = javaCart.events().map { String(RecordCodec.forType(JavaCart.Event::class.java).toBytes(it)) }
        assertEquals(1, kotlinEvents.size)
        assertEquals(javaEvents, kotlinEvents)
    }

    @Then("each caller is answered the same")
    fun answeredTheSame() = assertEquals(answers.second, answers.first)

    // ── kotlin.feature: a suspending step ──────────────────────────────────────────

    private var elapsedMillis = 0L
    private var threadsAdded = 0
    private var reserved: Int? = null

    @Given("a service {string} written in {string} whose workflow step {string} is a suspending function that calls the entity {string}")
    fun suspendingStep(service: String, language: String, step: String, entity: String) {
        kotlin(language)
        assertEquals("reserve", step)
        kit = AnkkaTestKit.start(Ankka.service(service).register(SlowCart::class.java).register(Reserving::class.java))
    }

    @When("the step calls the cart {string}")
    fun stepCalls(cartId: String) {
        val kit = running()
        // Fifty workflows at once, each step waiting a second on its own cart, on two carrier threads:
        // a step that held its thread while it waited would make them take twenty-five seconds.
        val ids = listOf(cartId) + (1 until 50).map { "$cartId-$it" }
        val before = kit.platformThreads
        val started = System.nanoTime()
        ids.forEach { kit.componentClient().forWorkflow("w-$it").call(Reserving::start).invoke(it) }
        Thread.sleep(500)
        threadsAdded = kit.platformThreads - before
        kit.eventually(java.time.Duration.ofSeconds(60)) {
            ids.forEach { id -> assertTrue(kit.componentClient().forWorkflow("w-$id").call(Reserving::get).invoke().reserved != null, id) }
        }
        elapsedMillis = (System.nanoTime() - started) / 1_000_000
        reserved = kit.componentClient().forWorkflow("w-$cartId").call(Reserving::get).invoke().reserved
    }

    @Then("the step is answered with the value the cart replied")
    fun stepAnswered() = assertEquals(1, reserved)

    @Then("no thread of {string} is held while the step waits")
    fun noThreadHeld(service: String) {
        assertTrue(elapsedMillis < 10_000, "fifty steps waiting a second each took ${elapsedMillis}ms")
        assertTrue(threadsAdded < 10, "$threadsAdded platform threads appeared while fifty steps waited")
    }

    // ── kotlin.feature: null safety, as the compiler sees it ───────────────────────

    private var compiled: Compiled? = null

    @Given("a service written in {string}")
    fun aService(language: String) = kotlin(language)

    @When("the developer reads a value the Java SDK may give as nothing without saying what happens when it is null")
    fun readsNullable() {
        val source = { body: String ->
            """
            import com.thinkmorestupidless.ankka.javasdk.http.Request
            import com.thinkmorestupidless.ankka.kotlin.headerOrNull
            fun cartHeader(request: Request): Int = $body
            """
        }
        // The same read, saying what happens when there is none, builds: the class path is sound.
        val handled = compile(source("""request.headerOrNull("x-cart")?.length ?: 0"""))
        assertTrue(handled.succeeded, handled.messages)
        compiled = compile(source("""request.headerOrNull("x-cart").length"""))
    }

    @When("the developer gives null where the Java SDK takes a value")
    fun givesNull() {
        val source = { id: String ->
            """
            import com.thinkmorestupidless.ankka.javasdk.client.ComponentClient
            fun cart(client: ComponentClient) = client.forEventSourcedEntity($id)
            """
        }
        val given = compile(source("\"c1\""))
        assertTrue(given.succeeded, given.messages)
        compiled = compile(source("null"))
    }

    @Then("the service does not build")
    fun doesNotBuild() {
        val result = compiled ?: fail("nothing was compiled")
        assertFalse(result.succeeded, "it built")
        assertTrue(result.messages.contains("error:"), result.messages)
    }

    @Then("the developer is told that the value may be null")
    fun toldNullable() {
        val messages = compiled!!.messages
        assertTrue(messages.contains("nullable receiver of type 'String?'") || messages.contains("String?"), messages)
    }

    // ── kotlin.feature: a data class is a state as a Java record is ────────────────

    private var cartItems = emptyList<String>()

    @Given("a service {string} written in {string} whose entity {string} declares its state and events as data classes")
    fun dataClassService(service: String, language: String, entity: String) {
        kotlin(language)
        kit = AnkkaTestKit.start(Ankka.service(service).register(KotlinCart::class.java))
    }

    @Given("the cart {string} has recorded {int} items")
    fun hasRecorded(cartId: String, count: Int) {
        val cart = running().componentClient().forEventSourcedEntity(cartId)
        cartItems = (1..count).map { "item-$it" }
        cartItems.forEach { cart.call(KotlinCart::addItem).invoke(Item(it)) }
    }

    @When("a service {string} written in {string} is started on the same database")
    fun startedOnSameDatabase(service: String, language: String) {
        assertEquals("Java", language)
        running().restartService(Ankka.service(service).register(JavaCart::class.java))
    }

    private var answeredState: JavaCart.State? = null

    @When("a caller sends the command {string} to the cart {string}")
    fun sendsQuery(command: String, cartId: String) {
        answeredState = running().componentClient().forEventSourcedEntity(cartId).call("cart", command, Void::class.java, JavaCart.State::class.java).invoke()
    }

    @Then("the caller is answered with the same {int} items")
    fun answeredSameItems(count: Int) {
        val items = answeredState?.items()?.map { it.name() } ?: fail("not answered")
        assertEquals(count, items.size)
        assertEquals(cartItems, items)
    }

    // ── kotlin.feature: a field added after the state was recorded ────────────────

    private var recordedBefore = ByteArray(0)
    private var addedField: Pair<String, Class<*>>? = null
    private var recovered: Result<Any> = Result.failure(IllegalStateException("not recovered"))

    @Given("a service {string} written in {string} whose state has the field {string} with a default of {int}")
    fun fieldWithDefault(service: String, language: String, field: String, default: Int) {
        kotlin(language)
        assertEquals(0, WithDefault("tea").discount)
        addedField = field to WithDefault::class.java
    }

    @Given("a service {string} written in {string} whose state has the field {string} with no default")
    fun fieldWithoutDefault(service: String, language: String, field: String) {
        kotlin(language)
        addedField = field to WithoutDefault::class.java
    }

    @Given("the cart {string} recorded its state before the field {string} existed")
    fun recordedBeforeField(cartId: String, field: String) {
        recordedBefore = RecordCodec.forType(Recorded::class.java).toBytes(Recorded("tea"))
        assertFalse(String(recordedBefore).contains(field))
    }

    @When("the cart {string} is recovered")
    fun isRecovered(cartId: String) {
        val type = addedField?.second ?: fail("no field was added")
        recovered = runCatching { RecordCodec.forType(type).fromBytes(recordedBefore) }
    }

    @Then("the cart {string} is recovered with the field {string} at {int}")
    fun recoveredWith(cartId: String, field: String, value: Int) {
        val state = recovered.getOrElse { fail("not recovered: $it") }
        assertEquals(WithDefault("tea", value), state)
        assertEquals(value, state.javaClass.getMethod("get" + field.replaceFirstChar { it.uppercase() }).invoke(state))
    }

    @Then("the recovery fails, naming the field {string}")
    fun recoveryFails(field: String) {
        val failure = recovered.exceptionOrNull() ?: fail("recovered: ${recovered.getOrNull()}")
        assertTrue(failure is EncodingException, failure.toString())
        assertTrue(failure.message!!.contains(field), failure.message)
    }

    // ── embedded.feature: a handler's name declared apart from its function ───────

    private fun cartCommand(cartId: String, handler: String, item: Item) =
        running().componentClient().forEventSourcedEntity(cartId).call("cart", handler, Item::class.java, Done::class.java).invoke(item)

    @Given("a service {string} written in {string} whose entity {string} has the handler {string} declared on the function {string}")
    fun handlerOnFunction(service: String, language: String, entity: String, handler: String, function: String) {
        kotlin(language)
        assertEquals(function, functionNamed(KotlinCart::class, handler))
        kit = AnkkaTestKit.start(Ankka.service(service).register(KotlinCart::class.java))
    }

    @When("the developer renames the function to {string} and runs {string} again")
    fun renames(function: String, service: String) {
        cartCommand("c1", "add-item", Item("pen"))
        assertEquals(function, functionNamed(RenamedCart::class, "add-item"))
        running().restartService(Ankka.service(service).register(RenamedCart::class.java))
    }

    @Then("a caller's command {string} to the cart {string} is still answered")
    fun stillAnswered(handler: String, cartId: String) = assertEquals(Done.DONE, cartCommand(cartId, handler, Item("pen")))

    @Then("the cart {string} is recovered from what it recorded before the rename")
    fun recoveredAfterRename(cartId: String) {
        val state = running().componentClient().forEventSourcedEntity(cartId).call(RenamedCart::getCart).invoke()
        assertEquals(listOf(Item("pen"), Item("pen")), state.items)
    }

    // ── embedded.feature: a service that fails at start ───────────────────────────

    private var builder: ServiceBuilder? = null
    private var refusal: String? = null
    private var failedFunction: String? = null
    private var requestsBefore = 0

    @Given("a service {string} written in {string} with two handlers of the entity {string} declared with the name {string}")
    fun twoHandlersOneName(service: String, language: String, entity: String, name: String) {
        kotlin(language)
        builder = Ankka.service(service).register(DuplicateNames::class.java)
    }

    @Given("a service {string} written in {string} whose entity {string} has a function shaped like a handler that carries no name")
    fun unnamedFunction(service: String, language: String, entity: String) {
        kotlin(language)
        builder = Ankka.service(service).register(Unannotated::class.java)
        failedFunction = "Unannotated.get"
    }

    @Given("a service {string} written in {string} whose entity {string} has a query whose declared outcome could record an event")
    fun queryThatPersists(service: String, language: String, entity: String) {
        kotlin(language)
        builder = Ankka.service(service).register(QueryThatPersists::class.java)
        failedFunction = "QueryThatPersists.get"
    }

    @Given("a service {string} written in {string} whose endpoint calls the entity {string}, which {string} does not register")
    fun unregisteredComponent(service: String, language: String, entity: String, same: String) {
        kotlin(language)
        builder = Ankka.service(service).withEndpoint(OrphanEndpoint::class.java)
    }

    @When("the developer starts {string}")
    fun starts(service: String) {
        requestsBefore = OrphanEndpoint.requests.get()
        refusal =
            try {
                (builder ?: fail("no service")).withHttp("127.0.0.1", 0).start().close()
                null
            } catch (refused: ServiceProblems) {
                refused.message
            }
    }

    @Then("{string} does not start, naming {string}")
    fun doesNotStartNaming(service: String, named: String) {
        val message = refusal ?: fail("the service started")
        assertTrue(message.contains(named), message)
    }

    @Then("{string} does not start, naming the function")
    fun doesNotStartNamingFunction(service: String) {
        val message = refusal ?: fail("the service started")
        assertTrue(message.contains(failedFunction ?: fail("no function")), message)
    }

    @Then("no caller's request is needed to find out")
    fun noRequestNeeded() {
        assertTrue(refusal != null, "the service started")
        assertEquals(requestsBefore, OrphanEndpoint.requests.get())
    }

    // ── embedded.feature: a field that may hold nothing ───────────────────────────

    private var written: String? = null

    @Given("a state declared in {string} with a field {string} that may hold nothing")
    fun mayHoldNothing(language: String, field: String) {
        kotlin(language)
        assertEquals("discount", field)
    }

    // Kotlin has one way to hold nothing, null; "nothing" is the Java row's Optional.empty().
    @When("the state is written with the field holding null")
    fun holdingNull() {
        written = String(RecordCodec.forType(Priced::class.java).toBytes(Priced("tea", null)))
    }

    @When("the state is written with the field holding nothing")
    fun holdingNothing(): Unit = fail("only a Java row holds nothing other than null, and it is skipped here")

    // The encoding's absent value is the Scala SDK's for `None`, as the Java row asserts.
    @Then("the field is absent from what is written")
    fun absent() = assertEquals("""{"name":"tea","discount":null}""", written)

    @Then("the writing is refused, naming the field {string}")
    fun refused(field: String): Unit = fail("only a Java row is refused, and it is skipped here")
}

/** The scenario of every Kotlin row that ran: a row skipped or filtered out looks like a Java row. */
private val kotlinRows = ConcurrentLinkedQueue<String>()

/** The Kotlin rows: one per scenario of kotlin.feature but its outline's two, and embedded.feature's six. */
private const val KOTLIN_ROWS = 13

// Top-level, so it is a plain static method: cucumber refuses a companion object's.
@AfterAll
fun everyKotlinRowRan() {
    val named = KotlinFeaturesTest.SCENARIOS.removePrefix("^(").removeSuffix(")$").split("|").toSet()
    assertEquals(named, kotlinRows.toSet(), "the scenarios whose Kotlin row ran")
    assertEquals(KOTLIN_ROWS, kotlinRows.size, "Kotlin rows run: $kotlinRows")
}
