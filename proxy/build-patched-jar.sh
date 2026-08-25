#!/usr/bin/env bash
# Rebuild the patched ViaProxy.jar from the clean base jar + jarpatches/src,
# and install it to ~/.bedrock-realm-bridge/ViaProxy.jar.
#
# The result is fully reproducible from this repo: checkout any tag, run this.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_JAR="$HERE/jars/ViaProxy-3.4.13-snapshot-b1927-base.jar"
TARGET="$HOME/.bedrock-realm-bridge/ViaProxy.jar"
OUT="$HERE/jarpatches/out"

[[ -f "$BASE_JAR" ]] || { echo "base jar missing: $BASE_JAR"; exit 1; }

rm -rf "$OUT" && mkdir -p "$OUT"
javac -proc:none --release 17 -cp "$BASE_JAR" -d "$OUT" \
  $(find "$HERE/jarpatches/src" -name '*.java')

mkdir -p "$(dirname "$TARGET")"
# Stamp the build so any log can name the bridge that produced it.
printf '%s %s\n' "$(git -C "$HERE" rev-parse --short HEAD 2>/dev/null || echo unknown)" \
    "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > "$OUT/vpp-build.txt"

cp "$BASE_JAR" "$TARGET"
(cd "$OUT" && jar -uf "$TARGET" $(find . -name '*.class') vpp-build.txt)

echo "==> bytecode patches"
ASM_OUT="$HERE/jarpatches/asm-out"
ASM_WORK="$HERE/jarpatches/asm-work"
# Inbound signaling frames now arrive as a bare object, not an array of them.
SIGNALING_CLASS="dev/kastle/netty/channel/nethernet/signaling/NetherNetXboxRpcSignaling.class"
# The offer SDP needs an a=identity assertion or realm hosts refuse it (CONNECTERROR 37).
IDENTITY_CLASS='dev/kastle/netty/channel/nethernet/NetherNetClientChannel$2$1.class'
rm -rf "$ASM_OUT" "$ASM_WORK" && mkdir -p "$ASM_OUT" "$ASM_WORK"
javac -proc:none --release 17 -cp "$TARGET" -d "$ASM_OUT" "$HERE/tools/AsmPatcher.java"

(cd "$ASM_WORK" && unzip -o -q "$TARGET" "$SIGNALING_CLASS")
java -cp "$ASM_OUT:$TARGET" AsmPatcher signaling "$ASM_WORK/$SIGNALING_CLASS"
(cd "$ASM_WORK" && jar -uf "$TARGET" "$SIGNALING_CLASS")

# Re-extract: the signaling class already carries the patch above.
(cd "$ASM_WORK" && rm -f "$SIGNALING_CLASS" && unzip -o -q "$TARGET" "$SIGNALING_CLASS")
java -cp "$ASM_OUT:$TARGET" AsmPatcher signalingtap "$ASM_WORK/$SIGNALING_CLASS"
(cd "$ASM_WORK" && jar -uf "$TARGET" "$SIGNALING_CLASS")

(cd "$ASM_WORK" && unzip -o -q "$TARGET" "$IDENTITY_CLASS")
java -cp "$ASM_OUT:$TARGET" AsmPatcher identity "$ASM_WORK/$IDENTITY_CLASS"
(cd "$ASM_WORK" && jar -uf "$TARGET" "$IDENTITY_CLASS")
echo "Patched jar installed: $TARGET"
echo "Patched classes:"
(cd "$OUT" && find . -name '*.class' | sed 's|^\./|  |')
