import java.util.regex.{Matcher, Pattern}

/**
 * `docs/release-patterns.txt`, as the CLI's build reads it: every `release` form a page names a
 * release of ankka in, rewritten to the release the CLI is built for, so the pages `ankka mcp`
 * serves name the release of the command line that carries them. The docs tool (`tools/docs`,
 * `release.py`) reads the same file with the same rule for the site; `DocsBundleSuite` holds the
 * bundled pages to it.
 */
object ReleasePatterns {

  // Three numbers that are not part of a longer run of them, nor the start of a pre-release or build.
  private val Numbers = """(\d+\.\d+\.\d+)(?!\.?\d)(?![-+]\w)"""

  def read(file: java.io.File): Seq[Pattern] =
    sbt.IO.readLines(file).flatMap { line =>
      if line.trim.isEmpty || line.trim.startsWith("#") || !line.startsWith("release ") then None
      else {
        val literal = line.stripPrefix("release ")
        val parts   = literal.split(Pattern.quote("{version}"), -1)
        if parts.length != 2 then sys.error(s"$file: a release line holds {version} once: $line")
        Some(Pattern.compile(Pattern.quote(parts(0)) + Numbers + Pattern.quote(parts(1))))
      }
    }

  def rewrite(text: String, version: String, patterns: Seq[Pattern]): String =
    patterns.foldLeft(text) { (current, pattern) =>
      val matcher = pattern.matcher(current)
      val out     = new StringBuffer
      while matcher.find() do {
        val whole = matcher.group(0)
        val from  = matcher.start(1) - matcher.start(0)
        val to    = matcher.end(1) - matcher.start(0)
        matcher.appendReplacement(
          out,
          Matcher.quoteReplacement(whole.substring(0, from) + version + whole.substring(to))
        )
      }
      matcher.appendTail(out)
      out.toString
    }

  /** The CLI's documentation release: its version on a release, else the last release before it. */
  def release(version: String, snapshot: Boolean, previous: Option[String]): String =
    if !snapshot && version.matches("""\d+\.\d+\.\d+""") then version
    else previous.getOrElse("0.0.0")
}
