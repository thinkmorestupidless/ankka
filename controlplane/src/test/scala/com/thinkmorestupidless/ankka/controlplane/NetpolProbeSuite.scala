package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import java.nio.file.{Files, Path, Paths}
import java.nio.file.attribute.PosixFilePermissions
import scala.sys.process.*

/**
 * The deploy script's network-policy probe (feature 014), run as bash against a stubbed `kubectl`
 * that plays a cluster which does, or does not, enforce the policy the probe applies. Nothing else
 * the script does can tell the two apart: the API server stores the policy either way.
 */
class NetpolProbeSuite extends munit.FunSuite with LogCapturing:

  private def repoRoot: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.isDirectory(p.resolve("kustomization")))
      .getOrElse(fail("could not find the repository root"))

  /**
   * A `kubectl` that answers everything, and fails an `exec` only once a policy has been applied
   * and the cluster is said to enforce it — or always, for a network that connects nothing.
   */
  private def stub(enforces: Boolean, connects: Boolean = true): Path =
    val dir   = Files.createTempDirectory("kubectl-stub")
    val state = dir.resolve("policy-applied")
    val script =
      s"""#!/usr/bin/env bash
         |case "$$*" in
         |  *" apply "*) cat >/dev/null; touch "$state"; exit 0 ;;
         |  *" get pod server "*) echo 10.0.0.9; exit 0 ;;
         |  *" exec "*)
         |    ${if connects then "" else "exit 1"}
         |    if [[ -f "$state" && "$enforces" == "true" ]]; then exit 1; fi
         |    exit 0 ;;
         |  *) exit 0 ;;
         |esac
         |""".stripMargin
    val kubectl = dir.resolve("kubectl")
    Files.writeString(kubectl, script)
    Files.setPosixFilePermissions(kubectl, PosixFilePermissions.fromString("rwxr-xr-x"))
    dir

  /** The probe's exit status, with `sleep` stubbed out so the suite does not wait on it. */
  private def probe(stubDir: Path): Int =
    Process(
      Seq("bash", "-c", "source kustomization/netpol-probe.sh; sleep() { :; }; netpol_enforced"),
      repoRoot.toFile,
      "PATH" -> s"$stubDir:${sys.env.getOrElse("PATH", "")}"
    ).!(ProcessLogger(_ => ()))

  test("a cluster that enforces the policy passes") {
    assertEquals(probe(stub(enforces = true)), 0)
  }

  test("a cluster that stores the policy and ignores it is the one verdict that refuses") {
    assertEquals(probe(stub(enforces = false)), 1)
  }

  test("a network that connects nothing is a probe that could not run, not a verdict") {
    assertEquals(probe(stub(enforces = true, connects = false)), 2)
  }

  test("pods are created only once the namespace's service account exists") {
    // The first two asks for the account find nothing, as on a namespace created a moment ago; a
    // `run` before the account exists is refused by the API server, which the stub reproduces.
    val dir     = stub(enforces = true)
    val kubectl = dir.resolve("kubectl")
    val asked   = dir.resolve("asked")
    val script  = Files.readString(kubectl)
    Files.writeString(
      kubectl,
      script.replace(
        "case \"$*\" in",
        s"""case "$$*" in
           |  *" get serviceaccount default"*)
           |    n=$$(cat "$asked" 2>/dev/null || echo 0); echo $$((n + 1)) > "$asked"
           |    [[ $$n -ge 2 ]] && exit 0 || exit 1 ;;
           |  *" run "*) [[ $$(cat "$asked" 2>/dev/null || echo 0) -ge 3 ]] && exit 0 || exit 1 ;;""".stripMargin
      )
    )
    assertEquals(probe(dir), 0)
    assertEquals(Files.readString(asked).trim, "3", "the probe did not wait for the account")
  }
