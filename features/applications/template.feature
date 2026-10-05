Feature: Starting a service from the template
  A developer starts a service from the template the platform gives, in an empty place, and has a
  service with an entity, an endpoint, a view, tests, a database to run against, an image and a
  descriptor, all named for it, that passes its tests and runs with nothing edited.

  Scenario: a service started from the template carries the name it was started with
    When a developer starts a service "orders" from the template
    Then the code, the image and the descriptor of the service all carry the name "orders"
    And nothing in the service carries the template's own name where "orders" belongs

  Scenario: the tests of a service started from the template pass unchanged
    Given a service "orders" started from the template
    When the developer runs the tests of "orders"
    Then a test of its entity, a test of its endpoint and a test of the whole service on a database all pass

  Scenario: a service started from the template runs on the developer's machine with nothing edited
    Given a service "orders" started from the template
    And the database the template describes running on the developer's machine
    When the developer runs "orders" on that machine with nothing edited
    Then a request to its endpoint writes state and a second request reads it back

  Scenario: the image of a service started from the template is the one its descriptor names
    Given a service "orders" started from the template
    When the developer builds the image of "orders"
    Then the image carries the name "orders"
    And it is the image the descriptor of "orders" names

  Scenario: a service started from the template says how to test, run, build and deploy it
    Given a service "orders" started from the template
    When the developer reads the instructions "orders" was started with
    Then they say, in this order, how to run its tests, run it on the developer's machine, build its image and deploy it to a platform
