#!/usr/bin/env bash
# Prove a patched bridge jar actually works before anything ships it.
#
# The patch pipeline already fails loudly when an ASM target disappears, but a
# jar can pass that and still be broken: ViaBedrock spliced in from source can
# fail to link against the ViaVersion the proxy bundles, and a forked class left
# at an old revision can load fine while quietly reinstating old protocol code.
# Both of those show up here and nowhere else in the build.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="${1:-$HOME/.bedrock-realm-bridge/ViaProxy.jar}"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

[[ -f "$JAR" ]] || { echo "::error::no jar to test at $JAR"; exit 1; }
echo "==> testing $JAR ($(stat -c%s "$JAR") bytes)"

# 1. ViaBedrock has to load and register a Bedrock protocol. If the splice or a
#    stale fork broke linkage, this is where the NoSuchMethodError surfaces.
cat > "$WORK/Smoke.java" <<'JAVA'
import net.raphimc.viabedrock.api.BedrockProtocolVersion;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;

public class Smoke {
    public static void main(String[] args) {
        final ProtocolVersion bedrock = BedrockProtocolVersion.bedrockLatest;
        System.out.println("bedrock=" + bedrock.getName() + " protocol=" + bedrock.getVersion());
        if (bedrock.getVersion() <= 0 || !bedrock.getName().startsWith("Bedrock ")) {
            throw new IllegalStateException("ViaBedrock did not register a usable protocol");
        }
    }
}
JAVA
javac -proc:none --release 17 -cp "$JAR" -d "$WORK" "$WORK/Smoke.java"
BEDROCK="$(java -cp "$WORK:$JAR" Smoke | tail -1)"
echo "    $BEDROCK"

# 2. The proxy has to reach a listening state, which exercises protocol
#    registration and mapping loading across every bundled Via component.
( java -jar "$JAR" cli --bind-address 127.0.0.1:25599 --target-address 127.0.0.1:19132 \
      --auth-method NONE > "$WORK/proxy.log" 2>&1 & echo $! > "$WORK/pid" ) || true
for _ in $(seq 1 60); do
    grep -qa "ViaProxy started successfully" "$WORK/proxy.log" && break
    sleep 1
done
kill "$(cat "$WORK/pid")" 2>/dev/null || true

if ! grep -qa "ViaProxy started successfully" "$WORK/proxy.log"; then
    echo "::error::the proxy never reached a started state"
    tail -40 "$WORK/proxy.log"
    exit 1
fi
if grep -qaE "NoSuchMethodError|NoClassDefFoundError|NoSuchFieldError" "$WORK/proxy.log"; then
    echo "::error::linkage error while starting - the spliced ViaBedrock does not match this ViaProxy"
    grep -aE "NoSuchMethodError|NoClassDefFoundError|NoSuchFieldError" "$WORK/proxy.log" | head -5
    exit 1
fi

echo "    proxy started and loaded mappings cleanly"
echo "SMOKE TEST PASSED ($BEDROCK)"
