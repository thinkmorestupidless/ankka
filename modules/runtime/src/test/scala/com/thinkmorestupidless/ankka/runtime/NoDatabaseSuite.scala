package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.ComponentKind
import com.typesafe.config.ConfigFactory

/**
 * features/deploying/process-resources.feature: a service with only consumers runs without a
 * database.
 */
class NoDatabaseSuite extends munit.FunSuite:

  test("the variable declares it, and nothing else does") {
    assert(NoDatabase.declared(ConfigFactory.parseString("ankka.database = none")))
    assert(!NoDatabase.declared(ConfigFactory.parseString("ankka.database = \"\"")))
    assert(!NoDatabase.declared(ConfigFactory.empty()))
  }

  test("a component that needs a database is named, and one that does not is not") {
    val descriptors = Vector(
      ComponentKind.EventSourcedEntity,
      ComponentKind.KeyValueEntity,
      ComponentKind.Workflow,
      ComponentKind.View,
      ComponentKind.TimedAction,
      ComponentKind.Consumer,
      ComponentKind.Endpoint,
      ComponentKind.Agent
    ).map(kind => Stub(kind))
    val problems = NoDatabase.problems(descriptors)
    assertEquals(problems.size, 5)
    assert(
      problems.head.startsWith(
        "event sourced entity 'stub' needs a database, and this service declares none"
      ),
      problems.head
    )
    assert(problems.forall(_.contains("ANKKA_DATABASE=none")))
  }

  private final case class Stub(kind: ComponentKind)
      extends com.thinkmorestupidless.ankka.core.ComponentDescriptor:
    val componentId = com.thinkmorestupidless.ankka.core.ComponentId("stub")
