package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import com.thinkmorestupidless.ankka.sdk.{
  SecretStore,
  ServiceClient,
  ServiceClients,
  ServiceResponse
}

/**
 * Whether the calling thread is running a workflow's step.
 *
 * A workflow's command handlers and its steps share one context, so its type cannot keep the secret
 * store from a command. The mark can: the engine sets it around a step's body, on the step's own
 * virtual thread, and a command handler runs on the actor's thread, where it is never set. Work a
 * step hands to another thread does not carry the mark, as it does not carry any thread-local.
 */
private[ankka] object StepScope:

  private val inStep = ThreadLocal.withInitial[java.lang.Boolean](() => false)

  def within[A](body: => A): A =
    inStep.set(true)
    try body
    finally inStep.set(false)

  def active: Boolean = inStep.get

  /** A store that answers only from inside a step. */
  def stepsOnly(underlying: SecretStore): SecretStore = new SecretStore:
    private def require(): Unit =
      if !active then
        throw CommandError(
          "a workflow reads and keeps a service secret in a step, not in a command handler",
          ErrorCode.BadRequest
        )
    def put(name: String, value: String): Unit = { require(); underlying.put(name, value) }
    def get(name: String): Option[String]      = { require(); underlying.get(name) }
    def delete(name: String): Unit             = { require(); underlying.delete(name) }

  /**
   * Clients for other services that make a call only from inside a step. Obtaining one anywhere is
   * harmless; a request made outside a step is refused, for the reason a secret is.
   */
  def stepsOnly(underlying: ServiceClients): ServiceClients = new ServiceClients:
    def apply(name: String): ServiceClient                  = guarded(underlying(name))
    def apply(project: String, name: String): ServiceClient = guarded(underlying(project, name))
    private def guarded(client: ServiceClient): ServiceClient = new ServiceClient:
      def target: String = client.target
      def request(
          method: String,
          path: String,
          body: Option[Array[Byte]],
          contentType: Option[String],
          headers: Seq[(String, String)]
      ): ServiceResponse =
        if !active then
          throw CommandError(
            "a workflow calls another service in a step, not in a command handler",
            ErrorCode.BadRequest
          )
        client.request(method, path, body, contentType, headers)
