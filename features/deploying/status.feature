Feature: The lifecycle of a deployed service
  The lifecycle of a deployed service says what its instances are doing now. It follows what happens
  in the cluster with no member doing anything, and a service that cannot be run says why.

  Scenario: a service whose only instance stops is unavailable until the instance is replaced
    Given a deployed service "cart" that is ready with 1 instance
    When the instance of "cart" stops
    Then the lifecycle of "cart" is "Unavailable"
    And the lifecycle of "cart" is "Ready" once the instance is replaced

  Scenario: an image that does not exist is reported as failed, with the reason
    Given no image "cart:missing" exists
    When a member applies a descriptor for the service "cart" with the image "cart:missing"
    Then the lifecycle of "cart" is "Failed"
    And the report names the image "cart:missing" and why it could not be run
