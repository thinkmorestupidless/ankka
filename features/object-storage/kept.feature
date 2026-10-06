Feature: A bucket outlives its service
  The platform never deletes a bucket or an object. Deleting a service leaves its bucket as it
  was, and a service applied again under the same name is given the same bucket, which its status
  reports as recovered.

  Scenario: deleting a service keeps its bucket and its objects
    Given a deployed service "reports" that has kept the object "march.pdf" in its bucket
    When a member deletes "reports"
    Then the object store still holds the bucket of "reports" with the object "march.pdf"

  Scenario: a service deleted and deployed again reads the objects it kept before
    Given a deployed service "reports" that has kept the object "march.pdf" in its bucket
    And "reports" has since been deleted
    When a member applies the descriptor for "reports" again
    Then "reports" reads the object "march.pdf" back from its bucket
    And the status says that the bucket of "reports" was recovered
