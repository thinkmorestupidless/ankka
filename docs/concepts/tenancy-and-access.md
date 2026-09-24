---
title: Tenancy and access
description: How organizations, projects and services divide an installation, who may operate each of them, and how the identity provider and the control plane share the work of authentication and authorization.
kind: concept
related: [platform/organizations.md, platform/identity.md, deploy/ci.md, build/http-endpoints.md]
---

# Tenancy and access

An ankka installation is divided into **organizations**, which contain **projects**, which contain
**services**. People are members of organizations. Every call to the control plane carries a token from
the installation's identity provider, and the control plane decides, from its own state, what that caller
may do.

This page is about who may operate the platform. Who may call a deployed service's HTTP endpoints is a
different question, answered by each endpoint's ACL; see [HTTP endpoints](../build/http-endpoints.md).

## Organizations, projects and services

**An organization is the boundary of access.** Membership is per organization, and a member can act on
every project in it.

**A project groups services and gives them a namespace.** Each project gets its own Kubernetes namespace,
and a service is addressed within its project: two projects may each have a service called `cart`. A
project's services each get their own database, provisioned by the platform. Projects separate names and
data, but not network traffic: a service in one project can call a service in another by its in-cluster
address.

**A service is a deployment target**, described by its descriptor.

## Deleted tenancy ids are not reused; service names are

Deleting an organization or a project leaves a tombstone. The id stays taken, and creating it again is
refused with a conflict. A tenancy boundary that quietly came back carrying someone else's history would
be worse than an error. An organization can be deleted only once it has no projects, and a project only
once it has no services.

A service name is different. It is a deployment target, not a boundary, so applying a descriptor for a
name you deleted creates the service again. Its generation keeps counting from where it was, so its
history is still there, and its database, which deletion never removes, is recovered.

## Authentication and authorization are separate

**The identity provider authenticates.** Every installation runs its own Keycloak, deployed with the rest
of the platform. It decides who is a user of the installation, signs their tokens, and holds their
passwords, sessions and second factors. Self-registration is off; an administrator adds people in its
console. The control plane never sees a password.

**The control plane authorizes.** It verifies each token offline against the identity provider's
published keys, takes the token's subject as the caller's identity, and answers every question about
organizations and membership from its own entities. It holds no administrative credential for the
identity provider and needs none. Only the token's subject is used as a key; a name or an email address
is for display.

`ankka login` signs you in with the OAuth 2.0 device authorization grant: the CLI prints an address and a
code, and you sign in in any browser, on any device. The login is saved per control plane URL, readable
only by you, and renewed without a browser. A machine such as a CI job uses a token it obtained itself
from a Keycloak client; see [Deploy from CI](../deploy/ci.md).

## Roles

Each member of an organization is an **owner** or a **member**.

| Action | Member | Owner |
|---|---|---|
| Read the organization, its projects and services | yes | yes |
| Create, rename and delete projects | yes | yes |
| Apply, pause, resume, restart, expose, unexpose and delete services; read their logs and history | yes | yes |
| Invite, remove and change the role of members | no | yes |
| Rename or delete the organization | no | yes |

Anyone signed in may create an organization and becomes its first owner. Owners invite people by email.
An invitation becomes a membership the first time a token carrying that email address, verified by the
identity provider, reaches the control plane. The last owner of an organization cannot be removed or
demoted.

Membership is checked on every request against the organization's own entity, never against a listing,
so removing someone takes effect on their very next request.

**What you cannot see does not exist.** For an organization, project or service you are not a member of,
the control plane answers exactly as it would for one that was never created: `404`. An outsider learns
nothing about which ids are in use. A member who lacks the owner role for an action gets `403`.

## Platform administrators

One role lives in the identity provider rather than the control plane: `platform-admin`, a realm role for
the people who run the installation. A platform administrator can see every organization, add an owner
to an organization whose owners have all left, and **disable** an organization.

Disabling an organization suspends every service in its projects. Its members can still read everything,
but every change is refused with a conflict. Enabling it again brings back exactly what was running: a
service its members had paused stays paused. A suspended service reports `Suspended` rather than `Paused`,
so it is always clear who stopped it.

## Every change is attributed

Every command recorded by the control plane carries who asked for it, when, and whether it was the
platform-admin role rather than membership that allowed it. `ankka services history` reads that back for
a service, newest first. See [Status and history](../operate/status-and-history.md).
