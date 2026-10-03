# Glossary

The words this project's features use, each in exactly one sense.

### ankka
*Proposed.* The platform these features describe: what a service is built on, and what runs it.

### service
*Proposed.* One program built on ankka and run as a unit: its entities, views, consumers and
endpoints together.

Avoid: application, app, microservice

### project
*Proposed.* The tenancy boundary a deployed service belongs to. A service's name is unique within
its project.

### deployed service
*Proposed.* A service running on the platform, which knows its project and its name because the
platform tells it.

### local service
*Proposed.* A service run on a developer's machine, outside the platform. It has no project, and a
name only when it states one.

### instance
*Proposed.* One running copy of a service. A service with several instances shares its work
between them.

Avoid: node, pod, replica

### entity
*Proposed.* A component that holds state under an id and records every change to it. A view or
consumer may read an entity's changes instead of a topic.

### view
*Proposed.* A component that keeps rows built from the changes it reads, for a service to query.

Avoid: projection, read model

### consumer
*Proposed.* A component that acts on each change it reads and keeps no rows.

Avoid: subscriber, listener

### kind
*Proposed.* Which sort of component something is. In these features, a view or a consumer.

### id
*Proposed.* The name a project, a service, a view or a consumer is declared with.

### row
*Proposed.* One entry a view holds, written by the view from a message it read.

Avoid: record, entry

### broker
*Proposed.* The system that holds topics and delivers their messages. One broker may serve many
services.

### topic
*Proposed.* A named stream of messages on a broker.

Avoid: queue, stream, channel

### partition
*Proposed.* One ordered part of a topic. A group's members divide a topic's partitions between
them.

### message
*Proposed.* One thing published to a topic.

Avoid: event, record

### subject
*Proposed.* What a message is about. A view holds one row for each subject it has read.

### topic source
*Proposed.* A view's or a consumer's declaration that it reads a topic, with the start position
and the version it reads at.

### group
*Proposed.* The name under which a topic source reads a topic. The broker delivers each message to
one member of a group, and remembers for each group how far it has read.

Avoid: consumer group, subscription

### subscribes
*Proposed.* Of a view or consumer: begins reading its topic under its group.

### start position
*Proposed.* Where a topic source begins reading the first time its group reads the topic:
"earliest", "latest", or a time. It does not apply once the group has read anything.

Avoid: offset reset, initial offset

### earliest
*Proposed.* Of a message: the oldest the broker still retains. As a start position: begin there.

### latest
*Proposed.* Of a message: the newest on the topic. As a start position: begin after it, reading
only what is published from then on.

### retained
*Proposed.* Of a message: still held by the broker. A broker retains messages for a bounded span
and then drops them, so a topic is not a complete record.

Avoid: kept, stored

### version
*Proposed.* A positive whole number a topic source declares, which only goes up. A higher version
means the rows an earlier version wrote are wrong and the view is to be rebuilt.

Avoid: revision, generation

### recorded version
*Proposed.* The version a view's rows were last built at, as the service has stored it.

### rebuild
*Proposed.* Emptying a view and reading its topic again from its start position under a new
group, when the view is declared at a higher version than its recorded version. A rebuild reaches
back only as far as the broker retains.

Avoid: replay, rewind, reset

### emptied
*Proposed.* Of a view: every row removed at once, at the start of a rebuild.

Avoid: truncated, cleared

### behind
*Proposed.* Of a view on one instance: declared at a lower version than its recorded version. A
view that is behind reads nothing and writes nothing, and is never rebuilt downward.

### registers
*Proposed.* A service hands a component to ankka at startup. A component ankka refuses is refused
there, before the service takes any request.

### registration
*Proposed.* The act of a service registering a component.

### refused
*Proposed.* Not accepted, with the reason given to whoever asked.

Avoid: rejected, denied

### rolling update
*Proposed.* Replacing a service's instances one at a time, so that old and new instances run
beside each other until the last old one stops.

### rolled back
*Proposed.* Of a service: deployed again as an earlier build, after a later one.

### upgraded
*Proposed.* Of a service: deployed again on a later release of ankka.

### ready
*Proposed.* Of a service: able to take requests.

### log
*Proposed.* The lines a running service writes about what it is doing, read by whoever operates
it.

### metrics
*Proposed.* The numbers a running service publishes about itself for a monitoring system to read.

### template
*Proposed.* What a new project is made from, one for each language a service can be written in.

### language
*Proposed.* What a service is written in: "scala", "python", "typescript" or "rust".

### release
*Proposed.* One published version of ankka. A service is built on one release and may run on a
platform at a later one.

### documentation
*Proposed.* The published pages that say how to build, deploy and operate a service on ankka.

### limitations
*Proposed.* The part of the documentation that lists what ankka does not do.

### reader
*Proposed.* A person or a model reading the documentation.

## Everyday words

reading, reads, read, hold, holds, holding, each, every, one, two, same, distinct, published,
between, once, named, name, names, naming, states, alone, made, runs, running, starts, starting,
start, again, under, new, left, was, were, longest, permitted, accepts, declaring, declared,
declare, first, time, last, after, since, goes, only, what, while, stopped, stops, restarted,
restarts, already, delivered, written, missing, honoured, earlier, later, now, higher, lower,
positive, whole, number, taken, during, together, beside, leaves, more, says, far, back, before,
removes, shows, shown, lists, none, looks, up, describes, default, reaches, bounded, changed,
must, upgrading, has, have, been, with, its, is, are, be, not, no, how, so, as, it, on, in, of,
to, for, from, at, by, that, than, the, a, an, and, or, does, until, empty, nobody, whether, they,
whose, created, creation, because, built, cannot, reserved
