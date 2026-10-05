Feature: Services through the console
  Through the console a member sees the services of a project and everything the control plane
  reports of each, applies a descriptor, pauses, resumes, restarts, exposes and stops exposing a
  service, reads and follows its logs, and deletes it. What the member is shown is what the control
  plane reports, kept current while they watch.

  Background:
    Given a member of the organization "acme", which has the project "shop"

  Scenario: a member is shown every service of a project with its lifecycle
    Given the service "cart" in "shop", exposed, ready with 2 of 3 instances
    When the member reads the project "shop"
    Then the member is shown "cart" with its lifecycle, that 2 of its 3 instances are ready, its image, its generation and its hostname

  Scenario: a member is shown everything the control plane reports of a service, and its history
    Given the service "cart" in "shop", applied and then restarted
    When the member reads the service "cart"
    Then the member is shown its lifecycle, its detail, its generation, its database, its hostname, and whether it is paused or suspended, in words
    And the member is shown the history of "cart", with who applied and who restarted it and when

  Scenario Outline: applying a descriptor creates or changes the service and moves its generation on
    Given <state>
    When the member applies a descriptor for "cart" in "shop"
    Then "cart" is what the descriptor says
    And the generation of "cart" is <generation>

    Examples:
      | state                                  | generation |
      | no service "cart" in "shop"            | 1          |
      | the service "cart" at generation 3     | 4          |

  Scenario: every problem with a refused descriptor is shown beside it
    Given a descriptor for "cart" with two problems
    When the member applies the descriptor
    Then the member is refused
    And the member is shown both problems beside the descriptor

  Scenario Outline: what a member does to a service is shown as the control plane reports it
    Given the service "cart" in "shop", <state>
    When the member <action>
    Then <outcome>
    And the member is shown what the control plane reports of "cart" without asking again

    Examples:
      | state         | action                     | outcome                          |
      | ready         | pauses "cart"              | "cart" is paused                 |
      | paused        | resumes "cart"             | "cart" is ready                  |
      | ready         | restarts "cart"            | "cart" is ready after the restart |
      | not exposed   | exposes "cart"             | "cart" is exposed                |
      | exposed       | stops exposing "cart"      | "cart" is not exposed            |

  Scenario Outline: a member watching a service is shown what the control plane reports of it without asking
    Given the member is reading <what>, in a browser with "JavaScript" turned on
    When the control plane reports that "cart" <report>
    Then the member is shown that "cart" <report> within 5 seconds, without asking

    Examples:
      | what                         | report            |
      | the service "cart"           | is ready          |
      | the service "cart"           | is restarting     |
      | the service "cart"           | has failed        |
      | the project "shop"           | is ready          |

  Scenario: a member opens an exposed service at its hostname
    Given the service "cart" in "shop", exposed
    When the member opens the hostname of "cart" from what the console shows
    Then the browser is shown what "cart" answers at its hostname

  Scenario Outline: a member chooses which logs of a service to read
    Given the service "cart" in "shop" with 2 instances
    When the member reads the logs of "cart" for <choice>
    Then the member is shown the logs of "cart" for <choice>

    Examples:
      | choice                                    |
      | one instance                              |
      | the run of an instance before its restart |
      | the last "50" lines                       |
      | the last "300" seconds                    |

  Scenario: a member following the logs of a service is shown each new line without asking
    Given the member is following the logs of "cart", in a browser with "JavaScript" turned on
    When "cart" prints "order placed"
    Then the member is shown "order placed" within 5 seconds, without asking

  Scenario: a member who stops following the logs is shown no new line
    Given the member followed the logs of "cart", in a browser with "JavaScript" turned on, and has stopped following
    When "cart" prints "order placed"
    Then the member is not shown "order placed"

  Scenario: a deleted service applied again continues its generation
    Given the service "cart" in "shop" at generation 3
    And the member has deleted "cart"
    When the member applies a descriptor for "cart" in "shop"
    Then "cart" exists again at generation 4

  Scenario: a deleted service is gone
    Given the service "cart" in "shop"
    When the member deletes "cart"
    Then "cart" is no longer among the services of "shop"

  Scenario Outline: everything a member does to a service of a disabled organization is refused with the reason
    Given the organization "acme" is disabled
    And the service "cart" in "shop"
    When the member <action>
    Then the member is refused
    And the refusal says that "acme" is disabled
    And the lifecycle of "cart" is "Suspended"

    Examples:
      | action                                 |
      | applies a descriptor for "cart"        |
      | pauses "cart"                          |
      | restarts "cart"                        |
      | exposes "cart"                         |
      | deletes "cart"                         |
