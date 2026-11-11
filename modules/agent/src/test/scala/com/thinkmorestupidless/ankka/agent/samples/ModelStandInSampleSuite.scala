package com.thinkmorestupidless.ankka.agent.samples

import scala.concurrent.Await
import scala.concurrent.duration.*

/** The testing guide's sample: a provider at an address, tested against the stand-in. */
class ModelStandInSampleSuite extends munit.FunSuite:

  test("a provider at an address answers from the stand-in's script") {
    // docs:start stand-in
    import com.thinkmorestupidless.ankka.agent.{ModelResponse, ModelStandIn}
    import com.thinkmorestupidless.ankka.agent.openai.OpenAiProvider

    val standIn = ModelStandIn.start()
    standIn.answer(ModelResponse("Paris"))
    val local = OpenAiProvider.at("local", s"${standIn.address}/v1", "qwen3:8b")
    val reply = Await.result(local.complete(question("The capital of France?")), 30.seconds)
    assertEquals(standIn.lastRequest.body("model").flatMap(_.asString), Some("qwen3:8b"))
    standIn.close()
    // docs:end stand-in
    assertEquals(reply.text, "Paris")
  }

  private def question(text: String) =
    import com.thinkmorestupidless.ankka.agent.{ChatMessage, ModelRequest, ModelSettings}
    ModelRequest(ModelSettings("qwen3:8b"), None, Vector(ChatMessage.User.text(text)))
