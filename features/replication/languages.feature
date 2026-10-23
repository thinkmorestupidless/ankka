Feature: Replicated entities in every language
  An entity in Scala, Python, TypeScript or Rust is told the installation that wrote each event
  and whether this installation is the home; a consumer is told whether a change is local or
  mirrored. A process declaring replication beside a runtime from before replication is refused,
  naming the protocol version replication needs.

  Scenario Outline: an entity in every language is told where a change was written and whether it is at the home
    Given a service "fleet" written in "<language>" with a replicated entity "vehicle" at the installations "a" and "b"
    And the vehicle "v1" was created at "a" with "b" in its replication set
    When a caller at "a" sends the command "add-step" to "v1"
    Then the handler of "add-step" at "a" was told it is at the home of "v1"
    And the event handler of "vehicle" at "a" and at "b" was told the event was written at "a"

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |
      | Java       |
      | Kotlin     |

  Scenario: a process declaring replication beside an older runtime is refused naming the protocol version
    Given a service "fleet" written in "Python" whose entity "vehicle" declares replication
    And "fleet" runs beside a runtime at a protocol version before replication
    When the runtime reads what "fleet" declares
    Then "fleet" is refused
    And the service says why, naming the protocol version replication needs
