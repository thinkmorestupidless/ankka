package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.sdk.{
  ServiceClient,
  ServiceClients,
  ServiceIdentityMismatch,
  ServiceResponse,
  ServiceUnanswered,
  ServiceUnresolvable
}

import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue}
import scala.jdk.CollectionConverters.*

/**
 * Other services, for a unit test: each answers as the test scripted it, and every request is
 * recorded.
 *
 * A call to a service the test did not script fails the test, naming the service: a test whose call
 * quietly got a default answer is no longer testing what it says. The typed helpers (`get`, `post`,
 * …) are the client's own, over `request`, so a script answers with a `ServiceResponse` and a
 * status outside 2xx is a `ServiceCallFailed` exactly as it would be.
 */
final class ScriptedServices(ownProject: String = "local") extends ServiceClients:

  private enum Script:
    case Answer(handler: ScriptedServices.Request => ServiceResponse)
    case Fail(failure: String => Throwable)

  private val scripts  = ConcurrentHashMap[(String, String), Script]()
  private val received = ConcurrentLinkedQueue[ScriptedServices.Request]()

  /** How `name` answers, in this service's own project unless a project is given. */
  def answer(name: String, project: String = ownProject)(
      handler: ScriptedServices.Request => ServiceResponse
  ): this.type =
    scripts.put((project, name), Script.Answer(handler))
    this

  /** `name` cannot be found: a call raises `ServiceUnresolvable`. */
  def unresolvable(name: String, project: String = ownProject): this.type =
    fail(project, name)(t => ServiceUnresolvable(t, "scripted as unresolvable"))

  /** `name` does not answer: a call raises `ServiceUnanswered`. */
  def unanswered(name: String, project: String = ownProject): this.type =
    fail(project, name)(t => ServiceUnanswered(t, "scripted as unanswered"))

  /** What answers for `name` is not it: a call raises `ServiceIdentityMismatch`. */
  def mismatch(name: String, project: String = ownProject): this.type =
    fail(project, name)(t => ServiceIdentityMismatch(t, "scripted as another service"))

  /** Every request made, in order, scripted or not. */
  def requests: Vector[ScriptedServices.Request] = received.asScala.toVector

  def clear(): Unit = received.clear()

  private def fail(project: String, name: String)(failure: String => Throwable): this.type =
    scripts.put((project, name), Script.Fail(failure))
    this

  def apply(name: String): ServiceClient = apply(ownProject, name)

  def apply(project: String, name: String): ServiceClient = new ServiceClient:
    val target: String = s"$project/$name"
    def request(
        method: String,
        path: String,
        body: Option[Array[Byte]],
        contentType: Option[String],
        headers: Seq[(String, String)]
    ): ServiceResponse =
      val made = ScriptedServices.Request(
        project,
        name,
        method,
        path,
        headers.toVector,
        contentType,
        body.getOrElse(Array.emptyByteArray)
      )
      received.add(made)
      Option(scripts.get((project, name))) match
        case Some(Script.Answer(handler)) => handler(made)
        case Some(Script.Fail(failure))   => throw failure(target)
        case None =>
          throw AssertionError(
            s"the test called the service '$target', and nothing is scripted for it: " +
              s"""script it with `answer("$name"${
                  if project == ownProject then "" else s""", "$project""""
                }) { request => ServiceResponse(…) }`"""
          )

object ScriptedServices:

  /** One request a component made. */
  final case class Request(
      project: String,
      service: String,
      method: String,
      path: String,
      headers: Vector[(String, String)],
      contentType: Option[String],
      body: Array[Byte]
  ):
    def text: String = String(body, java.nio.charset.StandardCharsets.UTF_8)

  /** An answer with `status` and `text`, as `text/plain`. */
  def text(text: String, status: Int = 200): ServiceResponse =
    ServiceResponse(status, "text/plain", text.getBytes("UTF-8"), Vector.empty)

  /** An answer with `status` and `json`, as `application/json`. */
  def json(json: String, status: Int = 200): ServiceResponse =
    ServiceResponse(status, "application/json", json.getBytes("UTF-8"), Vector.empty)
