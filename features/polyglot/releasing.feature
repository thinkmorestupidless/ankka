Feature: Releasing the SDKs
  A release of the platform publishes each SDK to its language's package registry at the release's
  version, so that a developer installs the SDK at the version of the platform they deploy to, and
  the version the SDK reports is the version on the registry.

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
