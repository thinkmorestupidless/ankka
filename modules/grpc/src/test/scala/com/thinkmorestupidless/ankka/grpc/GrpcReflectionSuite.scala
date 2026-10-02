package com.thinkmorestupidless.ankka.grpc

import ankka.fixtures.v1.cart.*
import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.thinkmorestupidless.ankka.http.Acl
import com.typesafe.config.ConfigFactory
import io.grpc.reflection.v1.{
  ServerReflectionGrpc,
  ServerReflectionRequest,
  ServerReflectionResponse
}
import io.grpc.stub.StreamObserver
import io.grpc.{ManagedChannel, Status, StatusRuntimeException}

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Promise}
import scala.jdk.CollectionConverters.*
import scala.util.Try

/** A service that opts in tells a tool what it serves, to whom its reflection ACL admits. */
class GrpcReflectionSuite extends munit.FunSuite:

  private final class Closed extends GrpcEndpoint(CartServiceGrpc.SERVICE):
    val acl: Acl = Acl.DenyAll
    unary(CartServiceGrpc.METHOD_GET_CART)(request => Cart(request.cartId))
    unary(CartServiceGrpc.METHOD_ADD_ITEM)(request => Cart(request.cartId))

  private def serving[A](server: GrpcServer)(body: ManagedChannel => A): A =
    server.serve(Vector(Closed()), "127.0.0.1", 0, ConfigFactory.load())
    val channel = GrpcChannels.plaintext(server.boundPort.getOrElse(fail("not bound")))
    try body(channel)
    finally
      channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
      server.stop()

  /** One reflection request, as a tool sends it, and the one answer. */
  private def ask(
      channel: ManagedChannel,
      request: ServerReflectionRequest
  ): Either[Status, ServerReflectionResponse] =
    val answer = Promise[ServerReflectionResponse]()
    val requests = ServerReflectionGrpc
      .newStub(channel)
      .serverReflectionInfo(new StreamObserver[ServerReflectionResponse]:
        def onNext(value: ServerReflectionResponse): Unit = answer.trySuccess(value): Unit
        def onError(t: Throwable): Unit                   = answer.tryFailure(t): Unit
        def onCompleted(): Unit                           = ())
    requests.onNext(request)
    requests.onCompleted()
    Try(Await.result(answer.future, 10.seconds)).toEither.left.map {
      case e: StatusRuntimeException => e.getStatus
      case other                     => fail(s"not a status: $other")
    }

  private val listServices = ServerReflectionRequest.newBuilder().setListServices("").build()

  test("a service that has not opted into reflection answers none") {
    serving(GrpcServer.at("127.0.0.1", 0)()) { channel =>
      assertEquals(ask(channel, listServices).left.map(_.getCode), Left(Status.Code.UNIMPLEMENTED))
    }
  }

  test("a service that opts in lists its definitions, and describes their methods") {
    serving(GrpcServer.at("127.0.0.1", 0)().withReflection(Acl.AllowAll)) { channel =>
      val listed =
        ask(channel, listServices).toOption.get.getListServicesResponse.getServiceList.asScala
          .map(_.getName)
      assert(listed.contains("ankka.fixtures.v1.CartService"), listed.toString)
      val described = ask(
        channel,
        ServerReflectionRequest
          .newBuilder()
          .setFileContainingSymbol("ankka.fixtures.v1.CartService")
          .build()
      ).toOption.get.getFileDescriptorResponse.getFileDescriptorProtoList.asScala
        .map(bytes => FileDescriptorProto.parseFrom(bytes))
        .flatMap(_.getServiceList.asScala)
        .find(_.getName == "CartService")
        .getOrElse(fail("CartService was not described"))
      assertEquals(
        described.getMethodList.asScala.map(_.getName).toList,
        List("GetCart", "AddItem")
      )
    }
  }

  test("tools that ask the older version are answered too") {
    serving(GrpcServer.at("127.0.0.1", 0)().withReflection(Acl.AllowAll)) { channel =>
      val answer = Promise[io.grpc.reflection.v1alpha.ServerReflectionResponse]()
      val requests = io.grpc.reflection.v1alpha.ServerReflectionGrpc
        .newStub(channel)
        .serverReflectionInfo(
          new StreamObserver[io.grpc.reflection.v1alpha.ServerReflectionResponse]:
            def onNext(value: io.grpc.reflection.v1alpha.ServerReflectionResponse): Unit =
              answer.trySuccess(value): Unit
            def onError(t: Throwable): Unit = answer.tryFailure(t): Unit
            def onCompleted(): Unit         = ()
        )
      requests.onNext(
        io.grpc.reflection.v1alpha.ServerReflectionRequest.newBuilder().setListServices("").build()
      )
      requests.onCompleted()
      val listed = Await
        .result(answer.future, 10.seconds)
        .getListServicesResponse
        .getServiceList
        .asScala
        .map(_.getName)
      assert(listed.contains("ankka.fixtures.v1.CartService"), listed.toString)
    }
  }

  test("a tool the reflection ACL does not admit is refused and told nothing") {
    serving(GrpcServer.at("127.0.0.1", 0)().withReflection(Acl.DenyAll)) { channel =>
      assertEquals(
        ask(channel, listServices).left.map(_.getCode),
        Left(Status.Code.PERMISSION_DENIED)
      )
    }
  }

  test(
    "reflection lists a method whose endpoint denies all, and the method still refuses every call"
  ) {
    serving(GrpcServer.at("127.0.0.1", 0)().withReflection(Acl.AllowAll)) { channel =>
      assert(ask(channel, listServices).isRight)
      val refused = intercept[StatusRuntimeException](
        CartServiceGrpc.blockingStub(channel).getCart(GetCartRequest("c1"))
      )
      assertEquals(refused.getStatus.getCode, Status.Code.PERMISSION_DENIED)
    }
  }

  test("opting in without saying who may ask is refused when the service starts") {
    val failure = intercept[IllegalArgumentException](
      GrpcServer
        .at("127.0.0.1", 0)()
        .withReflection(null)
        .serve(Vector(Closed()), "127.0.0.1", 0, ConfigFactory.load())
    )
    assert(failure.getMessage.contains("reflection states no acl"), failure.getMessage)
  }
