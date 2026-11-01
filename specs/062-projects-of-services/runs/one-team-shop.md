# Design run: a shop that needs no boundary

2026-11-01. An agent holding only the rendered `ankka-design` skill (its `SKILL.md` and
`references/`), with no other file and no code, was given this description:

> A small independent bookshop's online shop, built and run by one developer. Customers browse a
> catalogue of a few thousand books, put books in a cart, and check out; the shop records the order
> and emails a confirmation. Traffic is light and even. Everything is released together whenever the
> developer has a change ready.

**What the scenario expects** (`features/projects/designing.feature`, "a design that needs no
boundary stays one service and has no context map").

**Outcome: met.** The design opens with "One service, or several", goes through ownership, scaling
and consistency and finds none differs, adds that everything is released together, and says: "It is
one service, so there is no context map." The rest is the ordinary component table: a `book` entity
per ISBN, a `catalogue` view, an `order` entity that is the cart and then the placed order, a
confirmation consumer with an idempotency key, an `orders` view, and a public and an admin endpoint.
Payment and stock are left open as not in the brief.
