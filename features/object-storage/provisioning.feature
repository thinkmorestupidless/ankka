Feature: A bucket for a service that asks for one
  A descriptor may ask the platform for a bucket. The platform makes the service one bucket in
  the installation's object store and a storage credential that reaches it, and gives the
  developer's program the variables that say where the bucket is and how to reach it. The platform
  offers no way of its own to keep and read objects: the service uses whatever it would use with
  any object store. A service that asks for no bucket is given nothing.

  Scenario: a service that asks for a bucket is given one
    Given a descriptor for a service "reports" that asks for a bucket
    When a member applies the descriptor
    Then "reports" starts with the variables "ANKKA_S3_ENDPOINT", "ANKKA_S3_REGION", "ANKKA_S3_BUCKET", "ANKKA_S3_ACCESS_KEY" and "ANKKA_S3_SECRET_KEY" set
    And the variable "ANKKA_S3_BUCKET" names a bucket the platform made for "reports"

  Scenario: a service keeps an object in its bucket and reads it back
    Given a deployed service "reports" with a bucket
    When "reports" keeps the object "march.pdf" in its bucket with what its variables say
    Then "reports" reads the object "march.pdf" back from its bucket

  Scenario: the status of a service names its bucket
    Given a deployed service "reports" with a bucket
    When a member reads the status of "reports"
    Then the status says that "reports" has a bucket, and names it

  Scenario: a service that asks for no bucket is given none
    Given a descriptor for a service "cart" that asks for no bucket
    When a member applies the descriptor
    Then no bucket exists for "cart"
    And "cart" starts with no variable whose name starts with "ANKKA_S3_"
    And the status says that "cart" has no bucket

  Scenario: a service that asks for a bucket is not started when the installation has no object store
    Given an installation with no object store
    And a descriptor for a service "reports" that asks for a bucket
    When a member applies the descriptor
    Then no instance of "reports" starts
    And the status says that "reports" has no bucket because the installation has no object store

  Scenario: a bucket the object store cannot make yet is reported as still being made
    Given an installation whose object store cannot be reached at present
    And a descriptor for a service "reports" that asks for a bucket
    When a member applies the descriptor
    Then the status says that the bucket of "reports" is still being made
    And the status of "reports" is not "Failed"

  Scenario: a descriptor that asks for a bucket is refused when the service's name cannot name one
    Given a descriptor that asks for a bucket for a service whose name is longer than a bucket's name may be
    When a member applies the descriptor
    Then the member is refused
    And the refusal names the limit
