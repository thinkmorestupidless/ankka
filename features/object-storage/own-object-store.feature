Feature: A service with an object store of its own
  A descriptor that gives any variable whose name starts with "ANKKA_S3_" says that the service
  has an object store of its own. The platform makes it no bucket and no storage credential, and
  the service is given the variables as the descriptor gave them. A descriptor cannot both ask for
  a bucket and give such a variable.

  Scenario: a service with an object store of its own is given no bucket
    Given a descriptor for a service "reports" that gives the variables "ANKKA_S3_ENDPOINT", "ANKKA_S3_REGION", "ANKKA_S3_BUCKET", "ANKKA_S3_ACCESS_KEY" and "ANKKA_S3_SECRET_KEY"
    When a member applies the descriptor
    Then no bucket exists for "reports"
    And the platform makes no storage credential for "reports"
    And the status says that "reports" has an object store of its own

  Scenario Outline: a descriptor cannot both ask for a bucket and give a variable of an object store of its own
    Given a descriptor for a service "reports" that asks for a bucket and gives the variable "<variable>"
    When a member applies the descriptor
    Then the member is refused
    And the refusal says that a descriptor cannot both ask for a bucket and give the variable "<variable>"

    Examples:
      | variable             |
      | ANKKA_S3_ENDPOINT    |
      | ANKKA_S3_SECRET_KEY  |
      | ANKKA_S3_REGION      |
