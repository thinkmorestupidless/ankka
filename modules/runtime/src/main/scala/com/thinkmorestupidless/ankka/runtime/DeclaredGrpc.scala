package com.thinkmorestupidless.ankka.runtime

/**
 * A service deployed to serve gRPC that registers nothing to serve it.
 *
 * The platform sets `ANKKA_GRPC_PORT` exactly when the descriptor declares gRPC, and only a
 * registered gRPC server reads it — so a service that registered none would start, bind nothing on
 * that port, and never be ready, with no reason anywhere a member could read. Instead it refuses to
 * start, saying why, and writes the same sentence to the container's termination log: the operator
 * reports a crashing container's last message as the service's reason, so `ankka services get`
 * shows it.
 *
 * Here rather than in the gRPC module because the point is a service that does not use that module.
 * The runtime knows the variable and the extension's name, nothing more.
 */
private[ankka] object DeclaredGrpc:

  /** What the platform tells a service its gRPC port by. */
  val PortEnvVar: String = "ANKKA_GRPC_PORT"

  /** The name a gRPC server extension registers under (`GrpcServer.Name`). */
  val ServerName: String = "grpc-server"

  /**
   * Where Kubernetes reads a container's termination message from, unless a test says otherwise.
   */
  def problem(env: String => Option[String], extensions: Seq[String]): Option[String] =
    env(PortEnvVar).filter(_.nonEmpty).filterNot(_ => extensions.contains(ServerName)).map { port =>
      s"the descriptor declares gRPC ($PortEnvVar=$port) and this service registers no gRPC " +
        "endpoint: register GrpcServer.of(…), or remove \"grpc\" from the descriptor"
    }

  def check(env: String => Option[String], extensions: Seq[String]): Unit =
    problem(env, extensions).foreach { message =>
      StartRefusal.refuse(message, IllegalStateException(_))
    }
