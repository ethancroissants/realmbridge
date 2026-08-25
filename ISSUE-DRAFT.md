Bedrock Realm connections rejected during negotiation with `CONNECTERROR <id> 37`

---

Joining a Bedrock Realm over NetherNet fails during WebRTC negotiation. Signaling
completes normally, the realm host receives and parses our offer, and answers
with a terminal error:

```
[CONNECT] (127.0.0.1:xxxxx | null) [26.2 <-> Bedrock 1.26.30] Connecting to nethernet-rpc://<session>
(NetherNetClientChannel) Received SIGNAL_CONNECT_ERROR for 5087569416665908099.
[PROXY KICK] Could not connect to the backend server!
```

The signal is `CONNECTERROR 5087569416665908099 37`. Counting the `iota` block in
[df-mc/go-nethernet `signal.go`](https://github.com/df-mc/go-nethernet/blob/master/signal.go)
from zero, **37 = `ErrorCodeIdentityNotAllowed`**.

The account can join this realm normally from a real Bedrock client, and is a
confirmed member of it.

### Version

- ViaProxy `3.4.13-SNAPSHOT` (`git-ViaProxy-3.4.13-SNAPSHOT:01be8b8`)
- Java client 26.2, JDK 25
- Realm: 10-player, `state=OPEN`, `expired=false`, `pendingUpdate=false`,
  `activeVersion=null`, `compatibility=null`, region `EastUs2`
- Reproduced on Windows 11 and Linux, on two different networks and two ISPs

### What still works

- **RakNet Bedrock servers are fine.** The same account and the same ViaProxy
  connect to `geo.hivebedrock.network:19132` and play normally. Only the
  NetherNet path fails.
- **The Realms control plane is happy**, immediately before the failure:
  `/mco/client/compatible` returns compatible for `1.26.30`; the realm lists as
  `OPEN`; `joinWorld` returns `{"networkProtocol":"NETHERNET_JSONRPC",
  "address":"<uuid>","pendingUpdate":false,...}`.
- **The host explicitly acknowledges the `CONNECTREQUEST` itself.** It returns a
  `Signaling_DeliveryNotification_V1_0` carrying that message's own `messageId`,
  and its `CONNECTERROR` quotes our connection ID back. The offer is delivered,
  parsed, and then refused — this is not a dropped, truncated or out-of-order
  message.
- **TURN works.** `Signaling_TurnAuth_v1_0` returns relay credentials and a
  `typ relay` candidate gathers successfully.

### Signaling trace

Captured by tapping `TextWebSocketFrame.text()` and `Gson.toJson` in
`NetherNetXboxRpcSignaling`. Identifiers and addresses redacted.

```
--> Signaling_TurnAuth_v1_0
<-- result: TurnAuthServers [stun|turn:relay.communication.microsoft.com:3478]
--> Signaling_SendClientMessage_v1_0  toPlayerId=<realm session uuid>
      inner: {"params":{"netherNetId":"<our random id>",
              "message":"CONNECTREQUEST <connId> <offer sdp>"},
              "method":"Signaling_WebRtc_v1_0"}
--> CANDIDATEADD  host <lan v4>
--> CANDIDATEADD  host <lan v6>
--> CANDIDATEADD  srflx <public v4>
<-- result: null                       (service accepted every send)
<-- Signaling_ReceiveMessage_v1_0      Signaling_DeliveryNotification_V1_0 for our candidates
<-- Signaling_ReceiveMessage_v1_0      {"message":"CONNECTERROR <connId> 37"}
```

Offer SDP as sent (single data channel, trickle ICE enabled):

```
v=0
o=- <n> 2 IN IP4 127.0.0.1
s=-
t=0 0
a=group:BUNDLE 0
a=extmap-allow-mixed
a=msid-semantic: WMS
m=application 9 UDP/DTLS/SCTP webrtc-datachannel
c=IN IP4 0.0.0.0
a=ice-ufrag:<x>
a=ice-pwd:<x>
a=ice-options:trickle
a=fingerprint:sha-256 <32 bytes>
a=setup:actpass
a=mid:0
a=sctp-port:5000
a=max-message-size:262144
```

### The part I would most like a sanity check on

ViaBedrock sends libwebrtc's offer verbatim and never attaches an `a=identity`
attribute — there is no `a=identity`, `cpk`, or DTLS-fingerprint signing anywhere
in `dev.kastle.*`. go-nethernet treats that attribute as how a client
authenticates itself and binds its identity to the peer connection, and its
`identityData.Valid()` requires a non-empty `idp.domain`.

I implemented one locally to test the theory: a detached ES384 JWS over the
offer's DTLS fingerprints, signed with the MinecraftAuth session key (secp384r1),
carrying the multiplayer token from
`authorization.franchise.minecraft-services.net`, with `idp.domain` taken from
that token's own `iss` claim. I verified it at connect time against the issuer's
published JWKS:

```
check: issuer published=<iss>  token iss=<iss>  match=true
check: token signature valid=true
check: token expires in 14,398s
check: fingerprint assertion verifies against cpk=true
check: token aud=api://auth-minecraft-services/multiplayer
```

**The rejection is byte-identical with and without that assertion.** Sending no
identity and sending one that verifies against Microsoft's own keys both produce
`CONNECTERROR <id> 37`, which suggests the host is not evaluating the assertion
at all and is refusing on some other basis.

### Questions

1. Do realm hosts require an `a=identity` assertion on the offer? If they do,
   ViaBedrock does not send one and realm joins should be failing for everyone —
   if they are not, what supplies the identity instead?
2. What else makes a host answer `ErrorCodeIdentityNotAllowed` when the Realms
   API has just issued that same account a session for that realm?
3. Does the number of `joinWorld` calls matter? ViaProxy's realm flow can call it
   several times before connecting (once per address poll, plus once more at
   connect); a real client appears to call it once.

Happy to run any patched build or capture more of the signaling channel — the tap
above is a two-call ASM redirect and I can share it if useful.
