Feature: The jobs a service started from the template carries
  A service started from the template carries two jobs for GitHub: one that tests it on every push,
  needing nothing, and one that builds its image, pushes it to a registry and deploys it, on a
  version tag or by hand. The deploy job declines to run until the repository holds its deploy
  settings, so a first push shows no failure.

  Background:
    Given a service "orders" started from the template, in a repository on GitHub

  Scenario: a first push of a service started from the template tests it and fails nothing
    Given the repository holds no deploy settings
    When the developer pushes to the repository
    Then the tests of "orders" run and pass
    And the deploy job declines to run
    And no job fails

  Scenario: a version tag deploys exactly the image the deploy job pushed
    Given the repository holds its deploy settings
    When the developer tags the version "v1.4.2"
    Then the image of "orders" is built and pushed to the registry as "1.4.2"
    And "orders" is deployed with exactly that image
    And the descriptor of "orders" in the repository is unchanged

  Scenario: a deploy run by hand deploys the commit it was run on
    Given the repository holds its deploy settings
    When the developer runs the deploy job by hand on a commit
    Then the image built from that commit is pushed to the registry
    And "orders" is deployed with exactly that image

  Scenario: a deploy refuses a descriptor that names a different service
    Given a descriptor that names the service "billing"
    When a job deploys "orders" with that descriptor
    Then the deploy is refused
    And nothing is deployed

  Scenario: the jobs of a service started from the template read the repository's deploy settings
    When the developer reads the deploy job of "orders"
    Then it reads the control plane, the deploy token and the project from the repository's deploy settings
    And nothing the template uses for itself is left in it
