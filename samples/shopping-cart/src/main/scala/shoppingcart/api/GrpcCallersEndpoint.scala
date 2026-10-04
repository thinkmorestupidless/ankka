package shoppingcart.api

import com.thinkmorestupidless.ankka.core.CommandError
import com.thinkmorestupidless.ankka.grpc.GrpcClients
import com.thinkmorestupidless.ankka.http.*
import io.grpc.reflection.v1.{
  ServerReflectionGrpc,
  ServerReflectionRequest,
  ServerReflectionResponse
}
import io.grpc.stub.{MetadataUtils, StreamObserver}
import io.grpc.{ClientInterceptors, Metadata, StatusRuntimeException}
import shoppingcart.v1.cart.{CartServiceGrpc, WhoCalledRequest}

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Promise}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/**
 * Calls another service's gRPC endpoint as this service, and says what came back. Not part of the
 * cart's domain: it is how the platform's own cluster suites drive one service calling another.
 */
final class GrpcCallersEndpoint(grpc: GrpcClients) extends HttpEndpoint("/callers/grpc"):

  val acl: Acl = Acl.AllowAll

  // docs:start call-another-service-grpc
  // Asks another cart service of this project who it read the call as coming from: the answer is
  // how that service saw this one.
  get("/{service}") { (service: String) =>
    CartServiceGrpc.blockingStub(grpc(service)).whoCalled(WhoCalledRequest()).caller
  }
  // docs:end call-another-service-grpc

  // The same call, answering why it could not be made — a service nobody has, one that serves no
  // gRPC — where the route above answers a bare 500. A refusal still passes as itself.
  get("/{service}/explained") { (service: String) =>
    try CartServiceGrpc.blockingStub(grpc(service)).whoCalled(WhoCalledRequest()).caller
    catch case NonFatal(e) if CommandError.from(e).isEmpty => s"failed: ${e.getMessage}"
  }

  // `n` calls in a row, counted by the instance that answered each, and the failures: what shows a
  // caller's calls spread over a service's instances, and none refused while they are replaced.
  get("/{service}/loop/{n}") { (service: String, n: Int) =>
    val answers = (1 to n).map { _ =>
      try Right(CartServiceGrpc.blockingStub(grpc(service)).whoCalled(WhoCalledRequest()).instance)
      catch case e: StatusRuntimeException => Left(e.getStatus.getCode.toString)
    }
    val byInstance = answers.collect { case Right(instance) => instance }.groupBy(identity)
    val counts = byInstance.toSeq.sortBy(_._1).map((i, all) => s"\"$i\":${all.size}").mkString(",")
    s"""{"instances":{$counts},"failures":${answers.count(_.isLeft)}}"""
  }

  // Asks another service what it serves, as this service. Reflection at a gRPC address is judged by
  // that service's own reflection ACL, reading this service from its certificate: the answer is the
  // service definitions it listed, or the status it refused with.
  get("/{service}/reflection") { (service: String) =>
    val answer = Promise[ServerReflectionResponse]()
    val requests = ServerReflectionGrpc
      .newStub(grpc(service))
      .withDeadlineAfter(10, TimeUnit.SECONDS)
      .serverReflectionInfo(new StreamObserver[ServerReflectionResponse]:
        def onNext(value: ServerReflectionResponse): Unit = answer.trySuccess(value): Unit
        def onError(t: Throwable): Unit                   = answer.tryFailure(t): Unit
        def onCompleted(): Unit                           = ())
    requests.onNext(ServerReflectionRequest.newBuilder().setListServices("").build())
    requests.onCompleted()
    Try(Await.result(answer.future, 15.seconds)) match
      case Success(response) =>
        response.getListServicesResponse.getServiceList.asScala.map(_.getName).mkString(",")
      case Failure(e: StatusRuntimeException) => s"failed: ${e.getStatus.getCode}"
      case Failure(e)                         => s"failed: ${e.getMessage}"
  }

  // The same call, claiming in its metadata to come from `other`. In a cluster the called service
  // reads the caller from this service's certificate and the claim changes nothing.
  get("/{service}/claiming/{other}") { (service: String, other: String) =>
    val (name, value) = LocalCallers.header(Caller.Service("local", other))
    val claim         = Metadata()
    claim.put(Metadata.Key.of(name, Metadata.ASCII_STRING_MARSHALLER), value)
    val channel =
      ClientInterceptors.intercept(grpc(service), MetadataUtils.newAttachHeadersInterceptor(claim))
    CartServiceGrpc.blockingStub(channel).whoCalled(WhoCalledRequest()).caller
  }
