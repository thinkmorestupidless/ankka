package digest.application

import com.thinkmorestupidless.ankka.agent.Json
import digest.domain.Paper

import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.{URI, URLEncoder}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.{Duration, LocalDate}

/**
 * Where the watch searches for papers: a literature index or preprint server, over its public
 * interface.
 */
trait Source:
  def name: String

  /** The papers on `query` published between the dates. A source that cannot be reached throws. */
  def search(query: String, from: LocalDate, to: LocalDate): Vector[Paper]

object Source:

  /**
   * The identifier a paper is known by across sources: its DOI, lower-cased, without a resolver.
   */
  def doi(raw: String): String =
    raw.trim.toLowerCase
      .stripPrefix("https://doi.org/")
      .stripPrefix("http://doi.org/")
      .stripPrefix("doi:")

  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  private def getJson(url: String): Json =
    val request = HttpRequest
      .newBuilder(URI.create(url))
      .timeout(Duration.ofSeconds(30))
      .header("Accept", "application/json")
      .GET()
      .build()
    val response = http.send(request, HttpResponse.BodyHandlers.ofString())
    if response.statusCode() / 100 != 2 then
      throw IllegalStateException(s"$url answered ${response.statusCode()}")
    Json
      .parse(response.body())
      .fold(e => throw IllegalStateException(s"$url did not answer JSON: $e"), identity)

  private def encode(text: String): String = URLEncoder.encode(text, UTF_8)

  private def string(json: Json, field: String): Option[String] = json(field).flatMap(_.asString)

  /** Europe PMC's REST search, by first publication date. */
  val europePmc: Source = new Source:
    def name = "europepmc"
    def search(query: String, from: LocalDate, to: LocalDate): Vector[Paper] =
      val q = s"($query) AND FIRST_PDATE:[$from TO $to]"
      val json = getJson(
        s"https://www.ebi.ac.uk/europepmc/webservices/rest/search?query=${encode(q)}&format=json&pageSize=50"
      )
      json("resultList").flatMap(_("result")).flatMap(_.asArray).getOrElse(Vector.empty).flatMap {
        r =>
          val id = string(r, "doi").map(doi).orElse(string(r, "id").map(i => s"europepmc:$i"))
          id.map(Paper(_, string(r, "title").getOrElse("")))
      }

  /**
   * bioRxiv's details listing for a span of dates, kept to the titles that carry the query's words.
   */
  val bioRxiv: Source = new Source:
    def name = "biorxiv"
    def search(query: String, from: LocalDate, to: LocalDate): Vector[Paper] =
      val json  = getJson(s"https://api.biorxiv.org/details/biorxiv/$from/$to/0")
      val words = query.toLowerCase.split("\\s+").filter(_.nonEmpty)
      json("collection").flatMap(_.asArray).getOrElse(Vector.empty).flatMap { r =>
        for
          d <- string(r, "doi")
          t <- string(r, "title")
          if words.forall(t.toLowerCase.contains)
        yield Paper(doi(d), t)
      }

  /** OpenAlex's works search, by publication date. */
  val openAlex: Source = new Source:
    def name = "openalex"
    def search(query: String, from: LocalDate, to: LocalDate): Vector[Paper] =
      val json = getJson(
        s"https://api.openalex.org/works?search=${encode(query)}&filter=from_publication_date:$from,to_publication_date:$to&per-page=50"
      )
      json("results").flatMap(_.asArray).getOrElse(Vector.empty).flatMap { r =>
        val id = string(r, "doi")
          .map(doi)
          .orElse(string(r, "id").map(i => s"openalex:${i.stripPrefix("https://openalex.org/")}"))
        id.map(Paper(_, string(r, "display_name").getOrElse("")))
      }

  val all: Vector[Source] = Vector(europePmc, bioRxiv, openAlex)
