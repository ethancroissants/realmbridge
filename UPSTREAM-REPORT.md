# Bedrock Realm connections refused with `CONNECTERROR <id> 37` (ErrorCodeIdentityNotAllowed)

Filed against ViaProxy / ViaBedrock. Everything below was reproduced against
ViaProxy `3.4.13-SNAPSHOT` (`git-ViaProxy-3.4.13-SNAPSHOT:01be8b8`, 2026-07-09)
with a small patch set applied; the unpatched build behaves identically for the
purposes of this report.

## Summary

Connecting to a Bedrock Realm over NetherNet fails during negotiation. Signaling
completes, the realm host answers, and the answer is a terminal error:

```
[VP+] signaling rejection: {"params":{"netherNetId":"<realm session>",
  "message":"CONNECTERROR 3203728649211414744 37"},"jsonrpc":"2.0",
  "method":"Signaling_WebRtc_v1_0"}
[Netty NIO Client IO #0/ERROR] (NetherNetClientChannel) Received SIGNAL_CONNECT_ERROR
[PROXY KICK] Could not connect to the backend server!
```

Counting the `iota` block in [df-mc/go-nethernet `signal.go`](https://github.com/df-mc/go-nethernet/blob/master/signal.go)
from zero, **37 = `ErrorCodeIdentityNotAllowed`** - "the remote identity token or
its DTLS fingerprint assertion failed validation".

## What is ruled out

**Not the account's credentials.** The same account, same bridge, connects fine
to a RakNet Bedrock server (`geo.hivebedrock.network:19132`) and plays normally.
Only the NetherNet path fails.

**Not the Realms control plane.** All of this succeeds immediately beforehand:

```
Realms API accepts client version Bedrock 1.26.30: true
realm id=... state=OPEN compatible=true expired=false
join info: address=<uuid> protocol=NETHERNET_JSONRPC
raw: {"networkProtocol":"NETHERNET_JSONRPC","address":"...","pendingUpdate":false,
      "sessionRegionData":{"regionName":"EastUs2","serviceQuality":1}}
```

**Not a missing identity assertion.** ViaBedrock sends libwebrtc's offer SDP
verbatim and never attaches an `a=identity` attribute - there is no `a=identity`,
`cpk`, or fingerprint signing anywhere in `dev.kastle.*`. We implemented one
(detached ES384 JWS over the offer's DTLS fingerprints, signed with the
MinecraftAuth session key, plus the multiplayer token from
`authorization.franchise.minecraft-services.net`) and verified it against the
issuer's own published JWKS at connect time:

```
check: issuer published=https://authorization.franchise.minecraft-services.net/
       token iss=https://authorization.franchise.minecraft-services.net/ match=true
check: token signature valid=true
check: token expires in 14,398s
check: fingerprint assertion verifies against cpk=true
check: token xid=<xuid>  xname=<gamertag>  aud=api://auth-minecraft-services/multiplayer
```

**The error is byte-identical with and without that assertion.** Sending no
identity at all and sending one that verifies against Microsoft's keys both
produce `CONNECTERROR <id> 37`. That strongly suggests the host is not
evaluating the assertion at all.

## The offer as sent

```
v=0
o=- 6867209763858411753 2 IN IP4 127.0.0.1
s=-
t=0 0
a=group:BUNDLE 0
a=extmap-allow-mixed
a=msid-semantic: WMS
a=identity:<1820 bytes, base64 JSON per go-nethernet identityData>
m=application 9 UDP/DTLS/SCTP webrtc-datachannel
c=IN IP4 0.0.0.0
a=ice-ufrag:9Wnw
a=ice-pwd:v3KS8sg1ZmZQkHRPVUgn2ZN8
a=ice-options:trickle
a=fingerprint:sha-256 2E:74:65:50:...:31:38
a=setup:actpass
a=mid:0
a=sctp-port:5000
a=max-message-size:262144
```

Total signaling message 2,291 bytes.

## Questions

1. Do realm hosts now require an `a=identity` assertion on the offer? If so,
   ViaBedrock does not send one and every realm connection should be failing.
2. If not, what else causes a host to answer `ErrorCodeIdentityNotAllowed` when
   the Realms API has just issued the caller a session for that realm?
3. Is `member: false` in the `/worlds` response meaningful here? It is reported
   for an account that the realm owner says is a member, and it does not change
   after the owner removes and re-adds the account.

## Environment

- ViaProxy `3.4.13-SNAPSHOT` (`01be8b8`), ViaBedrock protocol `1001` /
  `BEDROCK_VERSION_NAME = 1.26.30`
- Java client 26.2, Fabric loader 0.19.3, JDK 25
- Realm: `TEN_PLAYERS`, `apple.realms.subscription.monthly.10player.trial`,
  `state=OPEN`, `daysLeft=30`, `activeVersion=null`, `compatibility=null`
- Region `EastUs2`, `pendingUpdate=false`
- Reproduced on Windows 11 and Linux, on two different networks
