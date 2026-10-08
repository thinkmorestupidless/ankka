package digest

import com.thinkmorestupidless.ankka.agent.TestModelProvider
import com.thinkmorestupidless.ankka.testkit.{GherkinSuite, LogCapturing}

import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.Try

/**
 * `features/blueprints/research-digest.feature`, the platform's one scenario about this sample: the
 * sample's own features pass with nothing outside the test. It runs `ResearchDigestFeatures` as
 * munit would, scenario by scenario, and counts.
 */
class SampleFeaturesPassSuite
    extends GherkinSuite("../../features/blueprints/research-digest.feature")
    with LogCapturing:

  override val munitTimeout = 15.minutes

  private var suite: ResearchDigestFeatures                 = null
  private var outcomes: Vector[(String, Option[Throwable])] = Vector.empty

  Given(
    "the research digest sample with a scripted model, a scripted judgment provider and scripted sources"
  ) { () =>
    suite = new ResearchDigestFeatures
    // Scripted by construction: the model is the test provider, and every source is the suite's own.
    assert(suite.model.isInstanceOf[TestModelProvider])
    assertEquals(suite.sources.map(_.name), Vector("europepmc", "biorxiv", "openalex"))
  }

  When("the sample's features are run") { () =>
    suite.beforeAll()
    try
      outcomes = suite.munitTests().toVector.map { test =>
        suite.beforeEach(new BeforeEach(test))
        val outcome = Try(Await.result(test.body(), 5.minutes))
        suite.afterEach(new AfterEach(test))
        test.name -> outcome.failed.toOption
      }
    finally suite.afterAll()
  }

  Then("every one of them passes, with no model key and no network") { () =>
    assertEquals(outcomes.size, 6, outcomes.map(_._1).toString)
    val failed = outcomes.collect { case (name, Some(failure)) => s"$name: $failure" }
    assertEquals(failed, Vector.empty)
    // Every answer the scenarios needed came from the script, not a model.
    assert(suite.model.callCount > 0)
  }
