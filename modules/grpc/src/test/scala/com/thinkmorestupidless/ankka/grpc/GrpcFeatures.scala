package com.thinkmorestupidless.ankka.grpc

import com.thinkmorestupidless.ankka.testkit.{GherkinSuite, LogCapturing}

/**
 * Every feature under `features/grpc/`, run as written: the behaviour of one service's gRPC
 * endpoints, which one process can show. What needs a cluster is under `features/grpc-deployed/`
 * and runs in the control plane's k3s suite.
 *
 * A feature not yet implemented is tagged `@ignore`, which reports its scenarios ignored rather
 * than passed; the task that implements a file removes its tag.
 */
class GrpcFeatures extends GherkinSuite("../../features/grpc") with LogCapturing
