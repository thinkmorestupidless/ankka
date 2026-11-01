// {{project}}: a project of several ankka services, one repository.
//
// Each Scala service is a directory under services/ and is found there by project/Services.scala:
// nothing here names one, so adding a service is `ankka init <service>` and no edit of this file.
// The ankka version every service builds against is `Services.ankkaVersion`; each service.json
// declares the same as `runtime`. Change all of them together when upgrading.

ThisBuild / scalaVersion := "3.9.0"

// The types of the contracts that cross the services' boundaries, and the contracts themselves:
// contracts/*.json are put on its classpath under contracts/, so a contract is read from the same
// file the context map names and nothing is copied by hand.
lazy val shared = project
  .in(file("shared"))
  .settings(
    scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:all"),
    libraryDependencies += "com.thinkmorestupidless" %% "ankka-core" % Services.ankkaVersion,
    Compile / resourceGenerators += Def.task {
      val out = (Compile / resourceManaged).value / "contracts"
      ((ThisBuild / baseDirectory).value / "contracts" * "*.json").get.map { contract =>
        val copy = out / contract.getName
        IO.copyFile(contract, copy)
        copy
      }
    }.taskValue
  )

// Tests that cross services: each service under a test kit of its own, all on one broker in memory,
// and the context map held to what each service's components state. It depends on every Scala
// service the build discovered.
lazy val tests = project
  .in(file("tests"))
  .dependsOn(Services.names.map(name => ClasspathDependency(LocalProject(name), None)): _*)
  .settings(
    scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:all"),
    libraryDependencies ++= Seq(
      "com.thinkmorestupidless" %% "ankka-testkit" % Services.ankkaVersion % Test,
      "org.scalameta"           %% "munit"         % "1.3.6"               % Test
    ),
    Test / fork := true,
    // Where the project is, for the suites that read context-map.json.
    Test / javaOptions += s"-Dankka.project.root=${(ThisBuild / baseDirectory).value}",
    publish / skip := true
  )

lazy val root = project
  .in(file("."))
  .aggregate((Seq[ProjectReference](shared, tests) ++ Services.names.map(LocalProject(_))): _*)
  .settings(publish / skip := true)

// The platform's database schema, taken out of the ankka-runtime artifact into target/ddl, which
// scripts/databases.sh applies to each service's database. `sbt schema` after every ankka upgrade.
lazy val schema =
  taskKey[File]("Writes ankka's database schema from the runtime artifact into target/ddl")
schema := {
  val log = streams.value.log
  val jar = (tests / Test / dependencyClasspath).value
    .map(_.data)
    .find(f => f.getName.startsWith("ankka-runtime_") && f.getName.endsWith(".jar"))
    .getOrElse(
      sys.error("ankka-runtime is not on the classpath; is Services.ankkaVersion published?")
    )
  val out = target.value / "ddl"
  IO.delete(out)
  IO.createDirectory(out)
  IO.unzip(jar, out, (entry: String) => entry.startsWith("ankka/ddl/") && entry.endsWith(".sql"))
  val files = (out / "ankka" / "ddl").listFiles().toList.sortBy(_.getName)
  files.foreach(f => IO.move(f, out / f.getName))
  IO.delete(out / "ankka")
  log.info(s"schema: ${files.map(_.getName).mkString(", ")} -> $out")
  out
}
