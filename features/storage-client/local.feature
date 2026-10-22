Feature: The storage client on a developer's machine
  A service run on a developer's own machine has no bucket and no variables of one. The storage
  client keeps its objects in a directory store instead, so the service's code runs unchanged. A
  signed URL and a public address made there point at the service's own HTTP port, and the
  service serves them itself, so a page written against them works on the machine too. A service
  given the variables of an object store uses it rather than a directory. A service that declares no
  bucket starts whether or not its directory store can be written.

  Scenario: a service run on a developer's machine keeps its objects in a directory
    Given a service "listings" running on a developer's machine with no variable of an object store
    When a handler of "listings" keeps the object "listings/42/photos/front.jpg" through the storage client
    Then a handler of "listings" reads the object "listings/42/photos/front.jpg" back through the storage client
    And the directory store of "listings" holds the object "listings/42/photos/front.jpg" with its content type and when it was kept

  Scenario: a signed URL made on a developer's machine is served by the service itself
    Given a service "listings" running on a developer's machine with no variable of an object store
    And a handler of "listings" has kept the object "exports/march.pdf"
    When a browser on the machine sends a request to a signed URL that "listings" made through the storage client for reading "exports/march.pdf"
    Then the browser is shown the object "exports/march.pdf"
    And "listings" served it

  Scenario: a browser keeps an object on a developer's machine through a signed URL the service serves
    Given a service "listings" running on a developer's machine with no variable of an object store
    When a browser on the machine sends the object "uploads/passport.pdf" to a signed URL that "listings" made through the storage client for keeping "uploads/passport.pdf"
    Then a handler of "listings" reads the object "uploads/passport.pdf" back through the storage client

  Scenario: a request on a developer's machine without a signed URL is refused by the service
    Given a service "listings" running on a developer's machine with no variable of an object store
    And a handler of "listings" has kept the object "exports/march.pdf"
    When a browser on the machine sends a request for the object "exports/march.pdf" without a signed URL
    Then "listings" refuses the request

  Scenario: a public address on a developer's machine is served by the service itself
    Given a service "listings" running on a developer's machine with no variable of an object store
    And a handler of "listings" has kept the object "listings/42/front.jpg" in the public bucket "photos"
    When a browser on the machine sends a request to the public address that the storage client answered for "listings/42/front.jpg" in the bucket "photos"
    Then the browser is shown the object "listings/42/front.jpg"
    And "listings" served it

  Scenario: a service given the variables of an object store uses it rather than a directory
    Given a service "listings" running on a developer's machine with the variables of an object store of its own
    When a handler of "listings" keeps the object "listings/42/photos/front.jpg" through the storage client
    Then the object store of the service's own holds the object "listings/42/photos/front.jpg"
    And "listings" has no directory store

  Scenario: a service whose directory store cannot be written does not start
    Given a service "listings" on a developer's machine whose directory store cannot be written
    When "listings" starts
    Then "listings" does not start
    And the reason names the directory and why it cannot be written

  Scenario: a deployed service serves no signed URL and no public address itself
    Given a deployed service "listings" with a bucket and a public bucket "photos"
    When a browser sends a request to "listings" at the path a service on a developer's machine serves objects from
    Then "listings" answers that there is nothing there
