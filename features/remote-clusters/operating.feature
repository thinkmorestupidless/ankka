Feature: Operating a service on a remote cluster
  A service on a remote cluster is read as a service at home is: its logs and its topology are
  asked of the operator over the tether, which answers from its own cluster. The console names the
  cluster a project is placed on.

  Background:
    Given a remote cluster "pi" that is tethered
    And a project "shop" placed on "pi"
    And a deployed service "cart" in "shop" that is ready

  Scenario: the logs of a service on a remote cluster are read over the tether
    When a member reads the logs of "cart"
    Then the member is shown the log of each instance of "cart" on "pi"
    And the operator on "pi" answered the request over the tether

  Scenario: the topology of a service on a remote cluster is read over the tether
    When a member reads the topology of "cart"
    Then the member is shown the topology each instance of "cart" on "pi" reports
    And the operator on "pi" answered the request over the tether

  Scenario: the logs of a service on a remote cluster whose tether is down are refused with the reason
    Given the tether of "pi" is down
    When a member reads the logs of "cart"
    Then the request is refused with 503, saying that "pi" is untethered

  Scenario: the console names the cluster a project is placed on
    When a member opens the console
    Then wherever the console names "shop" it names "pi"
    When a platform administrator opens the console
    Then the platform administrator is shown every cluster, whether it is tethered, and when it was last seen
