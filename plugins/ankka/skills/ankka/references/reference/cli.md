# CLI

> Every `ankka` command and option, how the CLI resolves its settings and credentials, its output formats and its exit codes.

Source: https://docs.ankka.cloud/reference/cli/
`ankka` is the command-line client for an ankka control plane, plus three local tools: `ankka init`
creates a service from the template, `ankka local console` serves a console over the services
running on your own machine, and `ankka mcp` serves the CLI's service commands, the local services and
this documentation to a coding agent over the Model Context Protocol
([Work with a coding agent](../get-started/coding-agents.md)). Every other command makes one HTTP call to the control plane and prints
the result. The CLI holds no state of its own beyond a settings file and a credentials file.

## How settings are resolved

Each setting is taken from the first place that has it: a command-line flag, then an environment
variable, then the saved settings file.

| Setting | Flag | Environment variable | Saved with | Default |
|---|---|---|---|---|
| Control plane URL | `--url` | `ANKKA_URL` | `ankka config set url <url>` | `http://localhost:9000` |
| Bearer token | `--token` | `ANKKA_TOKEN` | `ankka config set token <token>` | none |
| Project | `--project`, `-p` | `ANKKA_PROJECT` | `ankka config set project <id>` | none |
| Extra trusted CA file | none | `ANKKA_CA` | `ankka config set ca <path>` | none |

The environment sits between flags and the file so that a CI job can point the CLI at a different
control plane without writing to a home directory it may not have. `ankka config get` shows the
effective settings, and `ankka config unset <key>` clears `token`, `project` or `ca`, or resets `url`
to its default.

`ca` names a PEM file of certificate authorities to trust in addition to the system's, for the control
plane's URL. A local platform exports its root to `~/.ankka/local-ca.crt`. There is no option to turn
certificate verification off.

A command that acts on a service needs a project. Without one it fails with a message naming
`--project` and `ankka config set project`, rather than sending a request that would come back `404`.

## Files

The settings file is `~/.ankka/config.json`. `ANKKA_CONFIG` names a different file; from inside a JVM,
the system property `-Dankka.config` takes precedence over both.

Saved logins live in `credentials.json` in the same directory as the settings file, keyed by control
plane URL, so a login for one installation is never presented to another. The file is readable by its
owner only, where the filesystem supports permissions. It is separate from the settings file because
`ankka config get` prints the settings and a credential is never printed.

Setting `HOME` for one command does not relocate either file, because the JVM resolves `~` once at
start. Use `ANKKA_CONFIG` to isolate a run.

## Credentials

The token a command presents is chosen in this order:

1. `--token` or `ANKKA_TOKEN`, presented exactly as given. No saved login is read or written. This is
   how a CI job authenticates, with a token it obtained itself.
2. The saved login for the control plane URL: its access token while it has time left, otherwise a
   silent refresh, which is saved back.
3. Otherwise the command fails with `run 'ankka login'`.

`ankka login` runs the OAuth 2.0 device authorization grant against the installation's identity
provider, which it finds by asking the control plane's unauthenticated `GET /auth`. It prints an
address and a code to enter in any browser, on any device. `--no-browser` skips trying to open one.
`ankka logout` forgets the saved login for the current URL and revokes it at the identity provider when
it can; `ankka logout --all` forgets every saved login. `ankka whoami` shows who the control plane
thinks you are.

The token is never printed, in either output format.

## Output

`--output table` (`-o table`, the default) prints aligned columns for a person. `--output json`
(`-o json`) prints the control plane's response as JSON, for a script:

```bash
ankka services list -o json | jq '.[].lifecycle'
```

`ankka services expose` prints only the service's URL in table format, and the whole service status in
JSON format.

## Exit codes

| Code | Meaning |
|---|---|
| `0` | The command succeeded. Its result is on standard output. |
| `1` | The command failed: the control plane refused it, could not be reached, or a local check failed. The reason is on standard error, prefixed `error: `. |
| `2` | The command line was not understood. Usage is on standard error. |

