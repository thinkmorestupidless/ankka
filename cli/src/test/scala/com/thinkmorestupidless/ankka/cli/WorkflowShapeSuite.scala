package com.thinkmorestupidless.ankka.cli

import java.nio.file.Files

/**
 * The two workflows that publish and prove the platform's images for both architectures, pinned to
 * the shape the images need (feature 049), as `HomebrewFormulaSuite` pins the formula's.
 *
 * Nothing before a tag runs the release workflow, and nothing before the night runs the cluster
 * one, so a step quietly lost in an edit — the QEMU setup, the platform check, the arm64 leg —
 * would be found by a release that published one architecture or a night that proved one. This
 * reads the files and the matrix script as text and asserts the lines the feature relies on are
 * there, by their `uses:` names and the literal platforms both workflows must carry.
 */
class WorkflowShapeSuite extends munit.FunSuite:

  private val root = CliReferenceSuite.repoRoot

  private lazy val release = Files.readString(root.resolve(".github/workflows/release.yml"))
  private lazy val cluster = Files.readString(root.resolve(".github/workflows/cluster.yml"))
  private lazy val build   = Files.readString(root.resolve("build.sbt"))

  private val Platforms = Seq("linux/amd64", "linux/arm64")

  /** The `images` job of the release workflow, from its header to the next top-level job. */
  private lazy val imagesJob: String =
    val start = release.indexOf("\n  images:\n")
    assert(start >= 0, "release.yml has no `images` job")
    val rest = release.substring(start + 1)
    val next = "\n  [a-z-]+:\n".r.findFirstMatchIn(rest.substring(1)).map(_.start + 1)
    next.fold(rest)(rest.substring(0, _))

  test(
    "the images job builds both platforms: QEMU, a buildx builder, and the variable the build reads"
  ) {
    assert(imagesJob.contains("uses: docker/setup-qemu-action@"), imagesJob)
    assert(imagesJob.contains("uses: docker/setup-buildx-action@"), imagesJob)
    assert(
      imagesJob.contains(s"ANKKA_IMAGE_PLATFORMS: ${Platforms.mkString(",")}"),
      "the job must set ANKKA_IMAGE_PLATFORMS to exactly the two platforms"
    )
    // The two setups come before the sbt publish, or the publish builds one platform.
    val publish = imagesJob.indexOf("sbt operator/Docker/publish")
    assert(publish > 0, "no sbt publish of the images")
    assert(imagesJob.indexOf("setup-qemu-action") < publish, "QEMU is set up after the publish")
    assert(imagesJob.indexOf("setup-buildx-action") < publish, "buildx is set up after the publish")
  }

  test("the console is built by buildx for both platforms and pushed, under both tags") {
    val step = imagesJob.substring(imagesJob.indexOf("Publish the console image"))
    assert(
      step.contains("docker buildx build --platform \"$ANKKA_IMAGE_PLATFORMS\" --push"),
      step.linesIterator.take(8).mkString("\n")
    )
    assert(step.contains("ankka-console:$version"), step)
    assert(step.contains("ankka-console:latest"), step)
    assert(!imagesJob.contains("docker push --all-tags"), "a plain push publishes one platform")
  }

  test("the pullable check reads the manifest list back and refuses an image missing a platform") {
    val check = imagesJob.substring(imagesJob.indexOf("Every image is publicly pullable"))
    assert(
      check.contains("docker buildx imagetools inspect") && check.contains(".Manifest.Manifests"),
      "the check must read the registry's manifest list, not the build log"
    )
    assert(
      check.contains("PLATFORMS: linux/amd64 linux/arm64") || imagesJob.contains(
        "PLATFORMS: linux/amd64 linux/arm64"
      )
    )
    assert(check.contains("has no $want build"), check)
    assert(check.contains("exit 1"), "a missing platform must fail the job")
  }

  test("the cache is warmed once per platform") {
    val warm = imagesJob.substring(imagesJob.indexOf("Warm the clusters' cache"))
    assert(warm.contains("for platform in $PLATFORMS"), warm)
    assert(warm.contains("docker pull -q --platform \"$platform\""), warm)
  }

  test("the cluster workflow runs every suite on a runner of each architecture") {
    assert(
      cluster.contains("runs-on: ${{ matrix.runner }}"),
      "the leg's runner must come from the matrix"
    )
    assert(
      cluster.contains("name: ${{ matrix.name }} (${{ matrix.arch }})"),
      "a job is named by the matrix's name (the suite, or the suite and its shard) and architecture"
    )
    assert(cluster.contains("resources-${{ matrix.slug }}-${{ matrix.arch }}"), cluster)
    assert(cluster.contains("test-reports-${{ matrix.slug }}-${{ matrix.arch }}"), cluster)
    assert(cluster.contains("ARCH: ${{ inputs.arch }}"), "the arch input narrows the matrix")
    assert(
      cluster.contains("format('-Dankka.features.only={0}', matrix.only)"),
      "a shard's scenarios reach the suite as -Dankka.features.only"
    )
  }

  test("the matrix script lists each job once per architecture, with its runner") {
    val listed = matrix(Nil)
    val names  = listed.map(_("name")).distinct
    assert(names.nonEmpty, "no suites found")
    names.foreach { name =>
      val legs = listed.filter(_("name") == name).map(l => l("arch") -> l("runner")).sorted
      assertEquals(
        legs,
        Seq("amd64" -> "ubuntu-24.04", "arm64" -> "ubuntu-24.04-arm"),
        s"$name does not run once on each architecture"
      )
    }
    val one = matrix(Seq("SampleDeploymentClusterSuite", "arm64"))
    assertEquals(
      one.map(l => (l("suite"), l("name"), l("only"), l("arch"))),
      Seq(("SampleDeploymentClusterSuite", "SampleDeploymentClusterSuite", "", "arm64"))
    )
  }

  test("a sharded suite is several jobs, each held to its scenarios; an unsharded one is whole") {
    val listed = matrix(Seq("", "amd64"))
    val shards = listed.filter(_("suite") == "RestoringClusterFeatures")
    assert(shards.size > 1, "RestoringClusterFeatures is in SHARDS and must be several jobs")
    shards.foreach { shard =>
      assert(shard("only").nonEmpty, s"${shard("name")} selects no scenario")
      assert(shard("name") != shard("suite"), "a shard's job is named by its scenario")
      assertNotEquals(shard("slug"), shard("suite"), "a shard's artifacts are named by it")
    }
    assertEquals(shards.map(_("slug")).distinct.size, shards.size, "two shards share a slug")
    listed.filter(_("suite") == "SampleDeploymentClusterSuite").foreach { whole =>
      assertEquals(whole("only"), "", "a suite outside SHARDS runs every scenario")
      assertEquals(whole("name"), whole("suite"))
    }
  }

  test(
    "the local build reads the platforms from the one variable the release job sets, and nothing local names it"
  ) {
    assert(
      build.contains("sys.env\n    .get(\"ANKKA_IMAGE_PLATFORMS\")") || build.contains(
        "sys.env.get(\"ANKKA_IMAGE_PLATFORMS\")"
      ),
      "build.sbt must read ANKKA_IMAGE_PLATFORMS"
    )
    assertEquals(
      build.linesIterator.count(_.contains("dockerBuildxPlatforms :=")),
      1,
      "dockerBuildxPlatforms is set in exactly one place, from the variable"
    )
    Seq("kustomization/deploy-local.sh", "Justfile").foreach { file =>
      val text = Files.readString(root.resolve(file))
      assert(
        !text.contains("ANKKA_IMAGE_PLATFORMS"),
        s"$file must not set the platforms: a local build is the host's"
      )
    }
  }

  /** The matrix script's `include` list, each entry's string fields. */
  private def matrix(args: Seq[String]): Seq[Map[String, String]] =
    val process = new ProcessBuilder((Seq("python3", ".github/cluster-suites.py") ++ args)*)
      .directory(root.toFile)
      .redirectErrorStream(true)
      .start()
    val output = new String(process.getInputStream.readAllBytes())
    assertEquals(process.waitFor(), 0, output)
    // A small parse rather than a JSON library: the script prints one flat object of strings per leg.
    val Entry = "\\{([^{}]*)\\}".r
    val Field = "\"([a-z]+)\": \"([^\"]*)\"".r
    Entry
      .findAllMatchIn(output.substring(output.indexOf("[")))
      .map(m => Field.findAllMatchIn(m.group(1)).map(f => f.group(1) -> f.group(2)).toMap)
      .toSeq
