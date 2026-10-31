package com.thinkmorestupidless.ankka.testkit

import java.nio.file.{Files, Path, StandardCopyOption}

/**
 * What the published documentation, the design skill and the project template say about projects of
 * several services, held to their text: one test per scenario of
 * `features/projects/designing.feature` (the design skill's rules), of
 * `features/documentation/projects.feature`, and of `features/projects/template.feature` and
 * `features/projects/releases.feature` that a read of the template's files can prove.
 *
 * The behavioural scenarios, what a project's build, tests, tags and image builds actually do, are
 * `cli`'s `ProjectTemplateSuite`, which renders a project and runs it. Here the change script is
 * run as bash over a temporary repository, as `ActionSuite` runs the action's steps, and the
 * workflows are read for the shape that makes a tag release one service.
 */
class ProjectsDocumentationSuite extends munit.FunSuite:

  private lazy val root: Path =
    var dir = Path.of("").toAbsolutePath
    while !Files.isDirectory(dir.resolve("docs/reference")) do dir = dir.getParent
    dir

  private def read(path: String): String = Files.readString(root.resolve(path))

  private def page(path: String): String = read(s"docs/$path").replaceAll("\\s+", " ")

  private val skillPath     = "tools/docs/skill/ankka-design/SKILL.md"
  private def skill: String = read(skillPath).replaceAll("\\s+", " ")

  private def says(name: String, text: String, statements: String*): Unit =
    for statement <- statements do
      assert(text.contains(statement), s"$name does not say: $statement")

  private val template = "cli/src/main/templates/project"

  // ── features/projects/designing.feature ──────────────────────────────────────────────────────

  test(
    "a design of several services starts with a context map that names each service, what it owns and why its boundary is there"
  ) {
    val text   = skill
    val decide = text.indexOf("## One service, or several")
    assert(decide >= 0, "the design skill has no section deciding one service or several")
    assert(
      decide < text.indexOf("## The component table"),
      "the decision comes after the component table"
    )
    says(
      skillPath,
      text,
      "Decide this first, before any component table",
      "write the context map before designing any of them",
      "**Every service**, with what it owns",
      "the criterion (`ownership`, `scaling` or `consistency`) and why"
    )
  }

  test(
    "every crossing of a boundary in a context map is a declared topic with its contract or an endpoint route, and names who calls whom"
  ) {
    says(
      skillPath,
      skill,
      "**Every crossing**, and nothing else",
      "a declared topic with the contract it carries and the path of that contract's schema",
      "a route of an endpoint of one service, with the service that calls it"
    )
  }

  test(
    "a crossing that is neither a declared topic with its contract nor a route is named as a defect"
  ) {
    says(
      skillPath,
      skill,
      "**Anything else that passes a boundary is a defect, not a crossing.**",
      "A service reading another's database, the events of another's entities, or a topic that carries no contract",
      "do not record it in the map, and name it in the design as a defect to remove"
    )
  }

  test("two services that must be released together are designed as one service") {
    says(
      skillPath,
      skill,
      "**Two services that must be released together are one service.**",
      "write in the design that they are one service because they must be consistent together"
    )
  }

  test("a cycle of calls between services is named and one direction becomes a declared topic") {
    says(
      skillPath,
      skill,
      "**A cycle of calls is named.**",
      "ask which of the two calls becomes a declared topic"
    )
  }

  test("a design that needs no boundary stays one service and has no context map") {
    says(skillPath, skill, "When nothing differs, the design is one service: it has no context map")
  }

  test("the concept page is in the navigation and in the design skill's pages") {
    val mkdocs = read("mkdocs.yml")
    assert(mkdocs.contains("concepts/projects.md"), "concepts/projects.md is not in the nav")
    assert(mkdocs.contains("build/projects.md"), "build/projects.md is not in the nav")
    val pages = read(skillPath).linesIterator
      .dropWhile(_ != "pages:")
      .drop(1)
      .takeWhile(_.startsWith("  - "))
      .toVector
    assert(pages.exists(_.contains("concepts/projects.md")), pages.mkString("\n"))
  }

  // ── features/documentation/projects.feature ──────────────────────────────────────────────────

  test(
    "the documentation says that services which must be deployed together are one service, and that the platform deploys each service on its own"
  ) {
    says(
      "concepts/projects.md",
      page("concepts/projects.md"),
      "The platform deploys each service on its own and never several as one. This is deliberate.",
      "So services that must be deployed together are one service."
    )
    says(
      "concepts/designing-services.md",
      page("concepts/designing-services.md"),
      "**Services that must be deployed together are one service.**"
    )
    says(
      "reference/limitations.md",
      page("reference/limitations.md"),
      "**Each service is deployed on its own, and never several as one.**",
      "deliberately: services that must be deployed together are one service"
    )
  }

  test(
    "the documentation recommends one repository per project, says why it does not tie deployments together, and says when a service moves to a repository of its own"
  ) {
    says(
      "concepts/projects.md",
      page("concepts/projects.md"),
      "Keep a project in one repository: every service, the context map, the contracts, and the specs",
      "One repository does not tie the services' deployments together.",
      "A service tag builds that service's image and deploys that service alone",
      "A service moves to a repository of its own when its owner or its toolchain differs",
      "What stays in the project's repository is the context map, the contracts and the specs"
    )
    says(
      "build/projects.md",
      page("build/projects.md"),
      "A service tag releases one service.",
      "builds and deploys nothing of any other service"
    )
    says("deploy/ci.md", page("deploy/ci.md"), "No other service is built or deployed.")
  }

  test(
    "the documentation says how to run every service of a project on one machine and how they find each other"
  ) {
    says(
      "build/projects.md",
      page("build/projects.md"),
      "## Run every service on one machine",
      "scripts/run.sh orders",
      "a call to another service by name reaches the address that service announced",
      "services find each other whether or not `ankka local console` is running"
    )
  }

  // ── features/projects/template.feature and releases.feature, as far as a read proves them ───

  private def deploy = read(s"$template/.github/workflows/deploy.yml")

  test(
    "a service tag naming one service builds and deploys that service alone, at the service tag's version"
  ) {
    val text = deploy
    assert(text.contains("""tags: ["*/v*"]"""), "deploy.yml does not run on service tags alone")
    assert(text.contains("""service="${TAG%%/v*}""""), "the service is not read from the tag")
    assert(text.contains("""version="${TAG#*/v}""""), "the version is not read from the tag")
    assert(
      text.contains(
        """ankka services deploy "$SERVICE" "$IMAGE" -f "services/$SERVICE/service.json""""
      ),
      "the deploy does not name the one service"
    )
  }

  test("a service tag naming no service of the project releases nothing and says why") {
    val text = deploy
    assert(text.contains("""if [ ! -f "services/$service/service.json" ]; then"""))
    assert(text.contains("::error::{{project}} has no service $service"))
  }

  test("a service tag at a commit where the service's tests fail deploys nothing of any service") {
    val text = deploy
    assert(
      text.contains("uses: ./.github/workflows/service-checks.yml"),
      "the checks are not the reusable ones"
    )
    val jobs = text.linesIterator
      .dropWhile(_ != "  deploy:")
      .takeWhile(l => !l.startsWith("    steps:"))
      .mkString("\n")
    assert(
      jobs.contains("needs: [resolve, checks]"),
      s"the deploy job does not wait for the checks:\n$jobs"
    )
    val checks = read(s"$template/.github/workflows/service-checks.yml")
    assert(checks.contains("workflow_call:"), "service-checks.yml is not reusable")
  }

  test(
    "each service's version comes from its own service tags, whatever the other services were last released at"
  ) {
    val plugin = read(s"$template/project/Services.scala")
    assert(plugin.contains("--match"), "the version is not read from the service's own tags")
    assert(plugin.contains("""s"$name/v*""""), "the tags matched are not the service's")
  }

  test("ci.yml names no secret, so a pull request is checked with none") {
    assert(!read(s"$template/.github/workflows/ci.yml").contains("secrets."))
  }

  /**
   * Runs the change script over a temporary repository of three services: two Scala, one Python.
   */
  private def changes(edits: String*): String =
    val repo = Files.createTempDirectory("changed-services")
    Files.createDirectories(repo.resolve("scripts"))
    for script <- Vector("changed-services.sh", "service-language.sh") do
      val to = repo.resolve(s"scripts/$script")
      Files.copy(
        root.resolve(s"$template/scripts/$script"),
        to,
        StandardCopyOption.REPLACE_EXISTING
      )
      to.toFile.setExecutable(true): Unit
    def write(path: String) =
      val file = repo.resolve(path)
      Files.createDirectories(file.getParent)
      Files.writeString(
        file,
        s"$path\n",
        java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.APPEND
      ): Unit
    for s <- Vector("orders", "billing") do
      write(s"services/$s/service.json")
      write(s"services/$s/src/main/scala/Main.scala")
    write("services/stock/service.json")
    write("services/stock/pyproject.toml")
    write("shared/src/main/scala/Contract.scala")
    write("README.md")
    def git(args: String*): String =
      val p = new ProcessBuilder(
        ("git" +: "-c" +: "user.email=t@example.com" +: "-c" +: "user.name=t" +: args)*
      )
        .directory(repo.toFile)
        .redirectErrorStream(true)
        .start()
      val out = String(p.getInputStream.readAllBytes()).trim
      assertEquals(p.waitFor(), 0, out)
      out
    git("init", "-q")
    git("add", "-A")
    git("commit", "-qm", "start")
    val base = git("rev-parse", "HEAD")
    edits.foreach(write)
    git("add", "-A")
    git("commit", "-qm", "change")
    val p = new ProcessBuilder("bash", "scripts/changed-services.sh", base, "HEAD")
      .directory(repo.toFile)
      .redirectErrorStream(true)
      .start()
    val out = String(p.getInputStream.readAllBytes()).trim
    assertEquals(p.waitFor(), 0, out)
    out

  test("a change to one service runs that service's checks and no other's") {
    assertEquals(
      changes("services/orders/src/main/scala/Main.scala"),
      """{"services":[{"service":"orders","language":"scala"}],"tests":true}"""
    )
    assertEquals(
      changes("services/stock/pyproject.toml"),
      """{"services":[{"service":"stock","language":"python"}],"tests":false}"""
    )
    assertEquals(changes("README.md"), """{"services":[],"tests":false}""")
  }

  test("a change to the shared module runs the checks of every service that uses it") {
    assertEquals(
      changes("shared/src/main/scala/Contract.scala"),
      """{"services":[{"service":"billing","language":"scala"},{"service":"orders","language":"scala"}],"tests":true}"""
    )
  }

  test(
    "a service added inside a project is found by its directory: the build, the checks and the release name no service"
  ) {
    val build = read(s"$template/build.sbt") + read(s"$template/project/Services.scala")
    // What runs, not what the comments say: a comment may name the sample service as an example.
    val ci = (read(s"$template/.github/workflows/ci.yml") + deploy).linesIterator
      .filterNot(_.trim.startsWith("#"))
      .mkString("\n")
    for name <- Vector("{{first}}", "{{second}}") do
      assert(
        !build.contains(s"project(\"$name\")") && !build.contains(s"lazy val $name"),
        s"the build names $name"
      )
      assert(!ci.contains(s"$name/"), s"a workflow names $name")
    assert(
      read(s"$template/project/Services.scala").contains("\"services\""),
      "the plugin does not look under services/"
    )
    val languages = read(s"$template/.github/workflows/service-checks.yml")
    for language <- Vector("scala", "python", "typescript", "go", "rust", "java", "kotlin", "web")
    do assert(languages.contains(s"'$language'"), s"service-checks.yml has no checks for $language")
  }
