Feature: A process is sized by its descriptor, and a stage needs no database
  A descriptor says what the process container gets, so a stage that does real work is not held at
  the platform's minimum, and a service with no entity, view or workflow is deployed without a
  database being provisioned for it.

  Scenario: a descriptor sizes the process container
    Given a descriptor hosted as a process asking for "1000m" and "1Gi" for the process
    When a member applies the descriptor
    Then the process is given "1000m" and "1Gi"

  Scenario: a descriptor that says nothing keeps the platform's size
    Given a descriptor hosted as a process that says nothing of the process's size
    When a member applies the descriptor
    Then the process is given the platform's size

  Scenario: a service with only consumers runs without a database
    Given a descriptor for a service "intake" with only consumers, asking for no database
    When a member applies the descriptor
    Then no database is provisioned for "intake"
    And "intake" is ready and its consumer reads its topic
