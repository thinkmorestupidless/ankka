package com.thinkmorestupidless.ankka.javasdk.bridge

import com.thinkmorestupidless.ankka.javasdk.client.{
  ServiceCallFailed,
  ServiceClient,
  ServiceClients,
  ServiceResponse,
  ServiceIdentityMismatch,
  ServiceUnanswered,
  ServiceUnresolvable
}
import com.thinkmorestupidless.ankka.javasdk.codec.{RecordCodec, Serializer}
import com.thinkmorestupidless.ankka.sdk

import java.util.Map as JMap
import scala.jdk.CollectionConverters.*

/** The Java service clients over the Scala ones. */
private[ankka] object Services:

  def of(scala: sdk.ServiceClients, serializers: Declarations.Serializers): ServiceClients =
    new ServiceClients:
      def of(service: String): ServiceClient = JavaServiceClient(scala(service), serializers)
      def of(project: String, service: String): ServiceClient =
        JavaServiceClient(scala(project, service), serializers)

  val unavailable: ServiceClients = of(sdk.ServiceClients.unavailable, Map.empty)

private final class JavaServiceClient(
    scala: sdk.ServiceClient,
    serializers: Declarations.Serializers
) extends ServiceClient:

  def target(): String = scala.target

  def request(
      method: String,
      path: String,
      body: Array[Byte],
      contentType: String
  ): ServiceResponse =
    request(method, path, body, contentType, JMap.of())

  def request(
      method: String,
      path: String,
      body: Array[Byte],
      contentType: String,
      headers: JMap[String, String]
  ): ServiceResponse =
    val r = calling(
      scala.request(method, path, Option(body), Option(contentType), headers.asScala.toSeq)
    )
    ServiceResponse(
      r.status,
      r.contentType,
      r.body,
      r.headers.map((k, v) => JMap.entry(k, v)).asJava
    )

  def get[R](path: String, reply: Class[R]): R = decoded(send("GET", path, None), reply)

  def getText(path: String): String = send("GET", path, None).text

  def getText(path: String, headers: JMap[String, String]): String =
    send("GET", path, None, headers.asScala.toSeq).text

  def post[B, R](path: String, body: B, reply: Class[R]): R =
    decoded(send("POST", path, Some(encoded(body))), reply)

  def put[B, R](path: String, body: B, reply: Class[R]): R =
    decoded(send("PUT", path, Some(encoded(body))), reply)

  def delete(path: String): Unit = send("DELETE", path, None): Unit

  private def serializer[A](cls: Class[A]): Serializer[A] =
    serializers.get(cls).map(_.asInstanceOf[Serializer[A]]).getOrElse(RecordCodec.forType(cls))

  private def encoded[B](body: B): (Array[Byte], String) =
    val s = serializer(body.getClass.asInstanceOf[Class[B]])
    s.toBytes(body) -> s.contentType.mediaType

  private def decoded[R](response: sdk.ServiceResponse, reply: Class[R]): R =
    if reply == classOf[Void] then null.asInstanceOf[R]
    else serializer(reply).fromBytes(response.body)

  private def send(
      method: String,
      path: String,
      body: Option[(Array[Byte], String)],
      headers: Seq[(String, String)] = Nil
  ): sdk.ServiceResponse =
    val r = calling(scala.request(method, path, body.map(_._1), body.map(_._2), headers))
    if r.status / 100 != 2 then throw ServiceCallFailed(scala.target, r.status, r.text)
    r

  private def calling(call: => sdk.ServiceResponse): sdk.ServiceResponse =
    try call
    catch
      case e: sdk.ServiceUnresolvable     => throw ServiceUnresolvable(e.service, e.getMessage, e)
      case e: sdk.ServiceIdentityMismatch =>
        throw ServiceIdentityMismatch(e.service, e.getMessage, e)
      case e: sdk.ServiceUnanswered => throw ServiceUnanswered(e.service, e.getMessage, e)
