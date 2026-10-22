Feature: Command line plugins
  A word after ankka that names no built-in command names a plugin: a program named for it on the
  path, run with the rest of the command line and with the settings a built-in command would have
  resolved. The command line stays thin; a product ships its own commands as a plugin.

  Background:
    Given a program "ankka-acme" on the path

  Scenario: a word that names no built-in command runs the plugin of that name with the rest of the command line
    When a person runs "ankka acme modules list"
    Then the program "ankka-acme" runs with the arguments "modules list"
    And what the program prints is what the person sees

  Scenario: the plugin's exit code is the command's exit code
    Given the program "ankka-acme" exits with the code "3"
    When a person runs "ankka acme modules list"
    Then the command exits with the code "3"

  Scenario: every flag after the plugin's name is the plugin's, untouched
    When a person runs "ankka acme modules list -o json --project p-2 --help"
    Then the program "ankka-acme" runs with the arguments "modules list -o json --project p-2 --help"

  Scenario: a plugin ended by a signal ends the command with 128 and the signal's number
    Given the program "ankka-acme" is ended by the signal "15"
    When a person runs "ankka acme modules list"
    Then the command exits with the code "143"

  Scenario: a plugin is given the settings a built-in command would have resolved
    Given the saved control plane URL "https://cp.example.test"
    And a saved login for the control plane URL "https://cp.example.test"
    And the saved project "p-1"
    And the saved trusted authorities "local-roots.pem"
    When a person runs "ankka acme modules list"
    Then the program "ankka-acme" is given the environment variable "ANKKA_URL" set to "https://cp.example.test"
    And the program "ankka-acme" is given the environment variable "ANKKA_TOKEN" set to the access token of the saved login
    And the program "ankka-acme" is given the environment variable "ANKKA_PROJECT" set to "p-1"
    And the program "ankka-acme" is given the environment variable "ANKKA_CA" set to the absolute path of "local-roots.pem"

  Scenario: a plugin is told where the settings are saved
    Given the settings saved in "work/config.json"
    When a person runs "ankka acme modules list"
    Then the program "ankka-acme" is given the environment variable "ANKKA_CONFIG" set to the absolute path of "work/config.json"

  Scenario: a saved login that has run out is refreshed before the plugin runs
    Given the saved control plane URL "https://cp.example.test"
    And a saved login for the control plane URL "https://cp.example.test" whose access token has run out
    When a person runs "ankka acme modules list"
    Then the saved login is refreshed
    And the program "ankka-acme" is given the environment variable "ANKKA_TOKEN" set to the refreshed access token

  Scenario: a plugin is given no token when none can be resolved, and still runs
    Given no saved login and no token
    When a person runs "ankka acme modules list"
    Then the program "ankka-acme" runs with the arguments "modules list"
    And the program "ankka-acme" is given no environment variable "ANKKA_TOKEN"

  Scenario: a refused refresh leaves the plugin without a token, and says why
    Given the saved control plane URL "https://cp.example.test"
    And a saved login for the control plane URL "https://cp.example.test" whose access token has run out
    And the identity provider refuses to refresh it
    When a person runs "ankka acme modules list"
    Then the program "ankka-acme" runs with the arguments "modules list"
    And the program "ankka-acme" is given no environment variable "ANKKA_TOKEN"
    And the person is told the login was refused and to run "ankka login"

  Scenario: the token reaches the plugin by its environment alone
    Given the saved control plane URL "https://cp.example.test"
    And a saved login for the control plane URL "https://cp.example.test"
    When a person runs "ankka acme modules list"
    Then the arguments the program "ankka-acme" runs with hold no token
    And nothing written by the command, but the saved login, holds the token
    And nothing the command prints holds the token

  Scenario: a word that names neither a built-in command nor a plugin is a usage error
    When a person runs "ankka reports summary"
    Then the command exits with the code "2"
    And the person is told that "reports" names no built-in command and no program "ankka-reports" is on the path

  Scenario: a plugin's name is matched exactly, whatever case the filesystem ignores
    When a person runs "ankka Acme"
    Then the command exits with the code "2"
    And the program "ankka-acme" does not run

  Scenario: a program of a plugin's name that is not executable is passed over
    Given a program "ankka-acme" on the path that is not executable, earlier than another program "ankka-acme"
    When a person runs "ankka acme modules list"
    Then the program "ankka-acme" runs with the arguments "modules list"
    And the list from "ankka plugin list" marks the program that is not executable

  Scenario: a plugin that cannot be run fails the command, naming the program
    Given a program "ankka-broken" on the path that cannot be run
    When a person runs "ankka broken"
    Then the command exits with the code "1"
    And the person is told the program "ankka-broken" cannot be run, and where it is

  Scenario: a plugin's name holds no directory
    When a person runs "ankka ../acme"
    Then the command exits with the code "2"

  Scenario: the plugin list names every plugin on the path and where it is
    Given a program "ankka-reports" on the path
    When a person runs "ankka plugin list"
    Then the list names the plugin "acme" with where its program is
    And the list names the plugin "reports" with where its program is

  Scenario: a plugin named like a built-in command is listed as shadowed and never run
    Given a program "ankka-services" on the path
    When a person runs "ankka services list"
    Then the built-in command "services" answers
    And the program "ankka-services" does not run
    And the list from "ankka plugin list" marks "services" as shadowed

  Scenario: help names the plugins found beside the built-in commands
    When a person runs "ankka --help"
    Then the help names the plugin "acme" under plugins

  Scenario: the command reference names no plugin
    When the command reference is generated
    Then it names no plugin, whatever is on the path
