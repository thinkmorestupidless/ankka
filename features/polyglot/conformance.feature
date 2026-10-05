Feature: Proving an SDK compatible
  The conformance suite is what compatible means. It drives a reference service through every
  conversation the protocol has, from the platform's side, and names each behaviour it checks. An
  SDK whose reference service passes it is compatible with every platform that speaks the same
  protocol version, and the platform never learns which language the SDK is written in. The Scala
  SDK passes the same suite in the platform's own program, so the suite defines the component model
  for every way a service is hosted.

  Scenario: the conformance suite names every behaviour it checks
    Given a reference service
    When the conformance suite runs against it
    Then every message of every conversation of the protocol has been exercised
    And each behaviour is reported by name as passing or failing

  Scenario Outline: the conformance suite passes against the reference service in every language
    Given the reference service written in "<language>"<shape>
    When the conformance suite runs against it
    Then every behaviour passes

    Examples:
      | language   | shape                                          |
      | Scala      |                                                |
      | Python     |                                                |
      | TypeScript |                                                |
      | Rust       | , with every component that has state stateless |
      | Rust       | , with every component that has state stateful  |

  Scenario Outline: a behaviour broken in an SDK is named and no other
    Given the reference service written in "<language>", with one behaviour broken
    When the conformance suite runs against it
    Then the suite reports that behaviour as failing
    And the suite reports every other behaviour as passing

    Examples:
      | language   |
      | Python     |
      | TypeScript |
      | Rust       |
