Feature: Setting up a repository's documentation
  A repository takes the documentation tool by one command, which writes what a documentation
  build needs and nothing over what is there. The parts of the set-up the documentation tool keeps are the same
  in every repository and the check holds them, so one repository's set-up is never copied from
  another's and never drifts from it.

  Scenario: a repository with no documentation is set up by one command and its documentation builds
    Given a repository "widget" with no documentation
    When a developer sets up its documentation with the description "Widgets, as a service." and the address "https://docs.widget.example/"
    Then the repository holds the build settings, the documentation job, the navigation, a first page, a page on writing documentation, a limitations page, a glossary page and a skill "widget"
    And the documentation of "widget" builds with no problem

  Scenario: the set-up names the release of the documentation tool that made it
    Given a repository "widget" with no documentation
    When a developer sets up its documentation with release "0.19.0" of the documentation tool
    Then the build settings of "widget" name release "0.19.0" of the documentation tool
    And nothing the set-up wrote names a branch of the documentation tool

  Scenario: setting up leaves what the repository already holds as it is and names it
    Given a repository "widget" that holds a navigation of its own and no other documentation
    When a developer sets up its documentation
    Then the navigation of "widget" is as it was
    And the report names the navigation as left
    And the report names every other part of the set-up as written

  Scenario: setting up a repository that is already set up writes only what is missing
    Given a repository "widget" whose documentation was set up
    And the limitations page of "widget" was removed
    When a developer sets up its documentation
    Then the report names the limitations page as written and every other part of the set-up as left

  Scenario: the set-up says what is left for a person to do
    Given a repository "widget" with no documentation
    When a developer sets up its documentation
    Then the report says publishing is turned on by a person
    And the report says the address is pointed at the published documentation by a person
    And the report gives the commands a developer builds the documentation with
    And the report says the skill is published by the repository's own release

  Scenario: a set-up repository checks its documentation on a pull request and publishes nothing
    Given a repository "widget" whose documentation was set up
    When a pull request changes a page of "widget"
    Then the documentation of "widget" is checked
    And nothing is published

  Scenario Outline: a set-up repository publishes its documentation when a release is tagged
    Given a repository "widget" whose documentation was set up
    When "<tag>" is tagged in "widget"
    Then the documentation of "widget" is <outcome>

    Examples:
      | tag          | outcome                         |
      | v0.2.0       | published for release "0.2.0"   |
      | v0.2.0-rc.1  | not published                   |

  Scenario: a part of the set-up the documentation tool keeps that was changed by hand fails the check
    Given a repository "widget" whose documentation was set up
    And the documentation job of "widget" was changed by hand
    When the documentation of "widget" is checked
    Then the check fails and names the documentation job
    And the check says the documentation job is put back by removing it and setting up again

  Scenario: a part of the set-up the documentation tool keeps is put back by removing it and setting up again
    Given a repository "widget" whose documentation was set up
    And the documentation job of "widget" was changed by hand
    When a developer removes the documentation job and sets up the documentation of "widget"
    Then the documentation of "widget" is checked and the check passes

  Scenario: a repository that says the documentation tool keeps no documentation job there is held to none
    Given a repository "widget" whose documentation was set up
    And "widget" says the documentation tool keeps no documentation job there
    And the documentation job of "widget" was removed
    When the documentation of "widget" is checked
    Then the check passes
