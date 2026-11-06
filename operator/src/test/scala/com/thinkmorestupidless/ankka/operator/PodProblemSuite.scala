package com.thinkmorestupidless.ankka.operator

import io.fabric8.kubernetes.api.model.{
  ContainerStateBuilder,
  ContainerStateTerminatedBuilder,
  ContainerStateWaitingBuilder,
  ContainerStatusBuilder,
  ObjectMetaBuilder,
  PodBuilder,
  PodStatusBuilder
}

/**
 * `PodProblem.of` on an image with no build for the node's architecture (feature 049): the kubelet
 * reports the container waiting — `RunContainerError` first, then `CrashLoopBackOff` — with the
 * runtime's refusal as the last termination's message. The problem keeps those words, says it is a
 * wrong architecture, and carries the container's image so the report can name it.
 */
class PodProblemSuite extends munit.FunSuite:

  private val Refusal =
    "failed to create containerd task: failed to create shim task: OCI runtime create failed: " +
      "runc create failed: unable to start container process: exec /opt/docker/bin/cart: exec format error"

  private def pod(
      reason: String,
      lastWords: Option[String],
      image: String = "cart:2",
      waitingMessage: String = "back-off"
  ) =
    val status = new ContainerStatusBuilder()
      .withName("cart")
      .withImage(image)
      .withState(
        new ContainerStateBuilder()
          .withWaiting(
            new ContainerStateWaitingBuilder()
              .withReason(reason)
              .withMessage(waitingMessage)
              .build()
          )
          .build()
      )
    lastWords.foreach { words =>
      status.withLastState(
        new ContainerStateBuilder()
          .withTerminated(
            new ContainerStateTerminatedBuilder()
              .withExitCode(128)
              .withReason("StartError")
              .withMessage(words)
              .build()
          )
          .build()
      )
    }
    new PodBuilder()
      .withMetadata(new ObjectMetaBuilder().withName("cart-0").build())
      .withStatus(new PodStatusBuilder().withContainerStatuses(status.build()).build())
      .build()

  test(
    "a container that crash-loops on exec format error is a wrong-architecture problem naming its image"
  ) {
    val problem = PodProblem.of(pod("CrashLoopBackOff", Some(Refusal))).get
    assertEquals(problem.reason, "CrashLoopBackOff")
    assert(problem.wrongArchitecture, problem.message)
    assertEquals(problem.image, Some("cart:2"))
    assertEquals(problem.architecture, None, "the node is read by the executor, not here")
  }

  test("the kubelet's first word for a container the runtime could not start is actionable too") {
    val problem = PodProblem.of(pod("RunContainerError", Some(Refusal))).get
    assertEquals(problem.reason, "RunContainerError")
    assert(problem.wrongArchitecture, problem.message)
  }

  test("a crash loop for any other reason is not a wrong architecture") {
    val problem = PodProblem.of(pod("CrashLoopBackOff", Some("refusing to start: no key"))).get
    assert(!problem.wrongArchitecture, problem.message)
    assertEquals(problem.image, Some("cart:2"))
  }

  test(
    "an image published for another platform alone is refused at pull, and that is a wrong architecture too"
  ) {
    // The kubelet's words, from a k3s node given a single-platform image for the other one.
    val refused = pod(
      "ImagePullBackOff",
      None,
      image = "ghcr.io/acme/orders:1.4.2",
      waitingMessage =
        "Back-off pulling image \"ghcr.io/acme/orders:1.4.2\": ErrImagePull: failed to pull and unpack image " +
          "\"ghcr.io/acme/orders:1.4.2\": no match for platform in manifest: not found"
    )
    val problem = PodProblem.of(refused).get
    assertEquals(problem.reason, "ImagePullBackOff")
    assert(problem.wrongArchitecture, problem.message)
    assertEquals(problem.image, Some("ghcr.io/acme/orders:1.4.2"))
  }

  test("a container that has just exited on exec format error names its image too") {
    // Between the kubelet's restarts the container is terminated, not waiting: the words a k3s
    // node on GitHub's runners gave, where the image was run rather than refused at pull.
    val exited = new ContainerStatusBuilder()
      .withName("cart")
      .withImage("cart:2")
      .withState(
        new ContainerStateBuilder()
          .withTerminated(
            new ContainerStateTerminatedBuilder()
              .withExitCode(255)
              .withReason("Error")
              .withMessage("exec /__cacert_entrypoint.sh: exec format error")
              .build()
          )
          .build()
      )
      .build()
    val p = new PodBuilder()
      .withMetadata(new ObjectMetaBuilder().withName("cart-0").build())
      .withStatus(new PodStatusBuilder().withContainerStatuses(exited).build())
      .build()
    val problem = PodProblem.of(p).get
    assertEquals(problem.reason, "Error")
    assert(problem.wrongArchitecture, problem.message)
    assertEquals(problem.image, Some("cart:2"))
  }

  test("an image that cannot be pulled is reported as before") {
    val problem = PodProblem.of(pod("ImagePullBackOff", None, image = "cart:missing")).get
    assertEquals(problem.reason, "ImagePullBackOff")
    assertEquals(problem.message, "back-off")
    assert(!problem.wrongArchitecture)
  }

  test("the newest pod's problem comes first, whatever the pods are named") {
    // Generation 5's unpullable image left behind a rollout to an image for the other architecture:
    // the older pod's name sorts first, as the API server lists them.
    def named(name: String, created: String, reason: String, words: Option[String]) =
      val p = pod(reason, words)
      p.getMetadata.setName(name)
      p.getMetadata.setCreationTimestamp(created)
      p
    val older = named("cart-c5b867499-9wbgs", "2026-11-05T19:05:01Z", "ImagePullBackOff", None)
    val newer =
      named("cart-dbb8f98db-pg8j6", "2026-11-05T19:06:12Z", "CrashLoopBackOff", Some(Refusal))
    val first = PodProblem.newestFirst(Vector(older, newer)).flatMap(PodProblem.of).head
    assert(first.wrongArchitecture, first.describe)
    assertEquals(first.pod, "cart-dbb8f98db-pg8j6")
    val same = named("cart-b", "2026-11-05T19:06:12Z", "CrashLoopBackOff", Some(Refusal))
    assertEquals(
      PodProblem.newestFirst(Vector(newer, same)).map(_.getMetadata.getName),
      Vector("cart-dbb8f98db-pg8j6", "cart-b"),
      "pods created in the same second keep the order they were listed in"
    )
  }
