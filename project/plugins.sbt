addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.11.7")
addSbtPlugin("org.scalameta"  % "sbt-scalafmt"        % "2.6.2")
// Releases: sbt-ci-release bundles sbt-dynver (the version comes from the git tag, never a file),
// sbt-pgp (signing) and sbt-sonatype (the Central Portal). `sbt ci-release` on a tag publishes the
// six application-facing modules; every other project carries `publish / skip`.
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.12.1")
// The platform's version as a value in code (nakka.core.BuildInfo.version), so the CLI can print it,
// the control plane can compare an application's declared runtime against it, and the runtime can
// log and serve it.
addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.13.2")
