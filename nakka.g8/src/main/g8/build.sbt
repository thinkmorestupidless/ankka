// $name;format="norm"$ — a nakka service.
//
// nakka's libraries come from Maven Central (or ~/.ivy2/local while the platform is being
// developed). The same version is declared in service.json as `runtime`: change both together
// when upgrading — see README.md, "Upgrading nakka".
val nakkaVersion = "$nakka_version$"

// The service name is also the image name, the descriptor's name, and — once exposed — the first
// label of the hostname, so it has to be one Kubernetes and DNS accept. Checked here so that a
// name the template's normalisation could not fix fails at the first `sbt` invocation, with the
// rule, rather than at `nakka services apply`.
val serviceName = "$name;format="norm"$"
// (Top-level statements in an sbt build must be settings or vals, hence the val.)
val serviceNameChecked: String = {
  val valid = serviceName.matches("[a-z]([-a-z0-9]{0,61}[a-z0-9])?")
  if (!valid)
    sys.error(
      s"'\$serviceName' is not a valid service name: lowercase letters, digits and hyphens, " +
        "starting with a letter, not ending with a hyphen, at most 63 characters"
    )
  serviceName
}

lazy val root = project
  .in(file("."))
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .settings(
    name         := serviceNameChecked,
    scalaVersion := "3.9.0",
    scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:all"),
    libraryDependencies ++= Seq(
      "com.thinkmorestupidless" %% "nakka-sdk"     % nakkaVersion,
      "com.thinkmorestupidless" %% "nakka-runtime" % nakkaVersion,
      "com.thinkmorestupidless" %% "nakka-http"    % nakkaVersion,
      // "com.thinkmorestupidless" %% "nakka-agent" % nakkaVersion,   // agents: uncomment
      "com.thinkmorestupidless" %% "nakka-testkit" % nakkaVersion % Test,
      "org.scalameta"           %% "munit"         % "1.3.6"      % Test
    ),
    // The test kit starts a throwaway Postgres in Docker; a forked JVM keeps its lifecycle out of
    // sbt's own process.
    Test / fork := true,

    // The image the descriptor names: <name>:<version> and <name>:latest, built into the local
    // Docker daemon by `sbt Docker/publishLocal`. No registry is assumed; `kind load docker-image`
    // puts it where a local cluster can see it.
    Docker / packageName := serviceName,
    dockerBaseImage      := "eclipse-temurin:21-jre",
    dockerUpdateLatest   := true,
    dockerExposedPorts   := Seq(9000),
    // A Docker tag may not contain '+'; a git-derived snapshot version might.
    Docker / version     := version.value.replace('+', '-')
  )

// The platform's database schema, taken out of the nakka-runtime artifact into target/ddl, where
// docker-compose.yml mounts it as Postgres' init directory. One copy of the schema — the
// runtime's — and nothing to keep in step: `sbt schema` after every nakka upgrade.
lazy val schema = taskKey[File]("Writes nakka's database schema from the runtime artifact into target/ddl")
schema := {
  val log = streams.value.log
  val jar = (Compile / dependencyClasspath).value
    .map(_.data)
    .find(f => f.getName.startsWith("nakka-runtime_") && f.getName.endsWith(".jar"))
    .getOrElse(sys.error("nakka-runtime is not on the classpath — is nakkaVersion published?"))
  val out = target.value / "ddl"
  IO.delete(out)
  IO.createDirectory(out)
  IO.unzip(jar, out, (entry: String) => entry.startsWith("nakka/ddl/") && entry.endsWith(".sql"))
  val files = (out / "nakka" / "ddl").listFiles().toList.sortBy(_.getName)
  files.foreach(f => IO.move(f, out / f.getName))
  IO.delete(out / "nakka")
  log.info(s"schema: \${files.map(_.getName).mkString(", ")} -> \$out")
  out
}