A descriptor passed to `ankka services apply -f` is validated before it is sent, with the same rules
the control plane applies, and every problem is reported at once. `-f -` reads the descriptor from
standard input.

## Commands

The help below is generated from the CLI's own command tree, so it is exactly what `--help` prints.

### `ankka`

```text
Usage:
    ankka login
    ankka logout
    ankka whoami
    ankka organizations
    ankka projects
    ankka services
    ankka config
    ankka version
    ankka init
    ankka local
    ankka mcp

Operate an ankka control plane.

Options and flags:
    --help
        Display this help text.

Subcommands:
    login
        Log in through the installation's identity provider.
    logout
        Forget the saved login for the control plane.
    whoami
        Show who the control plane thinks you are.
    organizations
        Manage organizations.
    projects
        Manage projects.
    services
        Manage services.
    config
        Read and write the saved settings.
    version
        Print the ankka version of this CLI.
    init
        Create a new service from the ankka template (runs `sbt new`; needs sbt on PATH).
    local
        Tools for services running on this machine.
    mcp
        Serve ankka's tools and documentation to an agent over MCP (stdio).
```

### `ankka login`

```text
Usage: ankka login [--url <string>] [--no-browser]

Log in through the installation's identity provider.

Options and flags:
    --help
        Display this help text.
    --url <string>
        Control plane base URL. Defaults to the configured value.
    --no-browser
        Print the address and code; do not try to open a browser.
```

### `ankka logout`

```text
Usage: ankka logout [--url <string>] [--all]

Forget the saved login for the control plane.

Options and flags:
    --help
        Display this help text.
    --url <string>
        Control plane base URL. Defaults to the configured value.
    --all
        Forget every saved login.
```

### `ankka whoami`

```text
Usage: ankka whoami [--url <string>] [--token <string>] [--project <string>] [--output <string>]

Show who the control plane thinks you are.

Options and flags:
    --help
        Display this help text.
    --url <string>
        Control plane base URL. Defaults to the configured value.
    --token <string>
        Bearer token. Prefer ANKKA_TOKEN or the config file.
    --project <string>, -p <string>
        Project id. Defaults to the configured project.
    --output <string>, -o <string>
        Output format: table or json.
```

### `ankka organizations`

```text
Usage:
    ankka organizations list
    ankka organizations get
    ankka organizations create
    ankka organizations rename
    ankka organizations delete
    ankka organizations members
    ankka organizations invitations
    ankka organizations disable
    ankka organizations enable

Manage organizations.

Options and flags:
    --help
        Display this help text.

Subcommands:
    list
        List every organization.
    get
        Show one organization.
    create
        Create an organization.
    rename
        Change an organization's display name.
    delete
        Delete an organization. It must have no projects.
    members
        Who belongs to an organization.
    invitations
        Pending invitations.
    disable
        Stop every service in the organization and refuse changes (platform administrators only).
    enable
        Re-enable a disabled organization (platform administrators only).
```

### `ankka organizations list`

```text
Usage: ankka organizations list [--url <string>] [--token <string>] [--project <string>] [--output <string>]

List every organization.

Options and flags:
    --help
        Display this help text.
    --url <string>
        Control plane base URL. Defaults to the configured value.
    --token <string>
        Bearer token. Prefer ANKKA_TOKEN or the config file.
    --project <string>, -p <string>
        Project id. Defaults to the configured project.
    --output <string>, -o <string>
        Output format: table or json.
```

### `ankka organizations get`

```text
Usage: ankka organizations get [--url <string>] [--token <string>] [--project <string>] [--output <string>] <id>

Show one organization.

Options and flags:
    --help
        Display this help text.
    --url <string>
        Control plane base URL. Defaults to the configured value.
    --token <string>
        Bearer token. Prefer ANKKA_TOKEN or the config file.
    --project <string>, -p <string>
        Project id. Defaults to the configured project.
    --output <string>, -o <string>
        Output format: table or json.
```

