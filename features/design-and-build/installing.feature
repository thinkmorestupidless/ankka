Feature: Installing design and build
  Design and build is kept with the platform and copied into a repository with its two skills, as
  the review is; the stages it runs are the ones already there, and their hooks fire inside it.

  Scenario: design and build copied into a repository and registered can be started there
    Given a repository with "bdd" and "review" installed
    When a developer copies design and build into it and registers it
    Then the developer can start the design and the build there
    And what the repository had before is unchanged

  Scenario: the bdd hooks fire inside the design's specify and clarify stages
    Given a repository with design and build and "bdd" installed
    When the design runs the "specify" and "clarify" stages
    Then the "bdd" hooks fire inside them as they do when the stages are run alone

  Scenario: the review hook fires inside the build after the last work item
    Given a repository with design and build and "review" installed
    When the build finishes the last work item
    Then the "review" hook fires as it does when the "implement" stage is run alone

  Scenario: design and build changes nothing it runs
    Given a repository with design and build installed
    When a developer compares the stages, "bdd", "review" and the checker with what they were before
    Then none is changed

  Scenario: a stage can still be run alone to repair it
    Given a repository with design and build installed
    When a developer runs one stage alone
    Then it runs as it did before design and build was installed

  Scenario: the description of design and build says what it needs and what it is for
    Given a repository with design and build installed
    When a developer reads the description of design and build
    Then the description says "bdd" and "review" are needed
    And the description names the stages a developer may run alone to repair one
