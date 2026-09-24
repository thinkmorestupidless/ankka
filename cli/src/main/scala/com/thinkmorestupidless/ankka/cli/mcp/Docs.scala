package com.thinkmorestupidless.ankka.cli.mcp

import java.nio.charset.StandardCharsets

/**
 * The documentation of the ankka version this CLI was built from, carried inside it.
 *
 * The build copies every public page of `docs/` onto the CLI's classpath under `ankka/docs/`, with
 * an `index.txt` listing them, because a directory inside a jar cannot be listed. Carrying the
 * pages rather than fetching them means a model talking to `ankka mcp` reads the documentation that
 * matches the CLI it is driving, offline, and never a newer site describing commands this CLI does
 * not have.
 */
private[cli] object Docs:

  final case class Page(path: String, title: String, description: String, kind: String):
    def uri: String = s"ankka://docs/$path"

  private val Root = "ankka/docs/"

  private def resource(path: String): Option[String] =
    Option(getClass.getClassLoader.getResourceAsStream(Root + path)).map { stream =>
      try String(stream.readAllBytes(), StandardCharsets.UTF_8)
      finally stream.close()
    }

  lazy val pages: Vector[Page] =
    resource("index.txt").toVector
      .flatMap(_.linesIterator.map(_.trim).filter(_.nonEmpty))
      .flatMap { path =>
        read(path).map { text =>
          val meta = frontmatter(text)
          Page(
            path,
            meta.getOrElse("title", path),
            meta.getOrElse("description", ""),
            meta.getOrElse("kind", "")
          )
        }
      }

  def read(path: String): Option[String] =
    if path.contains("..") then None else resource(path)

  /**
   * `title`, `description` and `kind` from a page's frontmatter; the only fields a listing needs.
   */
  private def frontmatter(text: String): Map[String, String] =
    if !text.startsWith("---\n") then Map.empty
    else
      val end = text.indexOf("\n---\n", 4)
      if end < 0 then Map.empty
      else
        text
          .substring(4, end)
          .linesIterator
          .flatMap { line =>
            line.split(":", 2) match
              case Array(key, value) if !key.startsWith(" ") && !key.startsWith("-") =>
                Some(key.trim -> unquote(value.trim))
              case _ => None
          }
          .toMap

  private def unquote(value: String): String =
    val quoted = value.length >= 2 && Set('"', '\'').exists(q => value.head == q && value.last == q)
    if quoted then value.substring(1, value.length - 1) else value

  /**
   * Pages ranked by how many of the query's words appear in their title, description and body.
   *
   * Deliberately simple: the corpus is a few dozen pages with a one-sentence description each, and
   * a model can read the top few in full. A search that needed tuning would be a search that could
   * be wrong in ways nobody sees.
   */
  def search(query: String, limit: Int): Vector[Page] =
    val words = query.toLowerCase.split("[^a-z0-9_-]+").filter(_.length > 1).distinct
    if words.isEmpty then Vector.empty
    else
      pages
        .flatMap { page =>
          val head = (page.title + " " + page.description).toLowerCase
          val body = read(page.path).getOrElse("").toLowerCase
          val score = words.map { word =>
            (if head.contains(word) then 5 else 0) + (if body.contains(word) then 1 else 0)
          }.sum
          Option.when(score > 0)(page -> score)
        }
        .sortBy((page, score) => (-score, page.path))
        .take(limit)
        .map(_._1)