### `ankka organizations create`

```text
Usage: ankka organizations create --name <string> [--url <string>] [--token <string>] [--project <string>] [--output <string>] <id>

Create an organization.

Options and flags:
    --help
        Display this help text.
    --name <string>
        Display name.
    --url <string>
        Control plane base URL. Defaults to the configured value.
    --token <string>
        Bearer token. Prefer ANKKA_TOKEN or the config file.
    --project <string>, -p <string>
        Project id. Defaults to the configured project.
    --output <string>, -o <string>
        Output format: table or json.
```

### `ankka organizations rename`

```text
Usage: ankka organizations rename --name <string> [--url <string>] [--token <string>] [--project <string>] [--output <string>] <id>

Change an organization's display name.

Options and flags:
    --help
        Display this help text.
    --name <string>
        Display name.
    --url <string>
        Control plane base URL. Defaults to the configured value.
    --token <string>
        Bearer token. Prefer ANKKA_TOKEN or the config file.
    --project <string>, -p <string>
        Project id. Defaults to the configured project.
    --output <string>, -o <string>
        Output format: table or json.
```

### `ankka organizations delete`

```text
Usage: ankka organizations delete [--url <string>] [--token <string>] [--project <string>] [--output <string>] <id>

Delete an organization. It must have no projects.

Options and flags:
    --help
        Display this help text.
    --url <string>
        Control plane base URL. Defaults to the configured value.
    --token <string>
        Bearer token. Prefer ANKKA_TOKEN or the config file.
    --project <string>, -p <string>
        Project id. Defaults to the configured project.
    --output <string>, -o <string>
        Output format: table or json.
```

### `ankka organizations members`

```text
Usage:
    ankka organizations members list
    ankka organizations members add
    ankka organizations members remove
    ankka organizations members role
    ankka organizations members repair

Who belongs to an organization.

Options and flags:
    --help
        Display this help text.

Subcommands:
    list
        List members and pending invitations.
    add
        Invite an email address; membership starts on their first verified login.
    remove
        Remove a member.
    role
        Change a member's role.
    repair
        Add a member directly (platform administrators only).
```

### `ankka organizations members list`

```text
Usage: ankka organizations members list [--url <string>] [--token <string>] [--project <string>] [--output <string>] <organization>

List members and pending invitations.

Options and flags:
    --help
        Display this help text.
    --url <string>
        Control plane base URL. Defaults to the configured value.
    --token <string>
        Bearer token. Prefer ANKKA_TOKEN or the config file.
    --project <string>, -p <string>
        Project id. Defaults to the configured project.
    --output <string>, -o <string>
        Output format: table or json.
```

### `ankka organizations members add`

```text
Usage: ankka organizations members add --email <string> [--role <string>] [--url <string>] [--token <string>] [--project <string>] [--output <string>] <organization>

Invite an email address; membership starts on their first verified login.

Options and flags:
    --help
        Display this help text.
    --email <string>
        The address to invite.
    --role <string>
        owner or member.
    --url <string>
        Control plane base URL. Defaults to the configured value.
    --token <string>
        Bearer token. Prefer ANKKA_TOKEN or the config file.
    --project <string>, -p <string>
        Project id. Defaults to the configured project.
    --output <string>, -o <string>
        Output format: table or json.
```

### `ankka organizations members remove`

```text
Usage: ankka organizations members remove [--url <string>] [--token <string>] [--project <string>] [--output <string>] <organization> <subject>

Remove a member.

Options and flags:
    --help
        Display this help text.
    --url <string>
        Control plane base URL. Defaults to the configured value.
    --token <string>
        Bearer token. Prefer ANKKA_TOKEN or the config file.
    --project <string>, -p <string>
        Project id. Defaults to the configured project.
    --output <string>, -o <string>
        Output format: table or json.
```

### `ankka organizations members role`

