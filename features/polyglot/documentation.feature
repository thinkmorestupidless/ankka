Feature: The documentation of each language
  A developer meeting entities, events and workflows for the first time learns them from the
  documentation, or from a coding agent holding the platform's skills. The documentation takes a
  developer from nothing written to a running service in each language, and every sample it shows is
  taken from code the platform tests.

  Scenario Outline: the first service in a language needs no JVM
    Given a developer with <tools> and nothing written
    When the developer follows the documentation's first service in "<language>"
    Then the developer sends a command to an entity of a service running on their own machine
    And the developer has installed no JVM

    Examples:
      | language   | tools                           |
      | TypeScript | Node and Docker                 |
      | Rust       | the Rust toolchain and Docker   |

  Scenario Outline: the documentation of a language states every kind of component with a tested sample
    Given the published documentation
    When a developer reads the documentation of "<language>" about <subject>
    Then the documentation states it, with a sample taken from tested code

    Examples:
      | language   | subject                         |
      | TypeScript | each kind of component          |
      | TypeScript | calling a component             |
      | TypeScript | the component test kit and the test kit |
      | TypeScript | how a service runs              |

  Scenario Outline: a coding agent writing a component in a language is told how the language differs
    Given a coding agent holding the platform's skills
    When the coding agent is asked for a component of any kind written in "<language>"
    Then the skill it reads states the rules in which "<language>" differs from the other languages
    And the component it writes builds and passes the component test kit

    Examples:
      | language   |
      | TypeScript |
      | Rust       |

  Scenario Outline: the documentation does not build when a page is out of step with what it is taken from or listed in
    Given <problem>
    When the documentation is built
    Then the build fails, naming the page

    Examples:
      | problem                                                     |
      | a sample on a page differs from the tested code it is taken from |
      | a new page is in no skill                                   |
      | a new page is not listed in the documentation's contents    |
