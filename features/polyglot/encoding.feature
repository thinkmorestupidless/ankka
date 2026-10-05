Feature: One encoding in every language
  Every value a service records or sends is written in the platform's one encoding, whatever language
  the service is written in, so that the events one service records are read by the same service
  written in any other language. The fixtures published with the protocol are the proof: an SDK that
  cannot read and write each one exactly is not compatible.

  Scenario Outline: every fixture is read and written again exactly by an SDK
    Given every fixture published with the protocol
    When the SDK for "<language>" reads each fixture and writes it again
    Then every value it read is the fixture's value
    And every byte it wrote is the fixture's byte
    And a fixture the SDK cannot read is a failure, not left out

    Examples:
      | language   |
      | Python     |
      | TypeScript |
      | Rust       |
