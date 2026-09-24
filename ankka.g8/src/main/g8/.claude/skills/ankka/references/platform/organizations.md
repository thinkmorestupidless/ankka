# Organizations, projects and members

> Create organizations and projects, invite members by email, manage roles, and disable or delete an organization, with the rules the control plane enforces on each.

Source: https://docs.ankka.cloud/platform/organizations/
Every service belongs to a project, and every project belongs to an organization. An organization is the
boundary of access: its members can see and change its projects and their services, and nobody else can
see that they exist. A project is a group of services that share a Kubernetes namespace and a Postgres
cluster, each service with its own database in it.

Every command on this page needs a login; see [Identity and machine accounts](identity.md).

## Create an organization and a project

```bash
ankka organizations create acme --name "Acme Corp"
ankka projects create checkout --name Checkout -O acme
ankka config set project checkout     # the default project for later commands
```

Anyone who is logged in may create an organization, and becomes its first owner. The id, `acme`, is
permanent; the display name can be changed later with `rename`.

A project id becomes part of its namespace name, `ankka-<project>`, and part of every exposed service's
hostname, so it follows DNS label rules: lowercase letters, digits and `-`, starting with a letter, and
at most 57 characters. Short project ids leave room for service names in hostnames, whose single label is
limited to 63 characters.

```bash
ankka organizations list
ankka projects list -O acme
```

Listings show only the organizations you belong to, each with your role and whether it is active.

## Roles

A member of an organization has one of two roles:

| Role | May |
|---|---|
| `member` | read the organization; create projects; apply, pause, resume, restart, expose, unexpose and delete services in its projects |
| `owner` | everything a member may, and also invite, remove and change the role of members, rename the organization and delete it |

An organization always has at least one owner: the last owner cannot be removed or made a member.

A person who is not a member of an organization gets exactly the answer they would get for one that does
not exist, a `404`, for the organization and for every project and service in it. Membership is checked
against the organization on every request, so removing someone takes effect on their very next request.

## Invite members

Members are invited by email address:

```bash
ankka organizations members add acme --email bob@example.com                 # as a member
ankka organizations members add acme --email carol@example.com --role owner
```

The invitation becomes a membership the first time a token carrying that email address arrives, with the
address marked verified by the identity provider. That happens on the person's first listing or their
first attempt to do anything. The control plane never needs to look the person up in the identity
provider, and holds no credential to do so.

The person needs an account in the installation's identity provider first; see
[Identity and machine accounts](identity.md#add-people).

```bash
ankka organizations members list acme                     # members and pending invitations
ankka organizations invitations revoke acme bob@example.com
```

## Change and remove members

Members are identified by their subject, the stable id the identity provider assigns, which
`members list` shows and `ankka whoami` prints for yourself:

```bash
ankka organizations members role acme 3f2c9a1e-... --role owner
ankka organizations members remove acme 3f2c9a1e-...
```

A subject never changes, unlike an email address or a display name, which is why it is the key.

## Platform administrators

A platform administrator holds the `platform-admin` role in the installation's identity provider. They
can see every organization and act as an owner in any of them. Three commands are theirs alone:

```bash
ankka organizations members repair acme --subject 3f2c9a1e-...   # add an owner directly
ankka organizations disable acme
ankka organizations enable acme
```

`repair` adds a member without an invitation, as an owner unless `--role member` is given. It exists for
an organization whose owners have all left.

`disable` suspends every service in every project of the organization and refuses every change; its
members can still read. The services report `Suspended`. `enable` brings back exactly what was running:
a service its members had paused before the organization was disabled stays paused.

Every change a platform administrator makes is recorded as administrative, so the history of a service
shows when the platform administrator role was what allowed it.

## Delete a project or an organization

```bash
ankka projects delete checkout           # must have no services
ankka organizations delete acme          # must have no projects; owners only
```

A project with services cannot be deleted, and neither can an organization with projects. The check
counts from a listing, so a service created moments earlier may not be counted yet; it guards against
the obvious mistake rather than being a transactional constraint.

**Organization and project ids are never reused.** Deleting one leaves a tombstone, and creating the same
id again is refused with a `409`. An id that quietly came back could carry someone else's history and
access. Service names are different: a service name is a deployment target, not a boundary, so
re-applying a deleted service's descriptor recreates it under the same name.
