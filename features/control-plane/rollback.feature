Feature: Rolling a service back
  A member whose service was better at an earlier generation rolls it back: the platform applies
  the descriptor of that generation again, as a new generation. The generation goes on counting and
  the history shows the roll back; nothing that was recorded is undone. The platform keeps the
  descriptors of a service's most recent generations, and refuses to roll back to one it no longer
  keeps.

  Background:
    Given a project "shop"
    And a member of the organization "shop" is in
    And a service "cart" applied at generation 1 with the image "cart:1"
    And "cart" applied at generation 2 with the image "cart:2"

  Scenario: rolling back with no generation named applies the descriptor of the generation before
    When the member rolls "cart" back
    Then "cart" is at generation 3
    And the status of "cart" shows the image "cart:1"

  Scenario: rolling back with no generation named passes over a restart
    Given a member restarted "cart" at generation 3
    When the member rolls "cart" back
    Then "cart" is at generation 4
    And the status of "cart" shows the image "cart:1"

  Scenario: rolling back with no generation named passes over a generation applied with the same descriptor
    Given "cart" applied at generation 3 with the same descriptor as generation 2
    When the member rolls "cart" back
    Then "cart" is at generation 4
    And the status of "cart" shows the image "cart:1"

  Scenario: rolling back twice with no generation named brings back the descriptor it started with
    Given the member has rolled "cart" back
    When the member rolls "cart" back
    Then "cart" is at generation 4
    And the status of "cart" shows the image "cart:2"

  Scenario: rolling back to a named generation applies the descriptor of that generation
    When the member rolls "cart" back to generation 1
    Then "cart" is at generation 3
    And the descriptor of "cart" is the one applied at generation 1

  Scenario: a service rolled back runs the image of the generation it was rolled back to
    Given "cart" is ready with the image "cart:2"
    When the member rolls "cart" back to generation 1
    Then "cart" is ready with the image "cart:1"

  Scenario: the history shows a roll back, who made it and the generation it was rolled back to
    Given the member has rolled "cart" back to generation 1
    When the member reads the history of "cart"
    Then the history of "cart" shows first a roll back at generation 3 by the member
    And the history says that generation 3 was rolled back to generation 1

  Scenario: a paused service that is rolled back stays paused
    Given "cart" is paused
    When the member rolls "cart" back
    Then "cart" is at generation 3
    And the status of "cart" is "Paused" and "cart" has no instances

  Scenario: an exposed service that is rolled back stays exposed
    Given "cart" is exposed
    When the member rolls "cart" back
    Then "cart" is at generation 3
    And "cart" is exposed

  Scenario: a roll back to a descriptor the platform no longer accepts is refused
    Given the descriptor applied at generation 1 is one the platform no longer accepts
    When the member rolls "cart" back to generation 1
    Then the member is refused
    And the refusal names the problems of the descriptor
    And "cart" is still at generation 2

  Scenario: a roll back that would take the organization over its quota is refused
    Given the descriptor applied at generation 1 asks for more instances than the quota of the organization allows
    When the member rolls "cart" back to generation 1
    Then the member is refused
    And the refusal says that the quota does not allow it
    And "cart" is still at generation 2

  Scenario: a machine holding a deploy token rolls a service back
    Given a deploy token of the organization "shop" is in
    When a machine holding the deploy token rolls "cart" back
    Then "cart" is at generation 3
    And the history of "cart" shows first a roll back at generation 3 by the deploy token

  Scenario: a member rolls a service back from the console
    When the member rolls "cart" back to generation 1 in the console
    Then "cart" is at generation 3
    And the console shows first in the history of "cart" a roll back at generation 3

  Scenario: the console offers a roll back only to a generation that can be rolled back to
    Given a member restarted "cart" at generation 3
    When the member reads the history of "cart" in the console
    Then the console offers a roll back to generation 1
    And the console offers no roll back to generation 2 or to generation 3

  Scenario: the console shows a refused roll back as the control plane refused it
    Given the console shows the history of "cart"
    And "cart" has since been applied with the descriptor of generation 1
    When the member rolls "cart" back to generation 1 in the console
    Then the console shows the refusal of the control plane, that "cart" already has the descriptor of generation 1

  Scenario: a service applied only once cannot be rolled back
    Given a service "search" applied at generation 1 with the image "search:1"
    When the member rolls "search" back
    Then the member is refused
    And the refusal says that "search" has no earlier generation with a different descriptor
    And "search" is still at generation 1

  Scenario: a roll back to a generation whose descriptor is no longer kept is refused
    Given a service "orders" applied 60 times
    When the member rolls "orders" back to generation 3
    Then the member is refused
    And the refusal says that the oldest generation "orders" can be rolled back to is 11
    And "orders" is still at generation 60

  Scenario: a roll back to the oldest generation whose descriptor is kept is made
    Given a service "orders" applied 60 times
    When the member rolls "orders" back to generation 11
    Then "orders" is at generation 61
    And the descriptor of "orders" is the one applied at generation 11

  Scenario: a roll back to a generation the service never had is told there is no such generation
    When the member rolls "cart" back to generation 9
    Then the member is told that "cart" has no generation 9
    And "cart" is still at generation 2

  Scenario: a roll back to the generation a service is at is refused
    When the member rolls "cart" back to generation 2
    Then the member is refused
    And the refusal says that "cart" already has the descriptor of generation 2
    And "cart" is still at generation 2

  Scenario: a roll back to a generation with the descriptor the service already has is refused
    Given a member restarted "cart" at generation 3
    When the member rolls "cart" back to generation 2
    Then the member is refused
    And the refusal says that "cart" already has the descriptor of generation 2
    And "cart" is still at generation 3

  Scenario: a roll back to a generation that recorded no descriptor is refused
    Given a member restarted "cart" at generation 3
    And "cart" applied at generation 4 with the image "cart:4"
    When the member rolls "cart" back to generation 3
    Then the member is refused
    And the refusal says that generation 3 was a restart and ran the descriptor of generation 2
    And "cart" is still at generation 4

  Scenario: of two roll backs to one generation made at once, one is made and the other is refused
    Given a second member of the organization "shop" is in
    When the member and the second member each roll "cart" back to generation 1 at once
    Then "cart" is at generation 3
    And one of the two members is refused
    And the refusal says that "cart" already has the descriptor of generation 1

  Scenario: a service applied before the platform kept descriptors is rolled back to the descriptor it had
    Given a service "search" applied at generation 1 with the image "search:1" on a platform too old to keep descriptors
    And "search" applied at generation 2 with the image "search:2" on the upgraded platform
    When the member rolls "search" back
    Then "search" is at generation 3
    And the status of "search" shows the image "search:1"

  Scenario: a roll back to a descriptor whose runtime version the platform no longer runs is recorded and not deployed
    Given the descriptor applied at generation 1 states a runtime version the platform no longer runs
    When the member rolls "cart" back to generation 1
    Then "cart" is at generation 3
    And the status of "cart" says that the platform does not run that runtime version

  Scenario: a service deleted and applied again is rolled back to a generation from before it was deleted
    Given "cart" was deleted and applied again at generation 3 with the image "cart:3"
    When the member rolls "cart" back to generation 1
    Then "cart" is at generation 4
    And the descriptor of "cart" is the one applied at generation 1

  Scenario: a service of a disabled organization cannot be rolled back
    Given the organization "shop" is in is disabled
    When the member rolls "cart" back
    Then the member is refused
    And the refusal says that the organization is disabled
    And "cart" is still at generation 2

  Scenario: a person who is not a member cannot roll a service back
    Given a person who is not a member of the organization "shop" is in
    When that person rolls "cart" back
    Then that person is told that there is no project "shop"
    And "cart" is still at generation 2