```text
Usage: ankka organizations members role --role <string> [--url <string>] [--token <string>] [--project <string>] [--output <string>] <organization> <subject>

Change a member's role.

Options and flags:
    --help
        Display this help text.
    --role <string>
        owner or member.
    --url <string>
        Control plane base URL. Defaults to the configured value.
    --token <string>
        Bearer token. Prefer ANKKA_TOKEN or the config file.
    --project <string>, -p <string>
        Project id. Defaults to the configured project.
    --output <string>, -o <string>
        Output format: table or json.
```

### `ankka organizations members repair`

```text
Usage: ankka organizations members repair --subject <string> [--role <string>] [--url <string>] [--token <string>] [--project <string>] [--output <string>] <organization>

Add a member directly (platform administrators only).

Options and flags:
    --help
        Display this help text.
    --subject <string>
        The user's subject id, as `ankka whoami` shows it.
    --role <string>
        owner or member.
    --url <string>
        Control plane base URL. Defaults to the configured value.
    --token <string>
        Bearer token. Prefer ANKKA_TOKEN or the config file.
    --project <string>, -p <string>
        Project id. Defaults to the configured project.
    --output <string>, -o <string>
        Output format: table or json.
```

### `ankka organizations invitations`

```text
Usage: ankka organizations invitations revoke

Pending invitations.

Options and flags:
    --help
        Display this help text.

Subcommands:
    revoke
        Withdraw an invitation that has not been claimed.
```

### `ankka organizations invitations revoke`

```text
Usage: ankka organizations invitations revoke [--url <string>] [--token <string>] [--project <string>] [--output <string>] <organization> <email>

Withdraw an invitation that has not been claimed.

Options and flags:
    --help
        Display this help text.
    --url <string>
        Control plane base URL. Defaults to the configured value.
    --token <string>
        Bearer token. Prefer ANKKA_TOKEN or the config file.
    --project <string>, -p <string>
        Project id. Defaults to the configured project.
    --output <string>, -o <string>
        Output format: table or json.
```

### `ankka organizations disable`

```text
Usage: ankka organizations disable [--url <string>] [--token <string>] [--project <string>] [--output <string>] <id>

Stop every service in the organization and refuse changes (platform administrators only).

Options and flags:
    --help
        Display this help text.
    --url <string>
        Control plane base URL. Defaults to the configured value.
    --token <string>
        Bearer token. Prefer ANKKA_TOKEN or the config file.
    --project <string>, -p <string>
        Project id. Defaults to the configured project.
    --output <string>, -o <string>
        Output format: table or json.
```

### `ankka organizations enable`

```text
Usage: ankka organizations enable [--url <string>] [--token <string>] [--project <string>] [--output <string>] <id>

Re-enable a disabled organization (platform administrators only).

Options and flags:
    --help
        Display this help text.
    --url <string>
        Control plane base URL. Defaults to the configured value.
    --token <string>
        Bearer token. Prefer ANKKA_TOKEN or the config file.
    --project <string>, -p <string>
        Project id. Defaults to the configured project.
    --output <string>, -o <string>
        Output format: table or json.
```

### `ankka projects`

```text
Usage:
    ankka projects list
    ankka projects get
    ankka projects create
    ankka projects rename
    ankka projects delete

Manage projects.

Options and flags:
    --help
        Display this help text.

Subcommands:
    list
        List projects, optionally in one organization.
    get
        Show one project.
    create
        Create a project.
    rename
        Change a project's display name.
    delete
        Delete a project. It must have no services.
```

### `ankka projects list`

```text
Usage: ankka projects list [--organization <string>] [--url <string>] [--token <string>] [--project <string>] [--output <string>]

List projects, optionally in one organization.

Options and flags:
    --help
        Display this help text.
    --organization <string>, -O <string>
        Organization id.
    --url <string>
        Control plane base URL. Defaults to the configured value.
    --token <string>
        Bearer token. Prefer ANKKA_TOKEN or the config file.
    --project <string>, -p <string>
        Project id. Defaults to the configured project.
    --output <string>, -o <string>
        Output format: table or json.
```

