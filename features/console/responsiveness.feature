Feature: A console that is fast and works with nothing running in the browser
  The console sends a browser what was asked for already drawn, moves between things without loading
  itself again, and does everything it offers whether or not the browser runs what it sends.

  Background:
    Given a person signed in to the console

  Scenario: a person is shown what they asked for before the browser runs anything the console sent
    When the person asks the console for the organization "acme"
    Then the browser shows "acme" before it runs anything the console sent

  Scenario: moving within the console does not load it again
    Given a browser with "JavaScript" turned on, showing the organization "acme"
    When the person moves to the project "shop" in "acme"
    Then the browser shows "shop" within 300 milliseconds, without loading the console again

  Scenario Outline: a person whose browser runs nothing the console sends can do everything the console offers
    Given a browser with "JavaScript" turned off
    When the person <action>
    Then it is done and the person is shown what came of it

    Examples:
      | action                                          |
      | creates the organization "acme"                 |
      | creates the project "shop" in "acme"            |
      | applies a descriptor for "cart" in "shop"       |
      | pauses "cart"                                   |
      | invites "ana@example.test" to "acme"            |
      | creates a deploy token "ci" for "acme"          |

  Scenario: a request sent twice while it is still being answered is done once
    Given the person has asked to create the project "shop" and has not yet been answered
    When the person asks to create the project "shop" again
    Then "shop" is created once
