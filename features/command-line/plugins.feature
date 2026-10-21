Feature: Command line plugins
  A word after ankka that names no built-in command names a plugin: a program named for it on the
  path, run with the rest of the command line and with the settings a built-in command would have
  resolved. The command line stays thin; a product ships its own commands as a plugin.

  Background:
    Given a program "ankka-vendor" on the path

  Scenario: a word that names no built-in command runs the plugin of that name with the rest of the command line
    When a person runs "ankka vendor modules list"
    Then the program "ankka-vendor" runs with the arguments "modules list"
    And what the program prints is what the person sees

  Scenario: the plugin's exit code is the command's exit code
    Given the program "ankka-vendor" exits with the code "3"
    When a person runs "ankka vendor modules list"
    Then the command exits with the code "3"

  Scenario: a flag before the plugin's name is the command line's, a flag after it is the plugin's
    When a person runs "ankka -o json vendor modules list --all"
    Then the program "ankka-vendor" runs with the arguments "modules list --all"
    And the program "ankka-vendor" is given the environment variable "ANKKA_OUTPUT" set to "json"

  Scenario: a plugin is given the settings a built-in command would have resolved
    Given a saved login for the control plane URL "https://cp.example.test"
    And the saved project "p-1"
    When a person runs "ankka vendor modules list"
    Then the program "ankka-vendor" is given the environment variable "ANKKA_URL" set to "https://cp.example.test"
    And the program "ankka-vendor" is given the environment variable "ANKKA_TOKEN" set to the access token of the saved login
    And the program "ankka-vendor" is given the environment variable "ANKKA_PROJECT" set to "p-1"
    And the program "ankka-vendor" is given the environment variable "ANKKA_OUTPUT" set to "table"

  Scenario: a saved login that has run out is refreshed before the plugin runs
    Given a saved login for the control plane URL "https://cp.example.test" whose access token has run out
    When a person runs "ankka vendor modules list"
    Then the saved login is refreshed
    And the program "ankka-vendor" is given the environment variable "ANKKA_TOKEN" set to the refreshed access token

  Scenario: a plugin is given no token when none can be resolved, and still runs
    Given no saved login and no token
    When a person runs "ankka vendor modules list"
    Then the program "ankka-vendor" runs with the arguments "modules list"
    And the program "ankka-vendor" is given no environment variable "ANKKA_TOKEN"

  Scenario: the token reaches the plugin by its environment alone
    Given a saved login for the control plane URL "https://cp.example.test"
    When a person runs "ankka vendor modules list"
    Then the arguments the program "ankka-vendor" runs with hold no token
    And nothing written by the command holds the token
    And nothing the command prints holds the token

  Scenario: a word that names neither a built-in command nor a plugin is a usage error
    When a person runs "ankka casino brands"
    Then the command exits with the code "2"
    And the person is told that "casino" names no built-in command and no program "ankka-casino" is on the path

  Scenario: a plugin that cannot be run fails the command, naming the program
    Given a program "ankka-broken" on the path that cannot be run
    When a person runs "ankka broken"
    Then the command exits with the code "1"
    And the person is told the program "ankka-broken" cannot be run, and where it is

  Scenario: a plugin's name holds no directory
    When a person runs "ankka ../vendor"
    Then the command exits with the code "2"

  Scenario: the plugin list names every plugin on the path and where it is
    Given a program "ankka-casino" on the path
    When a person runs "ankka plugin list"
    Then the list names the plugin "vendor" with where its program is
    And the list names the plugin "casino" with where its program is

  Scenario: a plugin named like a built-in command is listed as shadowed and never run
    Given a program "ankka-services" on the path
    When a person runs "ankka services list"
    Then the built-in command "services" answers
    And the program "ankka-services" does not run
    And the list from "ankka plugin list" marks "services" as shadowed

  Scenario: help names the plugins found beside the built-in commands
    When a person runs "ankka --help"
    Then the help names the plugin "vendor" under plugins
