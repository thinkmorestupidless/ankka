# Glossary

The words the shopping cart's features use, each in exactly one sense.

### cart
The products a customer has chosen and not yet ordered, one line per product.

Avoid: basket, trolley, bag

### customer
The person a cart belongs to.

Avoid: user, shopper

### product
Something the shop sells, named in a feature by its quoted name ("Widget").

### item
One unit of a product in a cart: a cart holding 2 of "Widget" holds 2 items.

### quantity
How many of one product a cart holds.

### checked out
Of a cart: ended. A checked-out cart is kept as the record of what was ordered, and refuses every
change after it, including being discarded.

### checkout
The customer's act of checking a cart out, and what it answers with: the products and quantities
the cart held.

### discard
The customer's act of throwing a cart away before checking out: the cart is deleted, and the same
cart starts again with nothing in it.

Avoid: abandon, cancel, clear

### checkout notice
What the service publishes when a cart is checked out: which cart, and when. Published to a topic,
where another service, or this one, reads it.

Avoid: checkout event, checkout message

### topic
Where checkout notices are published and read from, by its name.

Avoid: queue, stream

## Everyday words

add, adds, addition, remove, removes, removal, hold, holds, empty, only, line, total, already,
greater, zero, positive, leave, leaves, refuse, refused, ends, answers, starts, held, nothing,
collects, checking, cannot, again, keeps, kept, never, record, ordered, deleted, want, throwing,
away
