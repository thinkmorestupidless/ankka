Feature: How a module holds a component's state
  Each component that has state in a module is stateless or stateful, as its developer declares. A
  stateless component is handed its state with every call and hands back the new one; a stateful
  component is handed its state once, when its entity is loaded, and keeps it between calls. The
  platform's own program holds every entity's state in both, so a module that fails loses nothing.

  Scenario: a stateful component is handed its state once per load
    Given a module with the stateful entity "cart"
    When the cart "c1" is loaded, sent 3 commands, passivated, and sent a command again
    Then the module is handed the state of "c1" once for each time it was loaded
    And each of the 3 commands sees the state the command before it left
    And while "c1" is passivated the module holds nothing of it

  Scenario: a stateful component whose module fails mid-command is recovered from the state the platform holds
    Given a module with the stateful entity "cart" whose cart "c1" holds "socks"
    And the module fails part way through a command to "c1"
    When the next command to "c1" arrives
    Then the platform's own program loads the module again with the state it holds for "c1"
    And the command sees "socks" and nothing the failed command did

  Scenario: a workflow step waiting on a call delays no command
    Given a module with a workflow "checkout" whose step "reserve" is waiting on a call to the entity "stock"
    When a caller sends a command to the cart "c1"
    Then the command is answered without waiting for "reserve"
