package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.agent.*
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.DurationInt

/**
 * A support agent whose refund tool waits for a person.
 *
 * Each tool records what it was run with, so a test can assert that a tool did not run, ran once,
 * or ran with the arguments the model proposed.
 */
final class ApprovalAgent extends Agent:

  // docs:start ask
  def ask(question: String): Effect[String] =
    effects
      .systemMessage(ApprovalAgent.SystemMessage)
      .userMessage(question)
      .tools(ApprovalAgent.issueRefund, ApprovalAgent.readOrder, ApprovalAgent.closeAccount)
      .thenReply()
  // docs:end ask

  /** Remembers nothing, to show a turn waits in the session whether or not history is kept. */
  def askOnce(question: String): Effect[String] =
    effects
      .systemMessage(ApprovalAgent.SystemMessage)
      .userMessage(question)
      .tools(ApprovalAgent.issueRefund)
      .memory(MemoryProvider.none)
      .thenReply()

  def chat(question: String): StreamEffect =
    effects
      .systemMessage(ApprovalAgent.SystemMessage)
      .userMessage(question)
      .tools(ApprovalAgent.issueRefund, ApprovalAgent.readOrder)
      .thenStream()

object ApprovalAgent extends Agent.Companion[ApprovalAgent](ComponentId("support-agent")):

  val SystemMessage = "You are a support agent. Refunds need a supervisor's approval."

  /** One line per run: `tool(arguments)`. */
  val runs: ConcurrentLinkedQueue[String] = ConcurrentLinkedQueue[String]()

  def runsOf(tool: String): Vector[String] =
    runs.toArray.toVector.map(_.toString).filter(_.startsWith(s"$tool("))

  // docs:start requires-approval
  val issueRefund: FunctionTool = FunctionTool
    .named("issue_refund")
    .describedAs("Refunds an order. A supervisor approves every refund before it is made.")
    .param[String]("order", "The order to refund.")
    .param[Int]("amount", "The amount to refund.")
    .handle { (order, amount) =>
      runs.add(s"issue_refund($order,$amount)"): Unit
      s"refunded $amount on $order"
    }
    .requiresApproval
  // docs:end requires-approval

  val readOrder: FunctionTool = FunctionTool
    .named("read_order")
    .describedAs("Reads an order.")
    .param[String]("order", "The order to read.")
    .handle { order =>
      runs.add(s"read_order($order)"): Unit
      s"$order: one teapot, 40"
    }

  // docs:start requires-approval-within
  val closeAccount: FunctionTool = FunctionTool
    .named("close_account")
    .describedAs("Closes a customer's account.")
    .param[String]("customer", "The customer whose account to close.")
    .handle { customer =>
      runs.add(s"close_account($customer)"): Unit
      s"closed $customer"
    }
    .requiresApproval(2.seconds)
  // docs:end requires-approval-within

  def create(context: AgentContext) = new ApprovalAgent

  val ask     = command("ask")(_.ask)
  val askOnce = command("ask-once")(_.askOnce)
  val chat    = stream("chat")(_.chat)
