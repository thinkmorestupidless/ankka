Feature: Reading how a copy stands in every language
  A handler in any language asks a replicated entity's copy at its installation how it stands, and is
  told what a Scala handler is told, from that copy's own journal: the home, the replication set, the
  installation a handover waiting goes to, the other home of a conflict, and the position its history
  reaches: how many records it holds, the platform's records of its home and its replication set
  among them. The read runs no handler of the entity's and records nothing. A runtime from before the
  read refuses it, and the handler is told which protocol version the read needs.

  Scenario Outline: a handler reads the home, the replication set and the position of a copy in every language
    Given a service "fleet" written in "<language>" with a replicated entity "vehicle" at the installation "leipzig"
    And the vehicle "v1" was created at "leipzig" and its history holds 4 records
    When a handler of "fleet" at "leipzig" reads how "v1" stands
    Then the handler is told the home "leipzig", the replication set "leipzig", and the position 4

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |
      | Java       |
      | Kotlin     |
      | Go         |

  Scenario: a handler reads a handover waiting
    Given a service "fleet" written in "Scala" with a replicated entity "vehicle" at the installations "leipzig" and "vienna"
    And a handover of the home of "v1" to "vienna" was asked for and has not completed
    When a handler of "fleet" at "leipzig" reads how "v1" stands
    Then the handler is told the handover waiting goes to "vienna"

  Scenario: a handler reads a conflict
    Given a service "fleet" written in "Scala" with a replicated entity "vehicle" at the installations "leipzig" and "vienna"
    And "v2" was created at "leipzig" and at "vienna", and the two histories met
    When a handler of "fleet" at "leipzig" reads how "v2" stands
    Then the handler is told "v2" is in conflict with the home "vienna"

  Scenario Outline: a handler reading how a copy of an entity that is not replicated stands is refused in every language
    Given a service "fleet" written in "<language>" with an entity "garage" that is not replicated
    When a handler of "fleet" reads how "g1" of "garage" stands
    Then the handler's read is refused as a Scala handler's is

    Examples:
      | language   |
      | Python     |
      | TypeScript |
      | Rust       |
      | Go         |

  Scenario: an installation outside the replication set answers with the copy it last held
    Given a service "fleet" written in "Scala" with a replicated entity "vehicle" at the installations "leipzig", "vienna" and "graz"
    And the vehicle "v1" was created at "leipzig" with "vienna" and "graz" in its replication set
    And "graz" was removed from the replication set of "v1"
    And a caller at "leipzig" sent two commands to "v1"
    When a handler of "fleet" at "graz" reads how "v1" stands
    Then the handler is told the position of the copy "graz" last held, behind the home's, and a replication set without "graz"

  Scenario Outline: the component test kit answers how a copy stands as it was told
    Given a component test of an endpoint written in "<language>" that reads how a copy of "vehicle" stands
    And the component test kit is told "v1" has the home "leipzig" and the position 3
    When the test calls the endpoint for "v1"
    Then the endpoint is told the home "leipzig" and the position 3

    Examples:
      | language   |
      | Python     |
      | TypeScript |
      | Rust       |
      | Go         |

  Scenario Outline: the component test kit answers a handover waiting and a conflict as it was told
    Given a component test of an endpoint written in "<language>" that reads how a copy of "vehicle" stands
    And the component test kit is told "v1" has a handover waiting to "vienna" and "v2" is in conflict with the home "vienna"
    When the test calls the endpoint for "v1" and for "v2"
    Then the endpoint is told the handover waiting goes to "vienna" for "v1" and the conflict with "vienna" for "v2"

    Examples:
      | language   |
      | Python     |
      | TypeScript |
      | Rust       |
      | Go         |

  Scenario: the Rust component test kit answers how a copy stands from the entity it runs
    Given a component test of an endpoint written in "Rust" that reads how a copy of "vehicle" stands
    And the component test kit holds "v1" with 3 events and was told nothing about it
    When the test calls the endpoint for "v1"
    Then the endpoint is told the position 3

  Scenario Outline: the test kit reads how a copy stands from the running service
    Given a test of a service "fleet" written in "<language>"
    And the vehicle "v1" was created with one event, so its history holds 3 records
    When the test reads how "v1" stands through the running service
    Then the test is told the position 3 and the home the test kit's installation

    Examples:
      | language   |
      | Python     |
      | TypeScript |
      | Rust       |
      | Go         |

  Scenario: a runtime from before the read refuses a module that reads how a copy stands
    Given a service "fleet" written in "Rust" whose handler reads how a copy stands
    When "fleet" is started beside a runtime at a protocol version before the copy read
    Then "fleet" does not start
    And the developer is told the module imports the copy read, which the runtime does not provide

  Scenario: a runtime from before the read refuses how a copy stands, naming the protocol version
    Given a service "fleet" written in "Python" whose handler reads how a copy stands
    And "fleet" runs beside a runtime at a protocol version before the copy read
    When the handler reads how a copy stands
    Then the handler's read is refused
    And the handler is told which protocol version the copy read needs
