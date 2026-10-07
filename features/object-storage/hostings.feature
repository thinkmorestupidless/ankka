Feature: The variables of a bucket are for the developer's program
  A service that is not embedded has a program of the platform's beside the developer's. The
  variables that say where a bucket is and how to reach it are given to the developer's program
  and never to the platform's, which has no use for them. A module has no environment of its own
  and is told them when it asks.

  Scenario: the variables of a bucket are given to the process and not to the platform's own program
    Given a descriptor for a service "reports" with the hosting "process" that asks for a bucket
    When a member applies the descriptor
    Then the process of "reports" is given the variables "ANKKA_S3_ENDPOINT", "ANKKA_S3_REGION", "ANKKA_S3_BUCKET", "ANKKA_S3_ACCESS_KEY" and "ANKKA_S3_SECRET_KEY"
    And the platform's own program beside the process is given none of them

  Scenario: a module that asks for a variable of its bucket is told its value
    Given a deployed service "reports" written in "Rust" with a bucket
    When "reports" asks for the variable "ANKKA_S3_BUCKET"
    Then "reports" is told the name of its bucket
