package com.thinkmorestupidless.ankka.javasdk.testkit.docs

import com.thinkmorestupidless.ankka.agent.javasdk.{AgentClients, Agents}
import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.cart.{LineItem, ShoppingCart}
import com.thinkmorestupidless.ankka.javasdk.testkit.kinds.{CartAnswerer, CartTasks}
import com.thinkmorestupidless.ankka.javasdk.testkit.{AnkkaTestKit, TestModelProvider}
import com.thinkmorestupidless.ankka.testkit.LogCapturing

import java.io.{BufferedReader, InputStreamReader}
import java.net.http.{HttpRequest, HttpResponse}
import java.time.Duration
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/** The Java samples of `docs/build/autonomous-agents.md`, run against a scripted model. */
class DocsAutonomousSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private val model = TestModelProvider()
  private var kit: AnkkaTestKit = null
  private def client            = kit.componentClient

  override def beforeAll(): Unit =
    kit = AnkkaTestKit.start(
      Ankka
        .service("ops")
        .withModule(Agents.module().withModelProvider(model))
        .register(classOf[ShoppingCart])
        .register(classOf[CartAnswerer])
        .register(classOf[Operator])
        .withEndpoint(classOf[AnswererRoutes])
    )
  override def afterAll(): Unit = if kit != null then kit.close()
  override def beforeEach(context: BeforeEach): Unit =
    model.reset()
    Operator.RUNS.clear()

  test("an autonomous agent's tool that needs approval waits for a person's decision") {
    model
      .expectToolCall("restart_service", """{"service":"billing"}""")
      .expectCompleteTaskText("billing restarted")
    val id = AgentClients.tasks(client).create(Operator.OPERATE, "Restart billing.").create()
    val assigned = AgentClients.forAutonomousAgent(client, classOf[Operator], "ops-1").assign(id)
    assertEquals(assigned.accepted.asScala.toList, List(id))

    var approvalId: String = null
    kit.eventually(
      Duration.ofSeconds(20),
      () =>
        val awaiting =
          AgentClients.forAutonomousAgent(client, classOf[Operator], "ops-1").state().awaiting
        assert(!awaiting.isEmpty, "no approval requested yet")
        approvalId = awaiting.get(0).id
    )
    assert(Operator.RUNS.isEmpty, Operator.RUNS.toString)

    OperatorCalls.approve(client, approvalId)
    val done = AgentClients.forTask(client, id).await(Operator.OPERATE, Duration.ofSeconds(30))
    assertEquals(done.status, "completed", done.toString)
    assertEquals(done.result.get, "billing restarted")
    assertEquals(Operator.RUNS.asScala.toList, List("restart_service(billing)"))
  }

  test("two tasks are assigned together, the second running after the first") {
    client
      .forEventSourcedEntity("c-1")
      .call(DocsRefs.addItem)
      .invoke(LineItem("p-1", "Pen", 2)): Unit
    model
      .expectCompleteTask("""{"answer":"2 x Pen","sources":["cart_contents"]}""")
      .expectCompleteTask("""{"answer":"nothing","sources":["cart_contents"]}""")
    val result = OperatorCalls.review(client)
    assertEquals(result.accepted.size, 2, result.toString)
    assert(result.refused.isEmpty, result.toString)
    val Seq(first, second) = result.accepted.asScala.toSeq
    val firstDone  = AgentClients.forTask(client, first).await(CartTasks.ANSWER, Duration.ofSeconds(30))
    val secondDone = AgentClients.forTask(client, second).await(CartTasks.ANSWER, Duration.ofSeconds(30))
    assertEquals(firstDone.result.get.answer, "2 x Pen")
    assertEquals(secondDone.result.get.answer, "nothing")
  }

  test("an instance's notifications stream to a browser as server-sent events") {
    val response = kit.httpClient.send(
      HttpRequest.newBuilder(kit.http.resolve("/answerer/reviewer-2/notifications")).GET().build(),
      HttpResponse.BodyHandlers.ofInputStream()
    )
    assertEquals(response.statusCode, 200)
    model.expectCompleteTask("""{"answer":"empty","sources":["cart_total"]}""")
    val id = AgentClients.tasks(client).create(CartTasks.ANSWER, "Is c-9 empty?").create()
    AgentClients.forAutonomousAgent(client, classOf[CartAnswerer], "reviewer-2").assign(id): Unit

    val reader = BufferedReader(InputStreamReader(response.body))
    val seen   = Iterator.continually(reader.readLine()).takeWhile(_ != null)
    val lines  = seen.takeWhile(!_.contains("TaskCompleted")).toList
    assert(lines.exists(_.contains("TaskAssigned")), lines.mkString("\n"))
    response.body.close()
  }
