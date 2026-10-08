package digest.application

import com.thinkmorestupidless.ankka.agent.{FunctionTool, Json}
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import digest.domain.Paper

import java.time.{Clock, Instant, LocalDate}

/**
 * The tools the blueprints' workers name, over the service's records: a search per source, keeping
 * what a source found, and reading what was first found in a period. The blueprints compose through
 * these records and never call each other.
 */
final class Tools(client: ComponentClient, views: ViewClient, clock: Clock):

  /** `search_<source>`: the papers a source found for a query in a span of dates, as JSON. */
  def search(source: Source): FunctionTool =
    FunctionTool
      .named(s"search_${source.name}")
      .describedAs(
        s"Searches ${source.name} for papers on a query published between two dates (yyyy-MM-dd). " +
          "Answers with a JSON list of {identifier, title}."
      )
      .param[String]("query", "What to search for.")
      .param[String]("from", "The first publication date, yyyy-MM-dd.")
      .param[String]("to", "The last publication date, yyyy-MM-dd.")
      .handle { (query: String, from: String, to: String) =>
        Tools.papersJson(source.search(query, LocalDate.parse(from), LocalDate.parse(to))).render
      }

  /** Keeps what one source found: an entry per paper, by identifier, found now. */
  val keepPapers: FunctionTool =
    FunctionTool
      .named("keep_papers")
      .describedAs(
        "Keeps the papers a source found: one entry per paper by its identifier, however many " +
          "sources find it. `papers` is a JSON list of {identifier, title}."
      )
      .param[String]("source", "The source that found them.")
      .param[String]("papers", "The papers, as a JSON list of {identifier, title}.")
      .handle { (source: String, papers: String) =>
        val found = Tools.papersOf(Json.parse(papers).getOrElse(Json.Null))
        val now   = clock.instant().toEpochMilli
        found.foreach { paper =>
          client
            .forKeyValueEntity(EntityId(paper.identifier))
            .call(PapersEntity.keep)
            .invoke(PapersEntity.Keep(source, paper.title, now)): Unit
        }
        s"kept ${found.size} papers from $source"
      }

  /** The papers first found in a span of instants, as JSON: what a digest's period holds. */
  val papersFoundBetween: FunctionTool =
    FunctionTool
      .named("papers_found_between")
      .describedAs(
        "The papers first found from one instant up to another (ISO-8601), as a JSON list of {identifier, title}."
      )
      .param[String]("from", "The start of the span, ISO-8601.")
      .param[String]("to", "The end of the span, ISO-8601, not included.")
      .handle { (from: String, to: String) =>
        val entries = EntriesView.foundBetween(views, Instant.parse(from), Instant.parse(to))
        Tools.papersJson(entries.map(e => Paper(e.identifier, e.title))).render
      }

  def all(sources: Vector[Source]): Vector[FunctionTool] =
    sources.map(search) :+ keepPapers :+ papersFoundBetween

object Tools:
  def papersJson(papers: Vector[Paper]): Json =
    Json.arr(
      papers.map(p =>
        Json.obj("identifier" -> Json.str(p.identifier), "title" -> Json.str(p.title))
      )*
    )

  def papersOf(json: Json): Vector[Paper] =
    json.asArray.getOrElse(Vector.empty).flatMap { p =>
      p("identifier")
        .flatMap(_.asString)
        .map(id => Paper(id, p("title").flatMap(_.asString).getOrElse("")))
    }
