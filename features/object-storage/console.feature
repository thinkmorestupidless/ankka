Feature: The console shows a service's object storage
  A member reads what a deployed service has for object storage where they read its database: the
  bucket the platform made, that the service has an object store of its own, or that it has none.

  Scenario Outline: the console shows what a service has for object storage beside its database
    Given a deployed service "reports" <state>
    When a member reads "reports" in the console
    Then the console shows <shown> beside the database of "reports"

    Examples:
      | state                            | shown                                           |
      | with a bucket                    | the name of the bucket of "reports"             |
      | with an object store of its own  | that "reports" has an object store of its own   |
      | that asked for no bucket         | that "reports" has no bucket                    |
