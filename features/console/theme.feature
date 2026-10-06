Feature: The console in dark and in light, from its own origin
  The console is dark. A browser reports a preference for light when nobody has stated one, so the
  console does not follow the browser; the light theme is kept for a host that chooses it. Whichever
  theme, every page fetches everything it needs, its face included, from the console's own origin:
  a console inside a cluster reaches nothing outside it.

  Background:
    Given a service "cart" deployed in the project "checkout"
    And a member of "checkout"

  Scenario: a member reads the console dark whatever the browser prefers
    Given the member's browser prefers the light theme
    When the member reads the page of "cart"
    Then the page is shown in the dark theme

  Scenario: a host that chooses the light theme shows the console light
    Given a host that chooses the light theme
    When the member reads the page of "cart" in that host
    Then the page is shown in the light theme

  Scenario: everything a page fetches comes from the console's own origin
    Given the console served from the origin "https://console.example.test"
    When the member reads the page of "cart"
    Then everything the page fetches comes from "https://console.example.test"
    And the face the page is set in comes from "https://console.example.test"
