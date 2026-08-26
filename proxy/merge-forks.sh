#!/usr/bin/env bash
# Re-merge the forked ViaBedrock sources onto a newer upstream commit.
#
# jarpatches/src contains two kinds of file. Anything under experimental/ is ours
# and upstream knows nothing about it. Everything else is a *fork*: a copy of an
# upstream ViaBedrock source file with local changes, compiled and layered over
# the jar so it shadows upstream's compiled class.
#
# That shadowing is the dangerous part. A fork left at an old upstream revision
# still compiles and still loads - it just silently reinstates the old protocol
# handling and undoes whatever upstream changed. So bumping ViaBedrock without
# re-merging the forks is worse than not bumping it at all.
#
# This script does that re-merge, three-way, using the commit recorded in
# upstream.properties as the ancestor. Clean merges are written back and the pins
# advanced. Conflicts stop the script with the files left marked up for a human,
# which is the whole point: nobody should be resolving PLAYER_AUTH_INPUT blind.
#
# Usage: merge-forks.sh [<target-commit>]      (default: current viabedrock_ref)
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROPS="$HERE/upstream.properties"
SRC="$HERE/jarpatches/src/net/raphimc/viabedrock"

prop() { grep -E "^$1=" "$PROPS" | cut -d= -f2- | tr -d '[:space:]'; }
REPO_URL="$(prop viabedrock_repo)"
REF="$(prop viabedrock_ref)"
BASE_COMMIT="$(prop viabedrock_fork_base)"
SLUG="$(echo "$REPO_URL" | sed -E 's#.*github\.com/##; s#\.git$##')"

TARGET="${1:-}"
if [[ -z "$TARGET" ]]; then
    TARGET="$(git ls-remote "$REPO_URL" "refs/heads/$REF" | cut -f1)"
    [[ -n "$TARGET" ]] || { echo "::error::could not resolve $REF in $REPO_URL"; exit 1; }
fi

echo "fork base : ${BASE_COMMIT:0:12}"
echo "target    : ${TARGET:0:12} ($REF)"
if [[ "$BASE_COMMIT" == "$TARGET" ]]; then
    echo "Forks are already merged against this commit; nothing to do."
    exit 0
fi

WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
raw() { # raw <commit> <repo-relative java path> <dest>
    curl -fsSL "https://raw.githubusercontent.com/$SLUG/$1/src/main/java/$2" -o "$3"
}

# Which files are forks is decided by asking upstream, not by where they sit.
# Guessing from the path gets this wrong: ExperimentalFeatures.java lives in the
# experimental package next to code that is entirely ours, but it is upstream's
# file with local edits - and a fork that never gets re-merged is exactly the
# silent breakage this script exists to prevent.
has_upstream() {
    curl -fsSL -o /dev/null "https://raw.githubusercontent.com/$SLUG/$BASE_COMMIT/src/main/java/net/raphimc/viabedrock/$1" 2>/dev/null
}

FORKS=()
OURS=()
while IFS= read -r rel; do
    if has_upstream "$rel"; then FORKS+=("$rel"); else OURS+=("$rel"); fi
done < <(cd "$SRC" && find . -name '*.java' | sed 's|^\./||' | sort)

echo "forked from upstream: ${#FORKS[@]}   ours alone: ${#OURS[@]}"
echo

conflicted=()
for rel in "${FORKS[@]}"; do
    path="net/raphimc/viabedrock/$rel"
    name="${rel//\//_}"
    if ! raw "$BASE_COMMIT" "$path" "$WORK/base_$name"; then
        echo "::error::$rel does not exist upstream at the recorded fork base ${BASE_COMMIT:0:12}"
        exit 1
    fi
    if ! raw "$TARGET" "$path" "$WORK/theirs_$name"; then
        echo "::error::$rel was removed or renamed upstream at ${TARGET:0:12} - this fork needs a human"
        exit 1
    fi
    cp "$SRC/$rel" "$WORK/merged_$name"
    if git merge-file -L realmbridge -L "viabedrock-${BASE_COMMIT:0:7}" -L "viabedrock-${TARGET:0:7}" \
            "$WORK/merged_$name" "$WORK/base_$name" "$WORK/theirs_$name" >/dev/null 2>&1; then
        upstream_delta=$(diff "$WORK/base_$name" "$WORK/theirs_$name" | grep -c '^[<>]' || true)
        echo "  ok        $rel (upstream moved $upstream_delta lines)"
    else
        echo "  CONFLICT  $rel"
        conflicted+=("$rel")
    fi
    cp "$WORK/merged_$name" "$SRC/$rel"
done

echo
if (( ${#conflicted[@]} )); then
    echo "::error::${#conflicted[@]} fork(s) conflicted and were left with merge markers:"
    printf '::error::  %s\n' "${conflicted[@]}"
    echo "Resolve them, then re-run with the same target commit to advance the pins."
    exit 1
fi

# Only advance the pins once every fork merged cleanly, so a failed run never
# leaves the properties claiming a merge that did not happen.
sed -i -E "s|^viabedrock_commit=.*|viabedrock_commit=$TARGET|" "$PROPS"
sed -i -E "s|^viabedrock_fork_base=.*|viabedrock_fork_base=$TARGET|" "$PROPS"
echo "All forks merged cleanly; pinned ViaBedrock to ${TARGET:0:12}."
