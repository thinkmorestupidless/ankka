package shoppingcart

import com.thinkmorestupidless.ankka.agent.{AgentRuntime, Json, TestModelProvider}
import com.thinkmorestupidless.ankka.agent.autonomous.*
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.http.HttpServer
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.AnkkaTestKit
import shoppingcart.api.QuestionsEndpoint
import shoppingcart.application.*
import shoppingcart.domain.LineItem

import java.net.URI
import java.net.http.{HttpClient, HttpRequest as JdkRequest, HttpResponse as JdkResponse}
import scala.concurrent.duration.DurationInt

/** The cart answerer, with a scripted model, over real HTTP and a real journal. */
class CartAnswererSuite extends munit.FunSuite:

  override val munitTimeout = 3.minutes

  private var kit: AnkkaTestKit  = null
  private var server: HttpServer = null
  private val http               = HttpClient.newHttpClient()
  // docs:start script
  private val model = TestModelProvider()
  // docs:end script

  override def beforeAll(): Unit =
    server = HttpServer.at("127.0.0.1", 0)(clients => QuestionsEndpoint(clients.componentClient))
    kit = AnkkaTestKit.start(
      Seq(ShoppingCartEntity.descriptor, CartAnswerer.descriptor) ++ AgentRuntime.descriptors,
      Seq(AgentRuntime.withDefaultModel(model), ProjectionRuntime(), server)
    )

  override def afterAll(): Unit = if kit != null then kit.stop()

  override def beforeEach(context: BeforeEach): Unit = model.reset()

  private def url(path: String) = s"http://127.0.0.1:${server.boundPort.get}$path"

  private def post(path: String, body: String) =
    http.send(
      JdkRequest
        .newBuilder(URI.create(url(path)))
        .POST(JdkRequest.BodyPublishers.ofString(body))
        .header("Content-Type", "text/plain")
        .build(),
      JdkResponse.BodyHandlers.ofString()
    )

  private def get(path: String) =
    http.send(
      JdkRequest.newBuilder(URI.create(url(path))).GET().build(),
      JdkResponse.BodyHandlers.ofString()
    )

  test("a question is answered by looking the cart up, and the answer is read later") {
    kit.componentClient
      .forEventSourcedEntity(EntityId("c-1"))
      .call(ShoppingCartEntity.addItem)
      .invoke(LineItem("p-1", "Pen", 3)): Unit

    // docs:start test
    model
      .expectToolCall("cart_total", Json.obj("cartId" -> Json.str("c-1")))
      .expectCompleteTask(Answer("Cart c-1 holds 3 items.", List("cart_total")))

    val asked = post("/questions/ask", "How many items are in cart c-1?")
    assertEquals(asked.statusCode, 200, asked.body)
    val taskId = Json.parse(asked.body).toOption.flatMap(_("taskId")).flatMap(_.asString).get

    val done = kit.awaitTask(taskId, CartTasks.answer)
    assertEquals(done.result, Some(Answer("Cart c-1 holds 3 items.", List("cart_total"))))
    // docs:end test

    val read = get(s"/questions/$taskId")
    assertEquals(read.statusCode, 200)
    assert(read.body.contains("\"status\":\"completed\""), read.body)
    assert(read.body.contains("holds 3 items"), read.body)
  }

  test("an answer that cites nothing is sent back, and the second attempt stands") {
    model
      .expectCompleteTask(Answer("It is empty.", Nil))
      .expectToolCall("cart_contents", Json.obj("cartId" -> Json.str("c-2")))
      .expectCompleteTask(Answer("Cart c-2 is empty.", List("cart_contents")))
    val taskId = kit.componentClient
      .forAutonomousAgent(CartAnswerer)
      .runSingleTask(CartTasks.answer, "What is in c-2?")
    val done = kit.awaitTask(taskId, CartTasks.answer)
    assertEquals(done.result.map(_.sources), Some(List("cart_contents")))
    assertEquals(done.record.iterations, 3)
  }

  test("an instance's notifications arrive over server-sent events, each a JSON string") {
    val instance = s"watched-${java.util.UUID.randomUUID()}"
    val lines    = java.util.concurrent.LinkedBlockingQueue[String]()
    val response = http.sendAsync(
      JdkRequest
        .newBuilder(URI.create(url(s"/questions/answerer/$instance/notifications")))
        .GET()
        .build(),
      JdkResponse.BodyHandlers.ofLines()
    )
    Thread
      .ofVirtual()
      .start(() =>
        try
          response
            .get()
            .body()
            .filter(_.startsWith("data:"))
            .forEach(l => lines.add(l.drop(5).trim): Unit)
        catch case _: Throwable => ()
      ): Unit
    def next(): Json =
      val field =
        Option(lines.poll(20, java.util.concurrent.TimeUnit.SECONDS)).getOrElse(fail("no event"))
      // The field is a JSON string; its content is the notification.
      Json
        .parse(field)
        .toOption
        .flatMap(_.asString)
        .flatMap(Json.parse(_).toOption)
        .getOrElse(fail(field))
    try
      assertEquals(next()("type").flatMap(_.asString), Some("Activated"))
      model.expectCompleteTask(Answer("empty", List("memory")))
      val id = kit.componentClient.tasks.create(CartTasks.answer, "What is in c-9?").create()
      kit.componentClient.forAutonomousAgent(CartAnswerer)(instance).assign(id): Unit
      val seen = Iterator
        .continually(next())
        .map(_("type").flatMap(_.asString).get)
        .takeWhile(_ != "TaskCompleted")
        .toVector
      assertEquals(seen.headOption, Some("TaskAssigned"))
    finally response.cancel(true): Unit
  }
