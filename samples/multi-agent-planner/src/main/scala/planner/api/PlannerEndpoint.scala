package planner.api

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.{Codecs, EntityId}
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.sdk.ComponentClient
import planner.application.{PlannerWorkflow, PreferencesEntity}
import planner.domain.{AgentSelection, Contribution, PlanState, Preferences}

/**
 * Drives the planner over HTTP.
 *
 * Starting a plan returns immediately: the workflow runs to completion in the background and the
 * client polls. That is not a shortcut around async — it is the honest shape for work that involves
 * several model calls, and it is why the workflow's state is queryable in the first place.
 */
final class PlannerEndpoint(client: ComponentClient) extends HttpEndpoint("/plans"):

  private given JsonValueCodec[Preferences]           = Codecs.make[Preferences]
  private given JsonValueCodec[PlanState]             = Codecs.make[PlanState]
  private given JsonValueCodec[AgentSelection]        = Codecs.make[AgentSelection]
  private given JsonValueCodec[Contribution]          = Codecs.make[Contribution]
  private given JsonValueCodec[PlannerWorkflow.Start] = Codecs.make[PlannerWorkflow.Start]

  val acl: Acl = Acl.AllowAll

  /** Stores a traveller's preferences, which the activity specialist reads. */
  putBody("/preferences/{userId}") { (userId: String, preferences: Preferences) =>
    client.forKeyValueEntity(EntityId(userId)).call(PreferencesEntity.set).invoke(preferences)
  }

  get("/preferences/{userId}") { (userId: String) =>
    client.forKeyValueEntity(EntityId(userId)).call(PreferencesEntity.get).invoke()
  }

  /**
   * Starts a plan. Returns 204; poll the plan for progress.
   *
   * The destination arrives in the body rather than the path: ankka's endpoint DSL does not expose
   * query parameters yet, and a free-text destination does not belong in a path segment.
   */
  postBody("/{planId}") { (planId: String, request: PlannerWorkflow.Start) =>
    client.forWorkflow(EntityId(planId)).call(PlannerWorkflow.start).invoke(request)
  }

  get("/{planId}") { (planId: String) =>
    client.forWorkflow(EntityId(planId)).call(PlannerWorkflow.plan).invoke()
  }

  // docs:start lifecycle
  /** The engine's own view: running, paused, completed or failed, and why. */
  get("/{planId}/lifecycle") { (planId: String) =>
    val lifecycle = client.forWorkflow(EntityId(planId)).lifecycle(PlannerWorkflow).invoke()
    s"${lifecycle.status}${lifecycle.failure.map(reason => s": $reason").getOrElse("")}"
  }
  // docs:end lifecycle