### `ankka projects get`

```text
Usage: ankka projects get [--url <string>] [--token <string>] [--project <string>] [--output <string>] <id>

Show one project.

Options and flags:
    --help
        Display this help text.
    --url <string>
        Control plane base URL. Defaults to the configured value.
    --token <string>
        Bearer token. Prefer ANKKA_TOKEN or the config file.
    --project <string>, -p <string>
        Project id. Defaults to the configured project.
    --output <string>, -o <string>
        Output format: table or json.
```

### `ankka projects create`

```text
Usage: ankka projects create --name <string> --organization <string> [--url <string>] [--token <string>] [--project <string>] [--output <string>] <id>

Create a project.

Options and flags:
    --help
        Display this help text.
    --name <string>
        Display name.
    --organization <string>, -O <string>
        Organization id.
    --url <string>
        Control plane base URL. Defaults to the configured value.
    --token <string>
        Bearer token. Prefer ANKKA_TOKEN or the config file.
    --project <string>, -p <string>
        Project id. Defaults to the configured project.
    --output <string>, -o <string>
        Output format: table or json.
```

### `ankka projects rename`

```text
Usage: ankka projects rename --name <string> [--url <string>] [--token <string>] [--project <string>] [--output <string>] <id>

Change a project's display name.

Options and flags:
    --help
        Display this help text.
    --name <string>
        Display name.
    --url <string>
        Control plane base URL. Defaults to the configured value.
    --token <string>
        Bearer token. Prefer ANKKA_TOKEN or the config file.
    --project <string>, -p <string>
        Project id. Defaults to the configured project.
    --output <string>, -o <string>
        Output format: table or json.
```

### `ankka projects delete`

```text
Usage: ankka projects delete [--url <string>] [--token <string>] [--project <string>] [--output <string>] <id>

Delete a project. It must have no services.

Options and flags:
    --help
        Display this help text.
    --url <string>
        Control plane base URL. Defaults to the configured value.
    --token <string>
        Bearer token. Prefer ANKKA_TOKEN or the config file.
    --project <string>, -p <string>
        Project id. Defaults to the configured project.
    --output <string>, -o <string>
        Output format: table or json.
```

### `ankka services`

```text
Usage:
    ankka services list
    ankka services get
    ankka services apply
    ankka services pause
    ankka services resume
    ankka services restart
    ankka services logs
    ankka services history
    ankka services expose
    ankka services unexpose
    ankka services delete

Manage services.

Options and flags:
    --help
        Display this help text.

Subcommands:
    list
        List the services in a project.
    get
        Show one service.
    apply
        Apply a service descriptor.
    pause
        Stop a service's instances, keeping its descriptor.
    resume
        Start a paused service again.
    restart
        Replace a service's instances.
    logs
        Print a deployed service's recent output.
    history
        Who did what to a service, newest first.
    expose
        Make a service reachable outside the cluster at its platform-derived hostname.
    unexpose
        Remove a service's external route, and nothing else.
    delete
        Delete a service.
```

### `ankka services list`

```text
Usage: ankka services list [--url <string>] [--token <string>] [--project <string>] [--output <string>]

List the services in a project.

Options and flags:
    --help
        Display this help text.
    --url <string>
        Control plane base URL. Defaults to the configured value.
    --token <string>
        Bearer token. Prefer ANKKA_TOKEN or the config file.
    --project <string>, -p <string>
        Project id. Defaults to the configured project.
    --output <string>, -o <string>
        Output format: table or json.
```

### `ankka services get`

```text
Usage: ankka services get [--url <string>] [--token <string>] [--project <string>] [--output <string>] <name>

Show one service.

Options and flags:
    --help
        Display this help text.
    --url <string>
        Control plane base URL. Defaults to the configured value.
    --token <string>
        Bearer token. Prefer ANKKA_TOKEN or the config file.
    --project <string>, -p <string>
        Project id. Defaults to the configured project.
    --output <string>, -o <string>
        Output format: table or json.
```

