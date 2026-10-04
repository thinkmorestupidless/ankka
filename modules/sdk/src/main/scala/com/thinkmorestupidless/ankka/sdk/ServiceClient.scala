package com.thinkmorestupidless.ankka.sdk

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromArray, writeToArray}

import java.nio.charset.StandardCharsets

/**
 * Calls another service's HTTP endpoints as this service (feature 014).
 *
 * Addressed by name, never by URL: in a cluster the client presents this service's own certificate,
 * so the callee's `Acl.allowCallers` sees who is calling, and it verifies the callee's certificate
 * names the service asked for before sending anything. Outside a cluster the same call reaches the
 * named service on this machine over plain HTTP, so calling code is the same in both places.
 *
 * Blocking, like `ComponentClient.invoke`: handlers run on virtual threads, where a wait parks the
 * thread and releases its carrier. No retries and no redirects — whether a call is safe to repeat
 * is the caller's to know.
 */
trait ServiceClient:

  /** The service this client calls, as `project/name`. */
  def target: String

  /** One request, answered whatever its status. */
  def request(
      method: String,
      path: String,
      body: Option[Array[Byte]] = None,
      contentType: Option[String] = None,
      headers: Seq[(String, String)] = Nil
  ): ServiceResponse

  /** A JSON answer, decoded; any status other than 2xx is a `ServiceCallFailed`. */
  final def get[R](path: String, headers: Seq[(String, String)] = Nil)(using JsonValueCodec[R]): R =
    decoded(request("GET", path, headers = headers))

  /** A text answer — what an endpoint returning a `String` sends. */
  final def getText(path: String, headers: Seq[(String, String)] = Nil): String =
    succeeded(request("GET", path, headers = headers)).text

  final def post[B, R](path: String, body: B, headers: Seq[(String, String)] = Nil)(using
      JsonValueCodec[B],
      JsonValueCodec[R]
  ): R =
    decoded(request("POST", path, Some(writeToArray(body)), Some("application/json"), headers))

  final def put[B, R](path: String, body: B, headers: Seq[(String, String)] = Nil)(using
      JsonValueCodec[B],
      JsonValueCodec[R]
  ): R =
    decoded(request("PUT", path, Some(writeToArray(body)), Some("application/json"), headers))

  /** For a route answering nothing: succeeds on any 2xx. */
  final def delete(path: String, headers: Seq[(String, String)] = Nil): Unit =
    succeeded(request("DELETE", path, headers = headers)): Unit

  private def succeeded(response: ServiceResponse): ServiceResponse =
    if response.status / 100 == 2 then response
    else throw ServiceCallFailed(target, response.status, response.text)

  private def decoded[R](response: ServiceResponse)(using JsonValueCodec[R]): R =
    readFromArray(succeeded(response).body)

final case class ServiceResponse(
    status: Int,
    contentType: String,
    body: Array[Byte],
    headers: Vector[(String, String)]
):
  def text: String = String(body, StandardCharsets.UTF_8)

/** The callee answered, with a status other than 2xx; its status and body as it sent them. */
final case class ServiceCallFailed(service: String, status: Int, body: String)
    extends RuntimeException(s"$service answered $status: $body")

/** The service could not be found — nothing was sent. `reason` names what was tried. */
final case class ServiceUnresolvable(service: String, reason: String)
    extends RuntimeException(s"cannot reach $service: $reason")

/**
 * The service reached presented a certificate for somebody else — nothing was sent. Seen when a
 * name resolves to a workload that is not the one asked for.
 */
final case class ServiceIdentityMismatch(service: String, detail: String)
    extends RuntimeException(s"the service reached as $service is not it: $detail")

/**
 * The service exists and serves no gRPC: in a cluster, its address publishes no `grpc` port; on
 * this machine, it is running and announced no gRPC address. Usually a descriptor without
 * `"grpc": true`, or a service that registered no gRPC server.
 */
final case class ServiceServesNoGrpc(service: String)
    extends RuntimeException(s"$service serves no gRPC")

/**
 * How a service obtains clients for the others: `services("orders")`,
 * `services("billing", "invoices")`.
 */
trait ServiceClients:
  /** A service in this service's own project. */
  def apply(name: String): ServiceClient

  /** A service in another project; whether it admits this one is its own ACL's decision. */
  def apply(project: String, name: String): ServiceClient
