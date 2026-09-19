package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.AnkkaServiceSpec

import java.time.Instant

/**
 * One case per rule, plus the two orderings that are easy to get backwards.
 *
 * Pure: no cluster, no Docker, no clock — the timestamp is supplied. This is the suite that makes
 * "what does the operator report" answerable without deploying anything.
 */
class LifecycleRulesSuite extends munit.FunSuite:

  private val spec = AnkkaServiceSpec(
    projectId = "checkout",
    serviceName = "cart",
    generation = 4L,
    image = "cart:1.0"
  )

  private val now = Instant.parse("2026-09-14T10:00:00Z")

  private def snapshot(
      specReplicas: Int = 1,
      readyReplicas: Int = 1,
      updatedReplicas: Int = 1,
      totalReplicas: Int = -1,
      k8sGeneration: Long = 3L,
      observedGeneration: Long = 3L,
      progressing: Option[ConditionState] = None,
      problems: Vector[PodProblem] = Vector.empty
  ) = ClusterSnapshot(
    exists = true,
    ankkaGeneration = Some(4L),
    specReplicas = specReplicas,
    readyReplicas = readyReplicas,
    updatedReplicas = updatedReplicas,
    // Defaults to "no old pods around" unless a test says otherwise.
    totalReplicas = if totalReplicas < 0 then updatedReplicas else totalReplicas,
    k8sGeneration = Some(k8sGeneration),
    observedGeneration = Some(observedGeneration),
    progressing = progressing,
    available = None,
    podProblems = problems
  )

  private def observe(
      s: AnkkaServiceSpec = spec,
      c: Option[ClusterSnapshot] = None,
      problems: Vector[String] = Vector.empty
  ) = LifecycleRules.observe(s, c, problems, metadataGeneration = 3L, now = now)

  // ── The table, in order ───────────────────────────────────────────────────

  test("rule 1: a paused service is Paused") {
    val status = observe(spec.copy(paused = true), Some(snapshot()))
    assertEquals(status.lifecycle, "Paused")
    assertEquals(status.desiredInstances, 0)
  }

  test("rule 2: a service that cannot be rendered is Failed, with every reason at once") {
    val status =
      observe(problems = Vector("project id is not a DNS label", "image must not be empty"))
    assertEquals(status.lifecycle, "Failed")
    assertEquals(status.detail, Some("project id is not a DNS label; image must not be empty"))
  }

  test("rule 3: nothing deployed yet is UpdateInProgress wanting one instance") {
    val status = observe(c = None)
    assertEquals(status.lifecycle, "UpdateInProgress")
    assertEquals(status.readyInstances, 0)
    assertEquals(status.desiredInstances, 1)
  }

  test("rule 4: an exceeded progress deadline is Failed") {
    val stuck = snapshot(
      readyReplicas = 0,
      progressing = Some(ConditionState(status = false, "ProgressDeadlineExceeded", "timed out"))
    )
    assertEquals(observe(c = Some(stuck)).lifecycle, "Failed")
  }

  test("rule 5: a spec the API server has not acted on yet is UpdateInProgress") {
    val pending = snapshot(k8sGeneration = 4L, observedGeneration = 3L)
    assertEquals(observe(c = Some(pending)).lifecycle, "UpdateInProgress")
  }

  test("rule 6: pods still being replaced is UpdateInProgress") {
    assertEquals(
      observe(c = Some(snapshot(specReplicas = 1, updatedReplicas = 0))).lifecycle,
      "UpdateInProgress"
    )
  }

  test("rule 7: every requested instance ready is Ready") {
    val status = observe(c = Some(snapshot()))
    assertEquals(status.lifecycle, "Ready")
    assertEquals(status.readyInstances, 1)
    assertEquals(status.desiredInstances, 1)
    assertEquals(status.detail, None)
  }

  test("rule 8: some but not all ready is PartiallyReady, with both counts") {
    // Written in feature 001 as "unreachable at one replica"; reachable since feature 004.
    val partial = snapshot(specReplicas = 3, readyReplicas = 2, updatedReplicas = 3)
    val status  = observe(c = Some(partial))
    assertEquals(status.lifecycle, "PartiallyReady")
    assertEquals(status.readyInstances, 2)
    assertEquals(status.desiredInstances, 3)
  }

  test("rule 7b: pods of the old template still present is UpdateInProgress, never Ready") {
    // Mid-rollout the Deployment reports updated = spec (the new pods exist) and ready = spec —
    // but the pod being counted ready can be an OLD one that has not yet been replaced. Seen
    // live in feature 003, and hidden rather than fixed by Recreate; RollingUpdate brings it back.
    val rolling =
      snapshot(specReplicas = 3, readyReplicas = 3, updatedReplicas = 3, totalReplicas = 4)
    assertEquals(observe(c = Some(rolling)).lifecycle, "UpdateInProgress")
  }

  test("rule 9: none ready while some are wanted is Unavailable") {
    assertEquals(observe(c = Some(snapshot(readyReplicas = 0))).lifecycle, "Unavailable")
  }

  test("rule 10: a Deployment scaled to zero that is not paused is NotDeployed") {
    val scaledToZero = snapshot(specReplicas = 0, readyReplicas = 0, updatedReplicas = 0)
    assertEquals(observe(c = Some(scaledToZero)).lifecycle, "NotDeployed")
  }

  // ── The orderings that are load-bearing ───────────────────────────────────

  test("pause beats an exceeded progress deadline") {
    // Pause is desired state. Reversing these makes pausing a broken service report the
    // breakage forever, and an operator can never quiet it.
    val broken = snapshot(
      readyReplicas = 0,
      progressing = Some(ConditionState(status = false, "ProgressDeadlineExceeded", "timed out"))
    )
    assertEquals(observe(spec.copy(paused = true), Some(broken)).lifecycle, "Paused")
  }

  test("observedGeneration beats updatedReplicas") {
    // updatedReplicas is stale until the API server has acted on the new spec. Checking it
    // first reports Ready for the *previous* generation mid-rollout.
    val midRollout = snapshot(
      specReplicas = 1,
      readyReplicas = 1,
      updatedReplicas = 1,
      k8sGeneration = 5L,
      observedGeneration = 4L
    )
    assertEquals(observe(c = Some(midRollout)).lifecycle, "UpdateInProgress")
  }

  // ── Detail ────────────────────────────────────────────────────────────────

  test("a pod problem explains a failure better than the condition does") {
    val stuck = snapshot(
      readyReplicas = 0,
      progressing = Some(ConditionState(status = false, "ProgressDeadlineExceeded", "timed out")),
      problems = Vector(PodProblem("cart-abc", "ImagePullBackOff", "manifest unknown"))
    )
    assertEquals(observe(c = Some(stuck)).detail, Some("ImagePullBackOff: manifest unknown"))
  }

  test("a failure with no pod problem still says something") {
    val stuck = snapshot(
      readyReplicas = 0,
      progressing = Some(ConditionState(status = false, "ProgressDeadlineExceeded", ""))
    )
    assert(observe(c = Some(stuck)).detail.exists(_.contains("progress deadline")))
  }

  test("a missing secret surfaces as CreateContainerConfigError") {
    val stuck = snapshot(
      readyReplicas = 0,
      problems = Vector(
        PodProblem("cart-abc", "CreateContainerConfigError", "secret \"cart-db\" not found")
      )
    )
    val detail = observe(c = Some(stuck)).detail.getOrElse("")
    assert(detail.contains("CreateContainerConfigError"), detail)
    assert(detail.contains("cart-db"), detail)
  }

  test("only a secret's name and key can appear in a detail, never a value") {
    // Structural, not a discipline: the operator has no RBAC verb on secrets, so it has no
    // value to leak. This asserts the message it does build stays on the safe side.
    val stuck = snapshot(
      readyReplicas = 0,
      problems = Vector(
        PodProblem("cart-abc", "CreateContainerConfigError", "secret \"cart-db\" not found")
      )
    )
    val detail = observe(c = Some(stuck)).detail.getOrElse("")
    assert(!detail.toLowerCase.contains("password"), detail)
  }

  // ── Generations and change detection ──────────────────────────────────────

  test("the status reports the spec's generation, not one read back from the cluster") {
    val status = observe(c = Some(snapshot()))
    assertEquals(status.generation, 4L)
    assertEquals(status.observedGeneration, 3L)
  }

  test("an unsupported version is refused rather than acted on partially") {
    val status = LifecycleRules.unsupportedVersion("v9", "v1alpha1")
    assertEquals(status.lifecycle, "Failed")
    assert(status.detail.exists(_.contains("v9")))
  }

  test("a status differing only by its timestamp is not a new report") {
    // Without this the resync rewrites every status on every pass — a permanent write load
    // rather than a failing test.
    val a = observe(c = Some(snapshot()))
    val b = LifecycleRules.observe(spec, Some(snapshot()), Vector.empty, 3L, now.plusSeconds(600))
    assert(a.sameReport(b))
  }

  // --- Exposure (feature 005): the gateway's two conditions fold into one word for the resource.

  test("a route's status is one word: accepted, pending, or rejected with the gateway's reason") {
    def view(accepted: Option[(Boolean, String)], resolved: Option[(Boolean, String)]) =
      RouteView(accepted = accepted, resolvedRefs = resolved)

    assertEquals(LifecycleRules.routeStatus(exposed = true, Some("example.test"), None), "pending")
    assertEquals(
      LifecycleRules.routeStatus(
        exposed = true,
        Some("example.test"),
        Some(view(Some(true -> "Accepted"), Some(true -> "ResolvedRefs")))
      ),
      "accepted"
    )
    // Accepted by the listener but the backend not permitted: still rejected, and it says why.
    assertEquals(
      LifecycleRules.routeStatus(
        exposed = true,
        Some("example.test"),
        Some(view(Some(true -> "Accepted"), Some(false -> "RefNotPermitted")))
      ),
      "rejected: RefNotPermitted"
    )
    assertEquals(
      LifecycleRules.routeStatus(
        exposed = true,
        Some("example.test"),
        Some(view(Some(false -> "NotAllowedByListeners"), None))
      ),
      "rejected: NotAllowedByListeners"
    )
    // An operator with no base domain cannot render a route, and must say so, not go quiet.
    assert(LifecycleRules.routeStatus(exposed = true, None, None).contains("ANKKA_BASE_DOMAIN"))
  }