### `ankka services apply`

```text
Usage: ankka services apply --file <string> [--url <string>] [--token <string>] [--project <string>] [--output <string>]

Apply a service descriptor.

Options and flags:
    --help
        Display this help text.
    --file <string>, -f <string>
        Descriptor file, or '-' for stdin.
    --url <string>
        Control plane base URL. Defaults to the configured value.
    --token <string>
        Bearer token. Prefer ANKKA_TOKEN or the config file.
    --project <string>, -p <string>
        Project id. Defaults to the configured project.
    --output <string>, -o <string>
        Output format: table or json.
```

### `ankka services pause`

```text
Usage: ankka services pause [--url <string>] [--token <string>] [--project <string>] [--output <string>] <name>

Stop a service's instances, keeping its descriptor.

Options and flags:
    --help
        Display this help text.
    --url <string>
        Control plane base URL. Defaults to the configured value.
    --token <string>
        Bearer token. Prefer ANKKA_TOKEN or the config file.
    --project <string>, -p <string>
        Project id. Defaults to the configured project.
    --output <string>, -o <string>
        Output format: table or json.
```

### `ankka services resume`

```text
Usage: ankka services resume [--url <string>] [--token <string>] [--project <string>] [--output <string>] <name>

Start a paused service again.

Options and flags:
    --help
        Display this help text.
    --url <string>
        Control plane base URL. Defaults to the configured value.
    --token <string>
        Bearer token. Prefer ANKKA_TOKEN or the config file.
    --project <string>, -p <string>
        Project id. Defaults to the configured project.
    --output <string>, -o <string>
        Output format: table or json.
```

### `ankka services restart`

```text
Usage: ankka services restart [--url <string>] [--token <string>] [--project <string>] [--output <string>] <name>

Replace a service's instances.

Options and flags:
    --help
        Display this help text.
    --url <string>
        Control plane base URL. Defaults to the configured value.
    --token <string>
        Bearer token. Prefer ANKKA_TOKEN or the config file.
    --project <string>, -p <string>
        Project id. Defaults to the configured project.
    --output <string>, -o <string>
        Output format: table or json.
```

### `ankka services logs`

```text
Usage: ankka services logs [--instance <string>] [--previous] [--tail <integer>] [--since <integer>] [--url <string>] [--token <string>] [--project <string>] [--output <string>] <name>

Print a deployed service's recent output.

Options and flags:
    --help
        Display this help text.
    --instance <string>
        One instance; otherwise every instance.
    --previous
        The container before the last restart — usually where the answer is.
    --tail <integer>
        Only the last N lines.
    --since <integer>
        Only the last N seconds.
    --url <string>
        Control plane base URL. Defaults to the configured value.
    --token <string>
        Bearer token. Prefer ANKKA_TOKEN or the config file.
    --project <string>, -p <string>
        Project id. Defaults to the configured project.
    --output <string>, -o <string>
        Output format: table or json.
```

### `ankka services history`

```text
Usage: ankka services history [--url <string>] [--token <string>] [--project <string>] [--output <string>] <name>

Who did what to a service, newest first.

Options and flags:
    --help
        Display this help text.
    --url <string>
        Control plane base URL. Defaults to the configured value.
    --token <string>
        Bearer token. Prefer ANKKA_TOKEN or the config file.
    --project <string>, -p <string>
        Project id. Defaults to the configured project.
    --output <string>, -o <string>
        Output format: table or json.
```

### `ankka services expose`

```text
Usage: ankka services expose [--url <string>] [--token <string>] [--project <string>] [--output <string>] <name>

Make a service reachable outside the cluster at its platform-derived hostname.

Options and flags:
    --help
        Display this help text.
    --url <string>
        Control plane base URL. Defaults to the configured value.
    --token <string>
        Bearer token. Prefer ANKKA_TOKEN or the config file.
    --project <string>, -p <string>
        Project id. Defaults to the configured project.
    --output <string>, -o <string>
        Output format: table or json.
```

