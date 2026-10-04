# Topology fixtures

The rules for reading a topology, as cases: what is shown, what is left out, where each node goes
and which nodes open another service. Two consoles apply these rules, the local console
(`cli/src/main/resources/console/topology.js`) and the installation's
(`console/package/src/ui/topology/`), and both are tested against every file here, so the two cannot
come to disagree about a rule without one suite failing.

A fixture with a `scenario` is that scenario of `features/topology/reading.feature`, which no JVM
suite can run. The local console's suite fails when a scenario in that feature has no fixture of
its name.

## A fixture

| Key | Holds |
| --- | --- |
| `scenario` | the scenario's name in `features/topology/reading.feature`, or `null` for a rule no scenario states |
| `about` | what the rule is, when there is no scenario to say it |
| `document` | a topology, as a service renders it |
| `options` | what the reader asked to see: `showPlatform`, `kinds`, `focus` |
| `services` | the names of the services running on the reader's machine, when the case is about links |
| `now` | when the topology is read, for a case about its window; when absent, the moment the window begins |
| `timeZone` | where it is read, for a time of day in a sentence; `UTC` when absent |
| `expected` | what the view must come to, by the keys in the next table |

## What `expected` may say

Only the keys a fixture gives are compared, each for equality.

| Key | Compared with |
| --- | --- |
| `shown` | the ids of the nodes in the view, in the order they are placed |
| `platform` | the ids of the shown nodes that are the platform's own |
| `positions` | each shown node's `[column, row]` |
| `declared` | the declared connections shown, each as `from>to:kind` |
| `calls` | the observed calls shown, each as `from>to` |
| `counts` | for each observed call shown, its handled and its unanswered totals |
| `marks` | for each observed call shown, `"warning"` when it is going wrong and `null` when it is not |
| `weights` | for each observed call shown, how heavily it is drawn, from 1 to 5 |
| `observedLine` | the sentence that says how far back the observed calls reach |
| `through` | for each shown node, what it does through a platform component that is left out |
| `hiddenPlatform` | how many platform components were left out |
| `links` | for each node outside the service, the local service it opens and the note beside it |
| `labels` | what each of these nodes is called on the page |
| `describe` | the one line that says what the picture holds |
