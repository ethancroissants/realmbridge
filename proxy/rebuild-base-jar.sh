#!/usr/bin/env bash
# Build proxy/jars/base.jar from the upstream sources pinned in upstream.properties.
#
# The base jar is ViaProxy with its bundled ViaBedrock replaced by a build of the
# pinned ViaBedrock ref. Stock ViaProxy CI builds bundle ViaBedrock from main,
# which still targets an older Bedrock release than realms run - a client that
# old is refused at login with "Outdated client!", so the stock jar cannot reach
# a realm at all.
#
# The jar is a build artifact, not a source file: it is gitignored and rebuilt
# from these pins whenever it is missing or --force is passed.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$HERE/jars/base.jar"
WORK="$HERE/jars/.work"

[[ "${1:-}" == "--force" ]] && rm -f "$OUT"
if [[ -f "$OUT" ]]; then
    echo "base jar already built: $OUT (pass --force to rebuild)"
    exit 0
fi

# shellcheck disable=SC2046
prop() { grep -E "^$1=" "$HERE/upstream.properties" | cut -d= -f2- | tr -d '[:space:]'; }
VIAPROXY_CI="$(prop viaproxy_ci)"
VIAPROXY_BUILD="$(prop viaproxy_build)"
VIAPROXY_ARTIFACT="$(prop viaproxy_artifact)"
VIABEDROCK_REPO="$(prop viabedrock_repo)"
VIABEDROCK_REF="$(prop viabedrock_ref)"
VIABEDROCK_COMMIT="$(prop viabedrock_commit)"

rm -rf "$WORK" && mkdir -p "$WORK"

echo "==> ViaProxy: build $VIAPROXY_BUILD from $VIAPROXY_CI"
fetch_viaproxy() { curl -fsSL "$VIAPROXY_CI/$1/artifact/$VIAPROXY_ARTIFACT" -o "$WORK/viaproxy.jar"; }
if ! fetch_viaproxy "$VIAPROXY_BUILD"; then
    # Jenkins keeps a rolling window of builds, so pinned numbers expire. Falling
    # back keeps the build working; the warning is what gets the pin refreshed.
    echo "::warning::ViaProxy build $VIAPROXY_BUILD is gone from CI - falling back to the latest build."
    echo "::warning::Update viaproxy_build in proxy/upstream.properties to restore a reproducible build."
    fetch_viaproxy lastSuccessfulBuild
fi
echo "    $(stat -c%s "$WORK/viaproxy.jar") bytes"

echo "==> ViaBedrock: $VIABEDROCK_REF @ ${VIABEDROCK_COMMIT:0:12}"
git clone --quiet --no-checkout "$VIABEDROCK_REPO" "$WORK/viabedrock"
git -C "$WORK/viabedrock" checkout --quiet "$VIABEDROCK_COMMIT"
(cd "$WORK/viabedrock" && ./gradlew --quiet --no-daemon build -x test -x javadoc -x javadocJar)
VB_JAR="$(find "$WORK/viabedrock/build/libs" -name '*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' | head -1)"
[[ -f "$VB_JAR" ]] || { echo "::error::ViaBedrock build produced no jar"; exit 1; }
echo "    $(basename "$VB_JAR")"

echo "==> splicing ViaBedrock into ViaProxy"
VIAPROXY_JAR="$WORK/viaproxy.jar" VB_JAR="$VB_JAR" OUT_JAR="$OUT" python3 - <<'PY'
import os, zipfile

def is_viabedrock(name):
    return name.startswith("net/raphimc/viabedrock/") or name.startswith("assets/viabedrock/")

src = zipfile.ZipFile(os.environ["VIAPROXY_JAR"])
vb = zipfile.ZipFile(os.environ["VB_JAR"])
replaced = sum(1 for n in src.namelist() if is_viabedrock(n))
added = 0
os.makedirs(os.path.dirname(os.environ["OUT_JAR"]), exist_ok=True)
with zipfile.ZipFile(os.environ["OUT_JAR"], "w", zipfile.ZIP_DEFLATED) as out:
    for item in src.infolist():
        if not is_viabedrock(item.filename):
            out.writestr(item, src.read(item.filename))
    for item in vb.infolist():
        if is_viabedrock(item.filename):
            out.writestr(item, vb.read(item.filename)); added += 1
if not added:
    raise SystemExit("::error::the ViaBedrock jar contained no net/raphimc/viabedrock entries")
print(f"    dropped {replaced} bundled entries, added {added} from the source build")
PY

rm -rf "$WORK"
echo "Base jar: $OUT ($(stat -c%s "$OUT") bytes)"