### `ankka services unexpose`

```text
Usage: ankka services unexpose [--url <string>] [--token <string>] [--project <string>] [--output <string>] <name>

Remove a service's external route, and nothing else.

Options and flags:
    --help
        Display this help text.
    --url <string>
        Control plane base URL. Defaults to the configured value.
    --token <string>
        Bearer token. Prefer ANKKA_TOKEN or the config file.
    --project <string>, -p <string>
        Project id. Defaults to the configured project.
    --output <string>, -o <string>
        Output format: table or json.
```

### `ankka services delete`

```text
Usage: ankka services delete [--url <string>] [--token <string>] [--project <string>] [--output <string>] <name>

Delete a service.

Options and flags:
    --help
        Display this help text.
    --url <string>
        Control plane base URL. Defaults to the configured value.
    --token <string>
        Bearer token. Prefer ANKKA_TOKEN or the config file.
    --project <string>, -p <string>
        Project id. Defaults to the configured project.
    --output <string>, -o <string>
        Output format: table or json.
```

### `ankka config`

```text
Usage:
    ankka config get
    ankka config set
    ankka config unset

Read and write the saved settings.

Options and flags:
    --help
        Display this help text.

Subcommands:
    get
        Show the effective settings.
    set
        Set url, token, project or ca. A token set here is presented as given; interactive users run `ankka login` instead.
    unset
        Clear token, project or ca.
```

### `ankka config get`

```text
Usage: ankka config get [--url <string>] [--token <string>] [--project <string>] [--output <string>]

Show the effective settings.

Options and flags:
    --help
        Display this help text.
    --url <string>
        Control plane base URL. Defaults to the configured value.
    --token <string>
        Bearer token. Prefer ANKKA_TOKEN or the config file.
    --project <string>, -p <string>
        Project id. Defaults to the configured project.
    --output <string>, -o <string>
        Output format: table or json.
```

### `ankka config set`

```text
Usage: ankka config set <key> <value>

Set url, token, project or ca. A token set here is presented as given; interactive users run `ankka login` instead.

Options and flags:
    --help
        Display this help text.
```

### `ankka config unset`

```text
Usage: ankka config unset <key>

Clear token, project or ca.

Options and flags:
    --help
        Display this help text.
```

### `ankka version`

```text
Usage: ankka version 

Print the ankka version of this CLI.

Options and flags:
    --help
        Display this help text.
```

### `ankka init`

```text
Usage: ankka init [--template <string>] [--package <string>] [--dir <string>] <name>

Create a new service from the ankka template (runs `sbt new`; needs sbt on PATH).

Options and flags:
    --help
        Display this help text.
    --template <string>
        A Giter8 template reference, e.g. file:///path/to/ankka.g8.
    --package <string>
        The Scala package; defaults to com.example.<name>.
    --dir <string>
        Where to create the project; defaults to the current directory.
```

### `ankka local`

```text
Usage: ankka local console

Tools for services running on this machine.

Options and flags:
    --help
        Display this help text.

Subcommands:
    console
        Serve a console over the services running on this machine.
```

### `ankka local console`

```text
Usage: ankka local console [--port <integer>] [--no-open]

Serve a console over the services running on this machine.

Options and flags:
    --help
        Display this help text.
    --port <integer>
        Port to serve on; defaults to 9889.
    --no-open
        Do not open a browser.
```

### `ankka mcp`

```text
Usage: ankka mcp [--url <string>] [--token <string>] [--project <string>]

Serve ankka's tools and documentation to an agent over MCP (stdio).

Options and flags:
    --help
        Display this help text.
    --url <string>
        Control plane base URL. Defaults to the configured value.
    --token <string>
        Bearer token. Prefer ANKKA_TOKEN or the config file.
    --project <string>, -p <string>
        Project id. Defaults to the configured project.
```
