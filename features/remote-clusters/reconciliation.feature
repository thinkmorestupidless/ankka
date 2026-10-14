Feature: Services on a remote cluster
  A service of a project placed on a remote cluster runs there and nowhere else. The desired state
  the control plane would have written into its own cluster goes down the tether, the operator writes
  it into its cluster, and every report comes back up the tether. While the tether is down the services
  keep serving, the operator keeps reconciling what it has, and the control plane says what it
  cannot confirm.

  Background:
    Given a platform administrator "root"
    And a remote cluster "pi" that is tethered
    And a project "shop" placed on "pi"

  Scenario: a service applied to a project on a remote cluster runs on that cluster and nowhere else
    When a member applies a descriptor for the service "cart" in "shop"
    Then the operator on "pi" writes the database of "cart" and the instances of "cart" on "pi"
    And nothing of "cart" is written on the home cluster
    And "cart" becomes ready
    And the report of "cart" reached the control plane over the tether

  Scenario: a service on a remote cluster is paused, resumed, restarted, scaled and deleted as a service at home is
    Given a deployed service "cart" in "shop" that is ready
    When a member pauses "cart"
    Then "cart" has no instance on "pi" and reads "Paused"
    When a member resumes "cart"
    Then "cart" becomes ready on "pi"
    When a member restarts "cart"
    Then every instance of "cart" on "pi" is replaced and "cart" becomes ready
    When a member scales "cart" to 3 instances
    Then "cart" has 3 ready instances on "pi"
    When a member deletes "cart"
    Then nothing of "cart" remains on "pi"

  Scenario: a service on a remote cluster is reported with the same lifecycle, generation and detail as a service at home
    Given a deployed service "cart" in "shop" at generation 3
    When a member applies a descriptor for "cart" with the image "cart:missing"
    Then the report of "cart" describes generation 4
    And the report of "cart" says "Failed" and names the image "cart:missing" and why it could not be run
    And the history of "cart" records the apply and the report as it does for a service at home

  Scenario: a project's declared topics are made on the broker of the cluster it is placed on
    Given "pi" has a broker
    When a member declares the topic "orders" for "shop"
    Then the topic "orders" is made on the broker of "pi"
    And no topic "orders" is made on the broker of the home cluster

  Scenario: a descriptor that asks for what the placed cluster lacks is reported as it is on an installation that lacks it
    Given "pi" has no broker
    When a member applies a descriptor for the service "cart" in "shop" that asks for a topic
    Then the report of "cart" says that "pi" has no broker, as it would on an installation with no broker

  Scenario: services on a remote cluster keep serving while the tether is down
    Given a deployed service "cart" in "shop" that is ready and exposed
    When the tether of "pi" goes down
    Then "cart" answers at its hostname the whole time
    And the operator on "pi" keeps "cart" as it was recorded

  Scenario: a service on a remote cluster reads unconfirmed once the tether has been down for the tether bound
    Given a deployed service "cart" in "shop" that is ready
    And the tether bound of the installation is "30 seconds"
    When the tether of "pi" goes down
    Then within the tether bound "cart" reads "Ready (unconfirmed)"
    And "pi" is listed as untethered, with when it was last seen

  Scenario: a descriptor applied while the tether is down is recorded and honoured when the tether is back
    Given a deployed service "cart" in "shop" that is ready at generation 3
    And the tether of "pi" is down
    When a member applies a descriptor for "cart" with the image "cart:2"
    Then the apply is recorded at generation 4
    And "cart" reads "Ready (unconfirmed)" describing generation 3
    When the tether of "pi" is back
    Then the operator on "pi" receives what the control plane recorded for "cart" at generation 4
    And "cart" becomes ready with the image "cart:2" at generation 4

  Scenario: a report that reaches the control plane after the tether is back confirms the service again
    Given a deployed service "cart" in "shop" that reads "Ready (unconfirmed)" because the tether of "pi" is down
    When the tether of "pi" is back
    Then the operator on "pi" sends every report it holds
    And "cart" reads "Ready"
    And "pi" is listed as tethered

  Scenario: the operator on a remote cluster that cannot write to its own cluster says so
    Given a deployed service "cart" in "shop" that is ready
    When the operator on "pi" can no longer write to "pi"
    Then "cart" reads "Ready (unconfirmed)" with the reason the operator gave
    And "pi" is listed as tethered

  Scenario: sending what was recorded a second time changes nothing
    Given a deployed service "cart" in "shop" that is ready at generation 3
    When the tether of "pi" is replaced by a new tether and what the control plane recorded for "shop" is sent again
    Then nothing of "cart" on "pi" changes
    And no new report of "cart" is recorded
