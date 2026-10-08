package digest.api

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.agent.AgentRuntime
import com.thinkmorestupidless.ankka.agent.blueprint.Period
import com.thinkmorestupidless.ankka.core.{Codecs, EntityId}
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import digest.application.PapersEntity
import digest.domain.Entry

import java.time.Instant

/** A run of a blueprint started by hand, over a period: the schedule starts its own. */
final case class StartRun(blueprint: String, from: String, to: String)

/** A run as a reader sees it: its status and each step's result, as JSON text. */
final case class RunView(
    runId: String,
    blueprint: String,
    version: Int,
    status: String,
    steps: Map[String, String]
)

/**
 * Reads the service's records and starts a run by hand. Starting a run returns at once; the run
 * goes on in the background and a reader polls it, as a run of several model calls should be read.
 */
final class DigestEndpoint(client: ComponentClient, agents: AgentRuntime)
    extends HttpEndpoint("/digest"):

  private given JsonValueCodec[Entry]    = Codecs.make[Entry]
  private given JsonValueCodec[StartRun] = Codecs.make[StartRun]
  private given JsonValueCodec[RunView]  = Codecs.make[RunView]

  val acl: Acl = Acl.AllowAll

  /** The entry for a paper, by its identifier. */
  get("/entries/{identifier}") { (identifier: String) =>
    client.forKeyValueEntity(EntityId(identifier)).call(PapersEntity.get).invoke()
  }

  /** Starts a run of `blueprint` covering `from` to `to` (ISO-8601 instants), under `runId`. */
  postBody("/runs/{runId}") { (runId: String, start: StartRun) =>
    val to     = Instant.parse(start.to)
    val period = Period(Instant.parse(start.from), to, Vector(to))
    agents.runs.start(start.blueprint, period.json, runId).runId
  }

  get("/runs/{runId}") { (runId: String) =>
    val run = agents.runs.get(runId)
    RunView(
      run.runId,
      run.blueprint,
      run.version,
      run.status.toString,
      run.steps.flatMap(s => s.result.map(r => s.name -> r.render)).toMap
    )
  }
