# Glossary

The words the platform's features use, each in exactly one sense. A term marked *Proposed.* has
still to be settled by `/speckit-clarify`; none is at present. The platform's established words are defined as
`docs/reference/glossary.md` defines them for the people who build on it. The shopping cart sample
has a glossary of its own, in `samples/shopping-cart/`.

## The platform

### platform
What runs deployed services and is told what they should be: the installation a member's projects
are in.

### service
A set of components registered together and run as one.

### component
A unit of a service that the platform hosts: an event sourced entity, a key value entity, a view,
a consumer, a workflow, a timed action, an agent or an endpoint.

### handler
A part of a component that the platform runs: a command, a step, a timed action's action or an
agent's handler. Each is declared with a name.

### entity
An event sourced entity or a key value entity.

### command
A handler that may change the state of an entity or of a workflow.

### workflow
A component that runs a process of several steps and survives a restart part way through.

### step
One unit of a workflow's work. A step may call other components.

### event
A fact an event sourced entity recorded, such as an item having been added.

### state
What an entity knows now.

### view
A component that keeps a table it can be asked questions of, built from the events or the state
it reads.

### timer
A call set to be made later.

### instance
One running copy of a service.

### database
Where the platform keeps what a service's entities, views and workflows know, and the service's
secret store.

### process
The developer's program in a service whose image is written in another language and run beside
the platform's own program.

Avoid: app, application server

### module
A service built as a WebAssembly module that the platform's own program loads. It has
no environment of its own and reads its configuration from the platform, which withholds its own
settings.

### refusal
A deliberate "no": a handler's to a call, or the platform's to a person who may not have what they
asked for. A refusal is the service working, not the service failing.

### refused
Of a handled call: it ended in a refusal. Of a person: given a refusal.

## People

### developer
A person building a service, who runs it on their own machine.

Avoid: user

### organization
The boundary of membership: who may operate the projects in it. Deleting one leaves its id taken.

### project
A group of services deployed together, which a member may operate.

### member
A person, or a machine acting for one, who belongs to the organization a project is in and may
operate its services.

### deploy token
A credential a machine holds to act as a member of an organization.

### person
Someone who sends a request to a service or to the platform: one of a service's own users, or a
member.

## Deploying

### control plane
The part of the platform that members operate their organizations, projects and
services through.

### descriptor
What a member writes to say what a service should be: its image, its hosting, its environment, its
size and how many instances it has.

### apply
Of a descriptor: give it to the platform, which makes the service what the descriptor says.

### environment
The named values a descriptor gives a service's image when it runs.

### variable
One named value of an environment.

### deployed
Of a service: running in a project on the platform, not on a developer's machine.

### ready
Of a service or an instance: started and able to serve.

### status
What the platform last saw of a deployed service, with one word for the whole: "Ready", "Paused",
"Failed" and the others.

### platform setting
A variable the platform's own program reads. Some the platform alone sets, and a
descriptor may not give them; the others a descriptor may give, and they are for the platform's
program and never for the developer's: a process is not given them, and a module that asks for one
is told that it is not set.

Avoid: reserved variable, platform variable

## Secrets

### secret store
Where a service keeps its service secrets: in its database, and apart from everything
its components know. It is not a component, and nothing a component records or a view is built
from ever holds what is in it. An entity and a view are given none; every other component of the
service keeps and reads through the same one.

Avoid: vault

### service secret
A named value a service keeps in its secret store while it runs and reads back by that
name, such as a credential a person gave it. The value is text, never empty, and no larger than
the secret store's limit. It belongs to the one service that kept it; another
service cannot read it. It is not a project secret, which a member sets before a service starts.

Avoid: runtime secret

### secret key
What a service's secret store encrypts its service secrets with. Each service has its
own. The platform makes one for a deployed service unless its descriptor gives one, and keeps it
when the service is deleted; on a developer's machine the developer gives one. It is a platform
setting. It is not an entry of a project secret, and it is not an issuer's keys.

Avoid: encryption key, master key

### encrypted
Of a service secret as the database holds it: unreadable by anyone who does not have
the service's secret key.

### project secret
A named set of entries the platform keeps for a project, which a descriptor's variable
can be taken from. A member sets and removes its entries; the platform gives its values to a
service's instances when they start and shows them to nobody. A project secret with no entry left
is no longer listed.

Avoid: static secret

### entry
One named value of a project secret. A descriptor's variable is taken from one entry.

## Everyday words

read, reads, reading, keep, keeps, kept, keeping, remove, removes, removed, hold, holds, told,
tell, tells, ask, asks, asked, give, gives, gave, given, make, makes, made, set, sets, show, shows,
shown, list, lists, start, starts, started, restarted, restart, restarts, become, becomes, name,
names, named, value, values, nothing, nowhere, since, afterwards, again, later, once, fail, fails,
failure, limit, larger, longer, rule, break, breaks, slash, use, uses, reason, written, language, machine, holding, own, never, what,
who, why, how, however, which, whose, where, with, without, through, alike, exactly, newly, else,
exist, exists, try, tries, say, says, said, is, are, does, did, can, cannot, there, such, present,
record, records, recorded, take, takes, taken, delete, deleted, change, changed, new, another,
other, one, two, each, every, none, no, not, only, still, has, have, had, been, be, was, were, it,
its, that, this, them, they, of, and, or, a, an, the, to, in, on, by, as, at, for, from, back
