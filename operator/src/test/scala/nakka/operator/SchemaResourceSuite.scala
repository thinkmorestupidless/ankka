package nakka.operator

/**
 * The schema symlink survives packaging.
 *
 * `operator/src/main/resources/nakka/ddl` is a directory symlink to
 * `modules/runtime/src/main/resources/nakka/ddl`, the single canonical copy. This suite proves sbt
 * follows it during `Compile/copyResources` — a real risk, since a previous attempt at exactly this
 * (feature 001, the CRD/install manifests) got the relative path wrong on the first try and
 * silently produced no files on the classpath at all rather than an error.
 */
class SchemaResourceSuite extends munit.FunSuite:

  private val expectedFiles =
    Vector("10-journal-postgres.sql", "20-projection-postgres.sql", "30-timers-postgres.sql")

  test("every DDL file is readable as a classpath resource") {
    expectedFiles.foreach { name =>
      val stream = getClass.getResourceAsStream(s"/nakka/ddl/$name")
      assert(stream != null, s"$name was not found on the classpath — check the ddl symlink")
      stream.close()
    }
  }

  test("the journal DDL is non-empty and contains the expected table") {
    val content =
      scala.io.Source
        .fromInputStream(getClass.getResourceAsStream("/nakka/ddl/10-journal-postgres.sql"))
        .mkString
    assert(
      content.contains("event_journal"),
      "expected the canonical journal DDL, got something else"
    )
  }

  test("the timer DDL is idempotent, which is what makes running it on every start safe") {
    val content =
      scala.io.Source
        .fromInputStream(getClass.getResourceAsStream("/nakka/ddl/30-timers-postgres.sql"))
        .mkString
    assert(
      content.contains("IF NOT EXISTS"),
      "the schema-init container relies on this being idempotent"
    )
  }
