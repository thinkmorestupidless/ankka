# ankka Homebrew tap

The `ankka` command-line client for [ankka](https://docs.ankka.cloud/), a serverless platform for
agentic AI on the actor model.

```bash
brew install thinkmorestupidless/tap/ankka
ankka version
```

The formula installs the CLI's own JDK dependency, `openjdk@21`. `ankka init`, which creates a service
from the template by running `sbt new`, also needs `brew install sbt`; every other command does not.

This repository is generated. The formula is rendered from
[thinkmorestupidless/ankka](https://github.com/thinkmorestupidless/ankka) (`homebrew/` there) by the
release workflow on every tag, with the version and the checksum of the zip attached to that tag's
GitHub release. Changes go to the ankka repository, not here.
