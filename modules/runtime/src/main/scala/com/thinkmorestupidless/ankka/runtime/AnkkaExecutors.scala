package com.thinkmorestupidless.ankka.runtime

import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext

object AnkkaExecutors:

  /**
   * One virtual thread per task.
   *
   * This is what makes `ComponentClient.invoke` honest. Handlers dispatched here can await
   * inter-component calls as ordinary sequential code: the await parks the virtual thread and frees
   * its carrier, so a handler blocked on three entity calls occupies no OS thread at all.
   * Endpoints, workflow steps and consumers all run here.
   */
  lazy val virtual: ExecutionContext =
    ExecutionContext.fromExecutorService(Executors.newVirtualThreadPerTaskExecutor())
