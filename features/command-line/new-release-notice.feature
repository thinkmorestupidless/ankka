Feature: A notice when a newer release exists
  The command line carries the documentation of the release it was built from, and nothing else
  tells a person or a model that the release is old. The command line reads the latest release at
  most once a day, never in the way of a command, and says so in one sentence: "ankka version Y is
  available (you're currently using version X)". Its MCP server says the same where a model reads.

  Background:
    Given the command line at release "0.13.0"

  Scenario: a command of a command line older than the latest release ends with the notice
    Given the latest release "0.16.0"
    When a person runs a command
    Then the command's error output ends with the line "ankka version 0.16.0 is available (you're currently using version 0.13.0)"

  Scenario: a command line at the latest release gives no notice
    Given the command line at release "0.16.0"
    And the latest release "0.16.0"
    When a person runs a command
    Then no notice is given

  Scenario Outline: a latest release that is not newer than the command line gives no notice
    Given the latest release "<latest>"
    When a person runs a command
    Then no notice is given

    Examples:
      | latest |
      | 0.13.0 |
      | 0.12.4 |

  Scenario: the latest release is read at most once a day
    Given the latest release "0.16.0" was read less than a day ago
    When a person runs a command
    Then the latest release is not read again

  Scenario: the notice is given at most once a day
    Given the latest release "0.16.0"
    And the notice was given less than a day ago
    When a person runs a command
    Then no notice is given

  Scenario: the version command reads the latest release at once and gives the notice whenever a newer release exists
    Given the latest release "0.16.0" was read less than a day ago
    And the notice was given less than a day ago
    And the latest release "0.17.0" was published since
    When a person runs the version command
    Then the latest release is read
    And the command's error output ends with the line "ankka version 0.17.0 is available (you're currently using version 0.13.0)"

  Scenario: a latest release that cannot be read changes nothing about the command
    Given the latest release cannot be read
    When a person runs a command
    Then the command's output and exit code are what they are with the check turned off
    And no notice is given

  Scenario: a command ends when its own work ends, not when the read of the latest release does
    Given the latest release takes longer to read than the command takes
    When a person runs a command
    Then the command ends no more than 100 milliseconds after its own work ends
    And no notice is given

  Scenario Outline: the latest release is not read and no notice is given where a notice would be in the way
    Given the latest release "0.16.0"
    When a person runs a command <where>
    Then the latest release is not read
    And no notice is given

    Examples:
      | where                              |
      | with JSON output                   |
      | under CI                           |
      | with the check turned off          |
      | with its error output not a terminal |

  Scenario: a development build of the command line never reads the latest release
    Given the command line is a development build
    And the latest release "0.16.0"
    When a person runs a command
    Then the latest release is not read
    And no notice is given

  Scenario: the command line's MCP server gives the notice in its instructions
    Given the latest release "0.16.0"
    When a model connects to the command line's MCP server
    Then the server's instructions say "ankka version 0.16.0 is available (you're currently using version 0.13.0)"

  Scenario: every page of documentation the model reads says it is the command line's release's and a newer one exists
    Given the latest release "0.16.0"
    When the model reads a page of the documentation through the command line's MCP server
    Then the page is preceded by a note that it is the documentation of release "0.13.0"
    And the note says "ankka version 0.16.0 is available (you're currently using version 0.13.0)"

  Scenario: the pages the model finds by keywords say they are the command line's release's and a newer one exists
    Given the latest release "0.16.0"
    When the model finds pages of the documentation by keywords through the command line's MCP server
    Then the answer has a note that the pages are the documentation of release "0.13.0"
    And the note says "ankka version 0.16.0 is available (you're currently using version 0.13.0)"

  Scenario: the model asking who it is is told the command line's release and the latest release
    Given the latest release "0.16.0"
    When the model asks the command line's MCP server who it is
    Then the answer names the command line's release "0.13.0" and the latest release "0.16.0"

  Scenario: the model asking for the installation is told when the installation is at a newer release than the command line
    Given an installation at release "0.16.0"
    When the model asks the command line's MCP server for the installation
    Then the answer says the installation is at release "0.16.0" and the command line at the older release "0.13.0"

  Scenario: an MCP server that runs for more than a day reads the latest release again
    Given the command line's MCP server started with the latest release "0.13.0"
    And the latest release "0.16.0" was published since
    When a day has passed and the model asks the server who it is
    Then the answer names the latest release "0.16.0"

  Scenario: a latest release that cannot be read changes nothing the MCP server answers
    Given the latest release cannot be read
    When the model asks the command line's MCP server who it is
    Then the answer is what it is with the check turned off

  Scenario: the documentation of the command line says when the notice is given and how to turn the check off
    When a reader looks up the command line in the documentation
    Then the documentation says when the notice is given and when it is not
    And the documentation says how to turn the check off
