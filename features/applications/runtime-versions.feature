Feature: Which runtime versions a platform runs
  A service built with the platform's own code declares the runtime version it was built with, and
  a platform runs only the runtime versions it supports: the same major version, and a minor version
  equal to the platform's or one below. A service outside that is refused before any instance
  starts, and a service inside it hears nothing of versions.

  Scenario: the documentation states which runtime versions a platform runs and how to read it
    When a reader reads about upgrading a service
    Then the documentation states which runtime versions a platform of each version runs
    And it states the rule they are read by

  Scenario Outline: a platform runs a service whose runtime version it supports and says nothing of versions
    Given a platform at the version "<platform>"
    And a descriptor for a service "orders" that declares the runtime version "<runtime>"
    When a member applies the descriptor
    Then "orders" becomes ready
    And what the platform shows of "orders" says nothing of versions

    Examples:
      | platform | runtime |
      | 1.4.2    | 1.4.2   |
      | 1.4.2    | 1.4.0   |
      | 1.4.2    | 1.3.7   |

  Scenario Outline: a platform refuses to run a service whose runtime version it does not support
    Given a platform at the version "<platform>"
    And a descriptor for a service "orders" that declares the runtime version "<runtime>"
    When a member applies the descriptor
    Then no instance of "orders" starts
    And what the platform shows of "orders" names the runtime version "<runtime>" and the runtime versions the platform runs

    Examples:
      | platform | runtime |
      | 1.4.2    | 1.2.9   |
      | 1.4.2    | 1.5.0   |
      | 1.4.2    | 2.4.0   |
      | 1.4.2    | 0.4.2   |

  Scenario: a platform refuses a runtime version too old to hold a certificate, whatever its range
    Given a platform at the version "0.8.2"
    And a descriptor for a service "orders" that declares the runtime version "0.7.4"
    When a member applies the descriptor
    Then no instance of "orders" starts
    And what the platform shows of "orders" says that the runtime version "0.7.4" is too old to hold a certificate

  Scenario: a descriptor that declares no runtime version is not checked
    Given a descriptor for a service "orders" that declares no runtime version
    When a member applies the descriptor
    Then "orders" becomes ready
    And what the platform shows of "orders" says nothing of versions

  Scenario: a platform upgraded within a service's runtime versions keeps everything the service's database needs
    Given a deployed service "orders" whose runtime version the platform runs
    When the platform is upgraded to a version that still runs that runtime version
    Then everything the database of "orders" held is still there
    And "orders" goes on running
