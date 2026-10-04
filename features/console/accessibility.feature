Feature: Every page is readable and operable
  The console's surfaces let the backdrop show through, so the contrast of its text is a property
  of the backdrop's glow, and the glow is pinned so that the contrast holds at its brightest
  point in each theme. Every page is audited in both themes, every operation is reached by
  keyboard, and a state is told by a shape as well as a colour.

  Background:
    Given a service "cart" deployed in the project "checkout"
    And a member of "checkout"

  Scenario: every page has no accessibility violation in either theme
    Given every page of the console
    When each page is audited in the dark theme and in the light theme
    Then no accessibility violation is reported

  Scenario: every operation is reached by keyboard with a visible focus
    Given the member's browser has only a keyboard
    When the member reaches each operation of the page of "cart"
    Then each operation is reached
    And the focus is visible on each

  Scenario: text keeps its contrast at the glow's brightest point
    Given the console's custom properties as shipped
    When the contrast of ink over each surface is computed at the glow's brightest point, in each theme
    Then every contrast is at least 4.5 to 1

  Scenario: a glow brighter than the limit fails the build
    Given the glow custom property set brighter than the limit
    When the contrast of ink over each surface is computed
    Then the build fails, naming the glow custom property

  Scenario: a lifecycle is told by its shape as well as its colour
    Given a service "inventory" deployed in the project "checkout" that has failed
    When the member reads the page of "checkout"
    Then the lifecycle of "cart" and the lifecycle of "inventory" are shown with different marks
    And the marks differ in shape, not only in colour

  Scenario: a member who asks for reduced motion sees the live indicator still
    Given the member's browser asks for reduced motion
    When the member reads the page of "cart"
    Then the live indicator is shown
    And nothing on the page moves of its own accord

  Scenario: a browser that cannot blur what is behind a surface still shows readable text
    Given the member's browser cannot blur what is behind a surface
    When the member reads the page of "cart"
    Then every surface is drawn opaque enough that the contrast holds

  Scenario: in forced colours every surface keeps its edge
    Given the member's browser forces its own colours
    When the member reads the page of "cart"
    Then every surface has a border
    And every operation has an outline
