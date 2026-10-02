package com.thinkmorestupidless.ankka.grpc

import com.thinkmorestupidless.ankka.http.Acl
import io.grpc.*
import io.grpc.protobuf.services.{ProtoReflectionService, ProtoReflectionServiceV1}

/**
 * The standard reflection service, behind an ACL of its own.
 *
 * grpc-java's: it describes every service whose descriptor carries the `.proto` file's descriptor,
 * which ScalaPB's generated code sets and the binding keeps. Both versions are served, because a
 * tool asks for v1 and falls back to v1alpha. A reflection call is judged by `acl` exactly as a
 * call to an endpoint's method is judged by the endpoint's, and refused the same way; it records no
 * span, since it is not a method the service declared.
 */
private[grpc] object Reflection:

  @annotation.nowarn("cat=deprecation")
  def services(acl: Acl, admission: Admission): Vector[ServerServiceDefinition] =
    // v1alpha is deprecated in grpc-java and still what older tools ask for.
    Vector(ProtoReflectionServiceV1.newInstance(), ProtoReflectionService.newInstance())
      .map(service => ServerInterceptors.intercept(service, Guard(acl, admission)))

  private final class Guard(acl: Acl, admission: Admission) extends ServerInterceptor:
    def interceptCall[Q, R](
        call: ServerCall[Q, R],
        headers: Metadata,
        next: ServerCallHandler[Q, R]
    ): ServerCall.Listener[Q] =
      admission
        .contextFor(call, headers, call.getMethodDescriptor.getFullMethodName)
        .left
        .map(_ -> Metadata())
        .flatMap(admission.decide(acl, _)) match
        case Left((status, trailers)) =>
          call.close(status, trailers)
          new ServerCall.Listener[Q] {}
        case Right(_) => next.startCall(call, headers)
