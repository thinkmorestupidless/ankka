Feature: Releasing the SDKs
  A release of the platform publishes each SDK to its language's package registry at the release's
  version, so that a developer installs the SDK at the version of the platform they deploy to, and
  the version the SDK reports is the version on the registry. One package registry publishes
  nothing itself: it serves an SDK from a repository of that SDK's own, which the release produces
  from the platform's code and tags at the release's version.

  Scenario Outline: a release publishes an SDK at its version after the platform's own
    Given a release "1.4.0" of the platform with the SDK for "<language>"
    When the release is published
    Then the SDK for "<language>" is published to the package registry at the version "1.4.0"
    And it is published only after the platform's own release is
    And no credential to publish it is kept with the platform's code

    Examples:
      | language   |
      | Python     |
      | TypeScript |
      | Rust       |
      | Go         |

  Scenario Outline: a change to the protocol that an SDK's copy does not have fails the build
    Given a change to the protocol
    And the copy of the protocol the SDK for "<language>" holds without that change
    When the platform's code is built
    Then the build fails, naming the SDK for "<language>"

    Examples:
      | language   |
      | Python     |
      | TypeScript |
      | Rust       |
      | Go         |

  Scenario: an SDK produced into a repository of its own is served by the package registry at the release's version
    Given the SDK for "Go" published by the release "1.4.0" of the platform
    When a developer fetches the SDK for "Go" from the package registry at the version "1.4.0"
    Then the SDK fetched is the one the release produced into a repository of its own, tagged "1.4.0" and never edited there
    And a service built on it reports the SDK for "Go" at the version "1.4.0"
