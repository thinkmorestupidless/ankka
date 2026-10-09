package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.api.{
  CleanupPolicy,
  CreateProject,
  RetentionTime,
  Setting,
  TopicDefaults
}
import com.thinkmorestupidless.ankka.controlplane.application.{ProjectEntity, TopicSettingsSweep}
import com.thinkmorestupidless.ankka.controlplane.deploy.{DeployConfig, ServiceProjector}
import com.thinkmorestupidless.ankka.controlplane.domain.DeclareTopic
import com.thinkmorestupidless.ankka.controlplane.tenancy.TopicPolicy
import com.thinkmorestupidless.ankka.core.EntityId
import com.thinkmorestupidless.ankka.runtime.ProjectionRuntime
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}

import scala.concurrent.duration.DurationInt

/**
 * `features/broker/retention.feature`: a topic declared before a declaration could say its settings
 * is filled by the control plane when it is upgraded (feature 043, FR-002a). A declaration with no
 * settings is what a node from before the feature sends, so it stands in for the old journal.
 */
class TopicSettingsSweepSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private var testKit: AnkkaTestKit = null
  private val policy = TopicPolicy.default.copy(
    defaults = TopicDefaults.Shipped.copy(retention = RetentionTime.Bounded(14L * 86400000L))
  )

  override def beforeAll(): Unit =
    val projector = ServiceProjector.withClient(DeployConfig.default, new FakeAnkkaServiceClient)
    testKit = AnkkaTestKit.start(ControlPlane.componentsWith(projector), Seq(ProjectionRuntime()))

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  private def project(id: String) = testKit.componentClient.forEventSourcedEntity(EntityId(id))

  test(
    "topics declared before settings are filled once, by the platform, copies left as the broker's"
  ) {
    project("legacy").call(ProjectEntity.createProject).invoke(CreateProject("Legacy", "acme"))
    project("legacy").call(ProjectEntity.declareTopic).invoke(DeclareTopic("notices", Some(3)))
    project("legacy")
      .call(ProjectEntity.declareTopic)
      .invoke(DeclareTopic("deltas", Some(3), compacted = true))
    project("modern").call(ProjectEntity.createProject).invoke(CreateProject("Modern", "acme"))

    val sweep = TopicSettingsSweep(policy)
    // The listing is a projection: the sweep fills nothing until the project is listed, and once it
    // has filled the project once it never fills it again, so the first run that names it is the one.
    val filled = testKit.eventually("the sweep fills the legacy project") {
      Some(sweep.run(testKit.service)).filter(_.exists(_._1 == "legacy"))
    }
    assertEquals(filled, Vector("legacy" -> Vector("deltas", "notices")))

    val topics  = project("legacy").call(ProjectEntity.topics).invoke()
    val notices = topics("notices").settings.getOrElse(fail("notices was not filled"))
    assertEquals(notices.retention, RetentionTime.Bounded(14L * 86400000L))
    assertEquals(notices.cleanup, CleanupPolicy.Delete)
    assertEquals(notices.copies, None)
    assertEquals(notices.minInSync, None)
    assertEquals(
      topics("notices").defaulted,
      Setting.values.toSet - Setting.Copies - Setting.MinInSync
    )
    assertEquals(topics("deltas").settings.map(_.cleanup), Some(CleanupPolicy.Compact))

    val history = project("legacy").call(ProjectEntity.history).invoke()
    val fills   = history.filter(_.kind == "topic-filled")
    assertEquals(fills.map(_.topic).sorted, Vector("deltas", "notices"))
    assert(fills.forall(_.actor.isEmpty), "the platform filled them, not a member")

    // A second start finds nothing to fill and records nothing.
    assertEquals(sweep.run(testKit.service), Vector.empty)
    assertEquals(project("legacy").call(ProjectEntity.history).invoke(), history)
  }
