Feature: The skill for writing documentation
  A coding agent asked to document a repository, or to add a page, is given the rules a page
  follows and the order to work in, as a skill of the platform. The rules are the published page,
  so the skill and the documentation never say different things.

  Scenario: a coding agent holding the platform's skills is told the rules a page follows
    Given a coding agent holding the platform's skills
    When the coding agent is asked to write a page of documentation
    Then the coding agent is told the rules a page follows by the skill for writing documentation

  Scenario: the rules the skill gives are the rules the documentation publishes
    Given the published documentation
    When a reader compares the page on writing documentation with the skill for writing documentation
    Then the skill holds the page as it is published

  Scenario: the skill says how a repository is documented, in order
    Given a coding agent holding the platform's skills
    When the coding agent is asked to document a repository
    Then the skill says to set the repository's documentation up first
    And the skill says to read the code, the contract and the living features before writing
    And the skill says every sample shown is taken from code a test runs
    And the skill says a reply shown on a page is one the running service gave
    And the skill says the limitations page is written from what the code does
    And the skill says to change a sample's code and see the check fail

  Scenario: the documentation says how a repository's documentation is set up, and no longer by copying
    Given the published documentation
    When a reader reads about writing documentation
    Then the documentation describes setting up a repository's documentation with the documentation tool
    And the documentation does not say to copy another repository's set-up
