Feature: A service in Java or Kotlin is embedded
  A service written in Java or Kotlin is embedded, as a service written in Scala is: the developer's
  program is the platform's own, and nothing runs beside it. The Java SDK shows a Java developer
  nothing of Scala, declares each handler's name apart from the function that handles it, and
  writes a state or an event exactly as the Scala SDK writes the same shape, so that a service in
  either language recovers what the other recorded.

  Scenario Outline: a service written in Java or Kotlin is embedded, as a service written in Scala is
    Given a service "shop" written in "<language>" started from the template
    When the developer runs "shop" on their own machine
    Then "shop" is embedded: the developer's program is the platform's own, and no process runs beside it
    And a command sent to an entity of "shop" is answered

    Examples:
      | language |
      | Java     |
      | Kotlin   |

  Scenario Outline: a command to an entity in Java or Kotlin costs at most a tenth more than in Scala
    Given the shopping cart sample written in "Scala" and written in "<language>", running on the same machine
    When a caller sends the command "add-item" 1000 times to a cart of each
    Then the median time of a command to the "<language>" cart, as the trace measures it, is at most a tenth more than to the "Scala" cart

    Examples:
      | language |
      | Java     |
      | Kotlin   |

  Scenario: every type a Java developer writes, reads or is given is a Java type
    Given the Java SDK and its documentation
    When a developer reads every declaration the documentation of the Java SDK names
    Then none is a Scala type

  Scenario: a state declared in Java and the same state declared in Scala are written the same
    Given the state of the entity "cart" declared in "Java" and the same state declared in "Scala"
    When each is written in the encoding
    Then every byte the "Java" state is written as is the byte the "Scala" state is written as

  Scenario Outline: a handler's name is declared apart from the function that handles it
    Given a service "shop" written in "<language>" whose entity "cart" has the handler "add-item" declared on the function "addItem"
    When the developer renames the function to "putItem" and runs "shop" again
    Then a caller's command "add-item" to the cart "c1" is still answered
    And the cart "c1" is recovered from what it recorded before the rename

    Examples:
      | language |
      | Java     |
      | Kotlin   |

  Scenario Outline: two handlers declared with one name fail the service at start
    Given a service "shop" written in "<language>" with two handlers of the entity "cart" declared with the name "add-item"
    When the developer starts "shop"
    Then "shop" does not start, naming "add-item"

    Examples:
      | language |
      | Java     |
      | Kotlin   |

  Scenario Outline: a handler declared wrongly fails the service at start, not at its first request
    Given a service "shop" written in "<language>" whose entity "cart" has <problem>
    When the developer starts "shop"
    Then "shop" does not start, naming the function
    And no caller's request is needed to find out

    Examples:
      | language | problem                                                        |
      | Java     | a function shaped like a handler that carries no name          |
      | Java     | a query whose declared outcome could record an event           |
      | Kotlin   | a function shaped like a handler that carries no name          |
      | Kotlin   | a query whose declared outcome could record an event           |

  Scenario Outline: a component the service does not register fails at start, not at its first request
    Given a service "shop" written in "<language>" whose endpoint calls the entity "cart", which "shop" does not register
    When the developer starts "shop"
    Then "shop" does not start, naming "cart"
    And no caller's request is needed to find out

    Examples:
      | language |
      | Java     |
      | Kotlin   |

  Scenario Outline: a field holding nothing is written as absent, and a field holding null is refused
    Given a state declared in "<language>" with a field "discount" that may hold nothing
    When the state is written with the field holding <value>
    Then <outcome>

    Examples:
      | language | value   | outcome                                             |
      | Java     | nothing | the field is absent from what is written            |
      | Java     | null    | the writing is refused, naming the field "discount" |
      | Kotlin   | null    | the field is absent from what is written            |

  Scenario Outline: the shopping cart written in Java or Kotlin passes the sample's own features
    Given the shopping cart sample written in "<language>"
    When the sample's own features run against it
    Then every scenario passes and none is left out

    Examples:
      | language |
      | Java     |
      | Kotlin   |

  Scenario Outline: the first service in Java or Kotlin needs a JDK and Docker and nothing else
    Given a developer with a JDK and Docker and nothing written
    When the developer follows the documentation's first service in "<language>"
    Then the developer sends a command to an entity of a service running on their own machine
    And the developer has installed nothing else

    Examples:
      | language |
      | Java     |
      | Kotlin   |

  Scenario Outline: a service started from the template builds with a JDK and no build tool installed
    Given a developer with a JDK and Docker and nothing else installed
    When the developer starts a service "shop" written in "<language>" from the template and builds it
    Then "shop" builds and every test of "shop" passes

    Examples:
      | language |
      | Java     |
      | Kotlin   |

  Scenario Outline: a change to the template is proven on the template before it is merged
    Given a change to the template for "<language>"
    When the change is offered for merging
    Then a service is started from the changed template and its tests are run
    And the change is merged only if every test passes

    Examples:
      | language |
      | Java     |
      | Kotlin   |

  Scenario: every place that names the languages a service may be written in names Java and Kotlin
    Given the published documentation and the platform's skills
    When a reader reads any place that names the languages a service may be written in
    Then Java and Kotlin are named

  Scenario Outline: the limitations name what the Java and Kotlin SDKs do not offer
    Given the published documentation
    When a reader reads the limitations about the SDK for "<language>"
    Then what the SDK for "<language>" does not offer is named

    Examples:
      | language |
      | Java     |
      | Kotlin   |
