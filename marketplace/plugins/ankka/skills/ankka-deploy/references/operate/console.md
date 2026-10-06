# The console

> Use an installation's web console at console.<base domain> to sign in, manage organizations, projects, members and deploy tokens, and deploy, operate and watch services without the CLI.

Source: https://docs.ankka.cloud/operate/console/
The console is a web page for an installation, at `https://console.<base domain>`. It shows what you can
see in the installation and lets you change what you are allowed to change: organizations, their projects,
members and deploy tokens, and the services in each project. It acts as you, through the same control plane
API the CLI uses, so every rule the CLI is held to holds here too, and every refusal carries the same reason.

The console is for a deployed installation. For the services running on your own machine, use
[the local console](local-console.md).

## Sign in

Open the console's address and you are sent to the installation's identity provider to sign in, with
whatever it asks of you: a password, a second factor, or a login elsewhere. You come back signed in, on the
page you asked for. The CLI's equivalent is `ankka login`; the two are separate sessions of the same account.

**Your browser holds no token.** The console keeps your session on its server and gives the browser one
cookie that page scripts cannot read. An access token lives five minutes and is renewed invisibly while you
use the console. When the identity provider ends your session, because you were idle past the realm's timeout
or an administrator ended it, the console sends you to sign in and then back to the page you were opening.

A session ended at the identity provider is noticed within the lifetime of the access token the console
already holds for you, at most five minutes on the realm as installed. Signing out of the console ends it at
once.

**Sign out** with the button at the foot of the rail. It ends your console session and your session at the
identity provider, so the next visit asks you to sign in rather than passing through silently.

## Finding your way

Every page has the same frame around it:

- **The rail**, down the left, holds the console's areas: organizations, projects, services, members and
  deploy tokens. The area the page belongs to is marked. Projects, members and deploy tokens open those of
  the organization the page belongs to, and services those of its project; on the front page, where no
  organization is open yet, they wait until you open one. Signing out is at the rail's foot.
- **The bar**, along the top, says where you are, as organization, project and service, offers the page's
  first operation (creating something, or applying a descriptor), and shows who you are signed in as.
- **The listing**, beside the page, lists what sits beside it: a project's services with each one's state
  and ready instances, or an organization's projects. The one you are reading is marked, and stays in view
  however long the list is. The front page, which is itself the list of your organizations, has none.
- **The inspector**, on the right, holds what you can do to what the page shows. Deleting is at its foot,
  behind a disclosure you open first. An operation on one row of a table, such as revoking a token, stays in
  its row, and a page that is a form, such as creating a project, keeps its form.

On a narrow screen the frame stacks in that order, and nothing is hidden. The console is dark.

## Organizations and projects

The front page lists the organizations you belong to and your role in each. A platform administrator sees
every organization in the installation, including ones they are not a member of.

Create an organization with an id and a name. The id is permanent: it cannot be renamed, and once the
organization is deleted it can never be used again. You become its first owner. The same holds for a
project's id, and the project's id is part of every exposed service's address.

An organization or project that still holds something cannot be deleted. Delete its projects, or its
services, first.

Listings are read from projections that follow changes by up to a second or two, so something created a
moment ago may be missing from a list briefly. After creating something, the console takes you to it
directly rather than to the list.

## Services

A project's page lists its services with each one's state, ready and desired instances, image, generation and
address. While the page is open it updates itself as the platform reports changes, with no reload; the page
says so while it is doing it.

A service has four sections, each a page of its own: its overview, its topology, its logs and its history.

The overview shows the service's **shape**: what the platform runs for it, joined in the direction traffic
and data flow. Its address on the installation's gateway when it is exposed, the service itself with its
generation and image, its instances with how many are ready, and its database, or that the cluster has not
reported one yet. A web-hosted service has no database; its shape shows instead each of its mounts, the
path on its hostname and the service behind it, marked when that service is missing, paused or serves no
HTTP. The service's own part carries its logs, pause or resume, and restart, the same operations the
inspector offers. Below the shape is everything the platform reports about the service. The history
section lists every apply, rollback, restart, pause and exposure, with who did it and when, and for each
apply the image it ran and a digest of its descriptor.

The state is always what the control plane reported, never what the console expected, and a state the
cluster has not confirmed is marked as the last known one. See
[Service lifecycle states](../reference/lifecycle-states.md) for what each state means.

**Apply a descriptor** by pasting a `service.json` or choosing the file. Applying creates the service, or
updates it when a service of that name exists. When the control plane refuses a descriptor, every problem it
names is listed beside the text, which is kept for you to correct.

**Roll back** from the history: a row whose descriptor differs from the one the service has now offers
**Roll back**, which says which generation and image it applies before you confirm. A rollback is a new
generation, as from the CLI; see [Pause, resume, restart, roll back and
delete](service-lifecycle.md#roll-back). A row recorded before the platform kept digests offers none,
though the CLI can still roll back to it. A row's digest opens its descriptor on the apply page, to read,
change and apply.

**Pause, resume, restart, expose and unexpose** are in the service's inspector. An exposed service's
address is a link. Deleting a service stops it and keeps its database, so applying the same name again brings
it back with its data.

**Logs** offer the CLI's choices: one instance or all of them, the container before the last restart, the last
lines, and the last seconds. While the page is open it follows new lines as the service writes them, until you
pause it. A line repeated identically within about two seconds may be shown once; a service that timestamps
its own log lines is followed exactly.

## A service's topology

A service's topology section shows what the service is made of and what calls what, read from every
running instance and merged by the control plane. The page says how many instances answered, as in
"2 of 3 instances answered". A topology missing an instance is marked **partial** and names each instance
that did not contribute and why — it did not answer in time, its runtime is too old to report one, or it
answered with something that is not a topology — and its counts are those of the instances that answered.
A component that only some instances have, during a rolling update for example, is listed with the
instances that have it and drawn as not on every instance.

Observed calls are the calls made in a recent window, not every call the service can make; the page says
how far back they reach. Handled and unanswered calls are counted apart and shown in separate columns. The
same picture is given as tables below it, and with scripts running the page follows new calls as they are
counted. A service with no running instance has no topology to show. From the CLI the same document is
`ankka services topology <name>`.

## Members and deploy tokens

An organization's members page lists its members, their roles, and the invitations waiting to be claimed.
Owners invite people by email, change roles and remove members; an organization always keeps at least one
owner. An invited person becomes a member the first time they sign in with that address, once the identity
provider has verified it.

A deploy token is a credential for a machine, such as a CI job, and appears as a member of the organization.
Owners create and revoke them on the deploy tokens page. **A new token is shown once**, on the page you land on
after creating it; reloading the page does not show it again and does not create a second one. A token lives
90 days unless you choose another lifetime, up to 365 days, or 0 for one that never expires.

## Platform administration

A platform administrator sees an extra section in each organization's inspector: disable and enable the
organization, set and clear its quota, and add an owner to an organization that has none. Disabling an
organization suspends its services; enabling it brings back what was running.

## Without scripts

Every page is rendered on the server and every operation is an ordinary form, so the console works with
scripts disabled or on a page whose scripts failed to load. The disclosures are the browser's own, the
sections are links, and every choice is the browser's own select. Without scripts, pages do not update themselves
and logs are not followed: reload to see what is new.

## What it does not show

The console shows a deployed service's topology, status, history and logs. Its traces, sessions and entity
state are not shown; for a service on your own machine, [the local console](local-console.md) shows them.
