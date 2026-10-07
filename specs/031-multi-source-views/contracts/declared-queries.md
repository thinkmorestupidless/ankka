# Contract: declared queries

A view declares the queries it can be asked beyond one row and every row. This is the contract
between a developer's declaration, the check at startup, and the read at the call. It holds for a
view written in Scala and for one discovered from a process or a module alike: both arrive at
`QueryCheck` as a view's table, a name and a statement.

## What is declared

| Part | Rule |
|---|---|
| name | A wire name, declared like a command's. Non-empty, `[a-z0-9-]+`. Not `get`, `all`, `where`, `ordered` or `count`, which are the fixed ways of asking and are counted under those names. Unique within the view. |
| statement | One SQL statement, text. Written with the view's table by its real name (`ankka_view_<id>`, every character of the id that is not a letter or digit folded to `_`), which every SDK offers as a value so nobody spells it by hand. |
| values | Not declared separately. They are the named parameters the statement holds, `:name`, `[a-z][a-z0-9_]*`. A value is text; a statement that needs a number casts (`(:depth)::int`). |

The answer is rows of the view's own row type, so the statement's result must have a column named
`payload` holding a row as the view stores it. Any other column is ignored.

## Q1–Q9: what the check refuses, at startup

`QueryCheck.check(table, name, statement)` is pure: it parses, and reads nothing. A service with a
problem does not start, and the problem names the view, the query, and what is wrong. Checked in
`ComponentRegistry.validate`, where every other rule about a registered component is.

| Rule | Refused | Problem says |
|---|---|---|
| Q1 | a statement the parser cannot read | the parser's message and position |
| Q2 | more than one statement | "holds N statements; a query is one" |
| Q3 | a statement that is not a `SELECT` (with or without `WITH`) | what kind it is: `UPDATE`, `DELETE`, `INSERT`, … |
| Q4 | a `WITH` item that is not a `SELECT` (a data-modifying CTE) | the item's name and kind |
| Q5 | `SELECT … INTO`, `FOR UPDATE`, `FOR SHARE` and their variants | which |
| Q6 | a relation that is neither the view's own table nor a `WITH` item of this statement | **the table's name** |
| Q7 | a relation named with a schema, even the view's own | the qualified name; "name the table alone" |
| Q8 | a function that takes a query or a relation as text: `query_to_xml*`, `table_to_xml*`, `schema_to_xml*`, `database_to_xml*`, `cursor_to_xml*`, `dblink*`, `pg_read_file`, `pg_read_binary_file`, `pg_ls_dir`, `pg_stat_file`, `lo_*` | the function |
| Q9 | a query named as a fixed way of asking, or twice in one view; a value name that is not `[a-z][a-z0-9_]*` | the name |

Identifiers are compared as Postgres reads them: an unquoted name folded to lower case, a quoted
one as written.

**What the check is not.** It holds a developer's statement to the view's own table as parsed, and
the database holds it to reading (see *At the call*). It is a guard for the developer who wrote
the statement, not a wall against them: the service's database is the service's own, and its
Scala code can already run any statement against it. Q8 exists because without it Q6 could be
true of the text and false of what runs.

A name inside a comment or a string literal is not a relation and is never refused: the check
reads the parsed statement, never the text.

## Values, and how they are bound

`QueryCheck` returns the statement with each `:name` replaced by a positional `$n`, and the names
in order of first appearance. The replacement is made by a scanner over the text that skips
single-quoted strings, quoted identifiers, dollar-quoted strings, line and block comments, and
`::` casts. The names the scanner finds must be exactly the named parameters the parser found, or
the statement is refused (Q1's problem, saying the two disagree): neither is trusted alone.

A value is bound as a parameter and is never part of the statement's text.

## At the call

| Step | Rule |
|---|---|
| C1 | A query the view does not declare is refused before anything is sent: `NotFound`, naming the view and the query. |
| C2 | The values given must be exactly the values the query takes. A missing one, or one it does not take, is refused before anything is sent: `BadRequest`, naming the value. |
| C3 | The statement runs in a transaction of its own that is `READ ONLY`, with `statement_timeout` set for that transaction to the service's ask timeout less 500 ms, never under 1 s. The database therefore refuses a write whatever the statement calls, and ends a statement that does not end. |
| C4 | At most `limit` rows are read (default 1000, the other reads' default), in the statement's own order. The statement is not wrapped or rewritten to apply it. |
| C5 | A statement the database ends for time is `Timeout`, naming the view and the query. Any other database error is `Internal` with the database's message. |
| C6 | A result with no `payload` column is `Internal`, naming the view and the query: it is a statement that should not have been declared, found at its first call because only the database knows its columns. |
| C7 | Counted as a call to the view under the query's name, from whoever asked, as `get` and `where` are. The names are bounded because they are declared. |

## A recursive query

Nothing more than a declared query whose statement is `WITH RECURSIVE`. The `WITH` item it
defines is a relation of the statement and passes Q6; the table it reads must still be the view's
own. One that follows a cycle forever is ended by C3 and answered by C5.

## From a view's own handler

A keyed view's handler asks its own view's declared queries, and reads its own rows by key,
through the handle it is given for the change (see [keyed-views.md](keyed-views.md)). Those reads
are ordinary asks, C1–C7, on a connection of their own, so they see what is committed. That is
everything there is to see: the change being handled has written nothing yet, since a handler
reads and then returns what to write, and nothing else can commit to the view's table while the
change holds the view's lock (O1). The handle reaches no other view: asking it a query of another
view's is C1's refusal.

## What does not change

`get`, `all`, and in Scala `where`, `ordered` and `count`, behave exactly as before. A view that
declares no query is hosted as before.
