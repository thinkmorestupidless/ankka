Feature: Reading a deployed service's logs
  A member reads what a deployed service's instances printed through the control plane, with the
  same credential that deploys it and none for the cluster. The platform keeps nothing: it reads
  what each instance holds at the moment of asking.

  Background:
    Given a project "shop"
    And a member of the organization "shop" is in, who holds no credential for the cluster

  Scenario: a member reads the recent logs of a deployed service
    Given a deployed service "cart" in "shop" that has printed "item added"
    When the member reads the logs of "cart"
    Then the logs show "item added"

  Scenario: each line of the logs of a service with several instances names the instance that printed it
    Given a deployed service "cart" in "shop" with 2 instances
    When the member reads the logs of "cart"
    Then each line of the logs names the instance that printed it

  Scenario: a member reads the logs of one instance
    Given a deployed service "cart" in "shop" with 2 instances
    When the member reads the logs of one instance of "cart"
    Then the logs show only what that instance printed

  Scenario: a member reads what an instance printed before it restarted
    Given a deployed service "cart" in "shop" whose instance printed "out of memory" and restarted
    When the member reads the logs of "cart" from before the restart
    Then the logs show "out of memory"

  Scenario: the logs of a paused service say that it has no running instance
    Given a deployed service "cart" in "shop" that is paused
    When the member reads the logs of "cart"
    Then the member is told that "cart" has no running instance

  Scenario: a person who is not a member is told there is no project when reading logs
    Given a deployed service "cart" in "shop"
    And a person who is not a member of the organization "shop" is in
    When that person reads the logs of "cart"
    Then that person is told that there is no project "shop"

  Scenario: a member reads the logs of a deployed service with no local console ever started
    Given a deployed service "cart" in "shop" that has printed "item added"
    And no local console has ever been started
    When the member reads the logs of "cart"
    Then the logs show "item added"
