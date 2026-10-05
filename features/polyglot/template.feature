Feature: Starting a service in another language from the template
  A developer starts a service in Python, TypeScript or Rust from the template the platform gives,
  and has a service that passes its tests and runs on their machine beside the platform's own
  program of the same version as the template, with nothing written by hand.

  Scenario Outline: a service started from the template passes its tests with none left out
    Given a developer with nothing written
    When the developer starts a service "shop" written in "<language>" from the template
    Then every test of "shop" passes and none is left out
    And the SDK "shop" is built with is at the template's version

    Examples:
      | language   |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: a service started from the template runs beside the platform's own program of the template's version
    Given a service "shop" written in "<language>" started from the template
    When the developer runs "shop" on their own machine
    Then "shop" runs with the platform's own program at the template's version
    And a command sent to an entity of "shop" is answered

    Examples:
      | language   |
      | Python     |
      | TypeScript |
      | Rust       |
