package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.api.{CleanupPolicy, RetentionTime}
import com.thinkmorestupidless.ankka.controlplane.tenancy.TopicPolicy
import com.typesafe.config.{Config, ConfigFactory}

/**
 * The installation's topic policy (feature 043): the shipped `reference.conf` is the shipped
 * policy, a key overrides it, and a value that is not its words, or a default outside its bound,
 * stops the control plane naming the key and the variable.
 */
class TopicPolicySuite extends munit.FunSuite:

  private def withTopics(settings: String): Config =
    ConfigFactory
      .parseString(s"ankka.controlplane.topics { $settings }")
      .withFallback(ConfigFactory.defaultReference())
      .resolve()

  test("the shipped reference.conf is the shipped policy") {
    assertEquals(TopicPolicy.from(ConfigFactory.defaultReference().resolve()), TopicPolicy.default)
  }

  test("a key overrides its default, as its variable does through the substitution") {
    val policy =
      TopicPolicy.from(withTopics("""default-retention = "14d", default-cleanup = "compact""""))
    assertEquals(policy.defaults.retention, RetentionTime.Bounded(14L * 86400000L))
    assertEquals(policy.defaults.cleanup, CleanupPolicy.Compact)
  }

  test("a value that is not its words stops the control plane, naming the key and the variable") {
    val e = intercept[IllegalArgumentException](
      TopicPolicy.from(withTopics("""default-retention = "a week""""))
    )
    assert(
      e.getMessage.startsWith(
        "ankka.controlplane.topics.default-retention (ANKKA_TOPIC_DEFAULT_RETENTION) is 'a week'"
      ),
      e.getMessage
    )
  }

  test("a default outside its bound stops the control plane, naming both") {
    val e = intercept[IllegalArgumentException](
      TopicPolicy.from(withTopics("default-copies = 5, most-copies = 3"))
    )
    assertEquals(
      e.getMessage,
      "ankka.controlplane.topics.default-copies (ANKKA_TOPIC_DEFAULT_COPIES) is outside " +
        "ankka.controlplane.topics.most-copies (ANKKA_TOPIC_MOST_COPIES): 5 is more than 3"
    )
    val longer = intercept[IllegalArgumentException](
      TopicPolicy.from(withTopics("""default-retention = "400d", longest-retention = "365d""""))
    )
    assert(longer.getMessage.contains("default-retention"), longer.getMessage)
  }

  test("copies of zero is refused as copies") {
    val e = intercept[IllegalArgumentException](TopicPolicy.from(withTopics("default-copies = 0")))
    assert(e.getMessage.contains("ANKKA_TOPIC_DEFAULT_COPIES"), e.getMessage)
  }
