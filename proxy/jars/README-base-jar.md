# The bridge base jar

`base.jar` in this directory is a **build artifact, not a source file**. It is
gitignored and rebuilt on demand by `../rebuild-base-jar.sh` from the upstream
revisions pinned in `../upstream.properties`.

    ./proxy/rebuild-base-jar.sh            # build it if missing
    ./proxy/rebuild-base-jar.sh --force    # rebuild from scratch

## What it is

ViaProxy, taken as a prebuilt CI artifact, with its bundled **ViaBedrock**
replaced by a build of the pinned ViaBedrock revision.

That replacement is the whole reason this file exists. ViaProxy CI builds bundle
ViaBedrock from `main`, which trails the Bedrock release realms actually run.
A client claiming an older protocol is refused at login with
`LOGIN_FAILED_CLIENT_OLD` - "Outdated client!" - so a stock ViaProxy jar cannot
reach a realm at all until upstream catches up.

## When upstream catches up

Once the ViaBedrock update branch merges and a ViaProxy CI build bundles it,
this splice becomes redundant:

1. point `viabedrock_ref` at `main` in `upstream.properties`, or
2. drop the ViaBedrock half of `rebuild-base-jar.sh` entirely and use the CI
   artifact unmodified.

The forked sources under `../jarpatches/src` still need re-merging either way -
see `../merge-forks.sh`.

## Staying current

`.github/workflows/upstream-refresh.yml` checks both upstreams daily, re-merges
the forks, rebuilds, smoke tests, and publishes only if all of that passes.
