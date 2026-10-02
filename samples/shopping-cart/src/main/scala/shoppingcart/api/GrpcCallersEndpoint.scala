package shoppingcart.api

import com.thinkmorestupidless.ankka.core.CommandError
import com.thinkmorestupidless.ankka.grpc.GrpcClients
import com.thinkmorestupidless.ankka.http.*
import io.grpc.stub.MetadataUtils
import io.grpc.{ClientInterceptors, Metadata, StatusRuntimeException}
import shoppingcart.v1.cart.{CartServiceGrpc, WhoCalledRequest}

import scala.util.control.NonFatal

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
