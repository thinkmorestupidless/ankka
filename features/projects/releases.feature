Feature: Releasing each service of a project on its own
  A project keeps its services in one repository without tying their deployments together. A
  service tag releases the one service it names: its image alone is built and pushed, and it alone
  is deployed. Each service's version comes from its own service tags. A change runs the checks of
  the services it touches and no others.

  Background:
    Given a project "shop" started from the project template with the services "orders", "billing" and "stock", in a repository on GitHub
    And the repository holds its deploy settings

  Scenario: a service tag naming one service builds and deploys that service alone, at the service tag's version
    Given "billing" and "stock" are deployed at the version "1.0.0"
    When the developer tags "orders" at the version "1.4.2"
    Then the image of "orders" is built and pushed to the registry as "1.4.2"
    And "orders" is deployed with exactly that image
    And no image of "billing" or "stock" is built
    And "billing" and "stock" are still deployed at the version "1.0.0"

  Scenario: each service's version comes from its own service tags, whatever the other services were last released at
    Given "billing" was last tagged at the version "3.1.0"
    And "orders" was last tagged at the version "1.4.2"
    When the developer builds "orders" at a commit after its last service tag
    Then the version of "orders" follows "1.4.2"
    And nothing about the version of "orders" follows "3.1.0"

  Scenario: a service tag naming no service of the project releases nothing and says why
    When the developer tags "shipping" at the version "1.0.0"
    Then no image is built
    And nothing is deployed
    And the deploy job says that "shop" has no service "shipping"

  Scenario: a service tag at a commit where the service's tests fail deploys nothing of any service
    Given the tests of "orders" fail at a commit
    When the developer tags "orders" at that commit
    Then "orders" is not deployed
    And no other service of "shop" is built or deployed

  Scenario: a change to one service runs that service's checks and no other's
    When the developer pushes a change to the code of "orders" alone
    Then the tests of "orders" run
    And the tests of "billing" and "stock" do not run

  Scenario: a change to the shared module runs the checks of every service that uses it
    Given "orders" and "billing" use the shared module and "stock" does not
    When the developer pushes a change to the shared module
    Then the tests of "orders" and "billing" run
    And the tests of "stock" do not run
    And no service is deployed
