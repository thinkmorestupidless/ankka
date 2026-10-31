Feature: The documentation of projects of several services
  A reader learns from the documentation how to split an application into services, why a project
  keeps them in one repository, how each is still deployed on its own, and how to run them together
  on one machine.

  Scenario: the documentation says that services which must be deployed together are one service, and that the platform deploys each service on its own
    Given the published documentation
    When a reader reads how to decide where the boundaries between services go
    Then the documentation says that services which must be deployed together are one service
    And the documentation says that the platform deploys each service on its own and never several as one, and that this is deliberate

  Scenario: the documentation recommends one repository per project, says why it does not tie deployments together, and says when a service moves to a repository of its own
    Given the published documentation
    When a reader reads where the code of a project's services is kept
    Then the documentation recommends one repository holding every service of a project, its context map, its contracts and its specs
    And the documentation says that a service tag deploys one service alone
    And the documentation says that a service moves to a repository of its own when its owner or its toolchain differs, and what stays in the project's repository

  Scenario: the documentation says how to run every service of a project on one machine and how they find each other
    Given the published documentation
    When a reader reads how to run a project on their own machine
    Then the documentation says how to run every service of the project together
    And the documentation says how each service finds the others, with the local console running and without it
