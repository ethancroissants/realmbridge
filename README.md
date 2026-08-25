# RealmBridge

**Play on Bedrock Minecraft Realms from Java Edition.** Invite codes, one-click
join, and your Fabric mods (Baritone, Litematica, Sodium, ...) — on your
friends' Bedrock Realm.

Java and Bedrock normally can't share a Realm. RealmBridge glues them together:
a patched [ViaProxy](https://github.com/ViaVersion/ViaProxy)/[ViaBedrock](https://github.com/RaphiMC/ViaBedrock)
translates the protocol live, a companion plugin makes it feel vanilla
(instant item pickups, smooth movement, auto realm wake/reconnect), and a
Fabric mod wraps everything behind one button on your Realms screen.

## Install (2 minutes)

1. Install [Fabric](https://fabricmc.net/use/installer/) for Minecraft 26.2 and
   put [fabric-api](https://modrinth.com/mod/fabric-api) +
   **`realmbridge-<version>.jar`** (from [Releases](../../releases/latest))
   into your `mods/` folder. Those two jars are the whole install — everything
   else RealmBridge needs is bundled inside its jar or fetched on first use.
2. Launch the game → **Multiplayer → Realms → Bedrock Realms** (the button
   under Play/Configure/Leave).
3. **Sign in with Microsoft** (one time). The screen shows your sign-in code
   with a **Copy code** button; **Open sign-in page** launches the browser with
   the code already filled in. Use the account that owns or was invited to the
   Bedrock realm.
4. Paste a **realm invite code** → your realm appears → **click it**.

The mod downloads the bridge (~45 MB, one time) to `~/.bedrock-realm-bridge`,
signs it in with your account automatically, wakes the realm, and connects you.
Minecraft 26.2 runs on Java 25, and the launcher's bundled runtime is what the
bridge uses — no separate Java install needed.

## Good to know

- **First join after the realm slept** can time out once while the realm
  server boots — just click again.
- The bridge keeps running while you play; `/realmbridge stop` shuts it down.
- Client-side mods work normally. Server-dependent mods can't (there is no
  Java server). Movement cheats will rubber-band — Bedrock realms are
  server-authoritative.
- Lighting is approximate (uniform bright): Bedrock servers don't send light
  data (Bedrock clients compute it themselves), so there's nothing to
  translate — caves and night look flat until a light engine is written.
- Crafting through the 2x2/3x3 grid isn't wired up yet (Bedrock uses a
  separate crafting protocol); use a Bedrock client or another player for
  now. Chests, shulkers, furnaces, dropping and all inventory moves do work.
- Terminal-only alternative (macOS/Linux): grab
  `bedrock-realm-bridge.tar.gz` from Releases —
  `./bedrock-realm play <invite-code>`.

## Repo layout / building

| dir | what | artifact |
|-----|------|----------|
| `mod/` | Fabric mod: UI, Microsoft sign-in, invite codes, bridge bootstrap, auto-connect | `realmbridge-<v>.jar` |
| `proxy/` | ViaProxy patch set + ViaProxyPlus plugin (documented in `jarpatches/src` + git history) | `ViaProxy-patched.jar`, `ViaProxyPlus.jar` |
| `cli/` | Python CLI + portable bundle | `bedrock-realm-bridge.tar.gz` |

```bash
./build-all.sh   # JDK 25+, python3 → everything lands in dist/
```

Gradle comes from the wrapper in `mod/`; CI runs the same steps
(`.github/workflows/build.yml`) and attaches the artifacts to tagged releases.

## Credits & license

Built on [ViaProxy](https://github.com/ViaVersion/ViaProxy),
[ViaBedrock](https://github.com/RaphiMC/ViaBedrock) and
[MinecraftAuth](https://github.com/RaphiMC/MinecraftAuth) by RK_01/RaphiMC &
contributors. Several fixes from this repo are headed upstream. GPLv3.
