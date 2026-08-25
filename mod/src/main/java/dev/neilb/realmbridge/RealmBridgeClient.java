package dev.neilb.realmbridge;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.realmsclient.RealmsMainScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.network.chat.Component;
import net.raphimc.minecraftauth.extra.realms.model.RealmsServer;
import net.raphimc.minecraftauth.extra.realms.service.impl.BedrockRealmsService;

import java.util.List;

/**
 * RealmBridge entrypoint. Primary UX: the "Bedrock Realms" button added to the
 * vanilla Realms screen (see {@code RealmsMainScreenMixin}), which opens
 * {@link RealmBridgeScreen}. The /realmbridge chat commands remain as a
 * power-user alternative.
 */
public final class RealmBridgeClient implements ClientModInitializer {

    private final RealmBridgeCore core = RealmBridgeCore.get();

    @Override
    public void onInitializeClient() {
        // A successful join means any later disconnect is the player's own doing,
        // not a failed realm connection to retry.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> JoinFlow.clear());

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof DisconnectedScreen) {
                // a realm that just woke often refuses the first connection
                JoinFlow.retryIfOurs(this.core);
            }
            if (screen instanceof RealmsMainScreen && RealmBridgeButton.needsFallback()) {
                // The footer mixin did not apply on this version - keep the mod
                // reachable from a corner the Realms screen leaves empty.
                RealmBridgeCore.LOGGER.info("Realms footer mixin did not apply; "
                        + "pinning the Bedrock Realms button to the corner instead");
                final Button button = RealmBridgeButton.create(screen, RealmBridgeButton.WIDTH);
                button.setPosition(scaledWidth - RealmBridgeButton.WIDTH - 6, 6);
                Screens.getWidgets(screen).add(button);
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommands.literal("realmbridge")
                        .then(ClientCommands.literal("login").executes(ctx -> {
                            this.core.async(() -> {
                                this.core.auth().authManager(code -> this.chat(Component.translatable(
                                        "realmbridge.devicecode.instructions", code.getVerificationUri())
                                        .append(" ").append(code.getUserCode())));
                                this.chat(Component.literal("Signed in."));
                            }, this::chatError);
                            return 1;
                        }))
                        .then(ClientCommands.literal("code")
                                .then(ClientCommands.argument("invite", StringArgumentType.word()).executes(ctx -> {
                                    final String invite = StringArgumentType.getString(ctx, "invite");
                                    this.core.async(() -> {
                                        final RealmsServer realm = this.core.realms().acceptInvite(invite);
                                        this.chat(Component.translatable("realmbridge.invite.joined",
                                                realm.getNameOr("realm")));
                                    }, this::chatError);
                                    return 1;
                                })))
                        .then(ClientCommands.literal("play").executes(ctx -> {
                            this.playCommand(null);
                            return 1;
                        }).then(ClientCommands.argument("name", StringArgumentType.greedyString()).executes(ctx -> {
                            this.playCommand(StringArgumentType.getString(ctx, "name"));
                            return 1;
                        })))
                        // Isolation test: everything a realm join uses except NetherNet.
                        // It deliberately does not auto-connect: the command is typed in
                        // chat, so a world is loaded, and opening a server connection from
                        // inside one leaves the integrated server running. Direct Connect
                        // from the title screen instead.
                        .then(ClientCommands.literal("test")
                                .then(ClientCommands.argument("address", StringArgumentType.greedyString()).executes(ctx -> {
                                    final String address = StringArgumentType.getString(ctx, "address").trim();
                                    this.core.async(() -> {
                                        Diagnostics.logEnvironment(this.core);
                                        this.core.runner().ensureInstalled(this::chat);
                                        final int index = this.core.runner().ensureAccount(this.core.auth().serialized());
                                        this.chat(Component.literal("Bridging to Bedrock server " + address + "..."));
                                        this.core.runner().startServer(address, index);
                                        this.chat(Component.literal("Bridge is up. Quit to title, then "
                                                + "Multiplayer -> Direct Connection -> " + ViaProxyRunner.BIND));
                                    }, this::chatError);
                                    return 1;
                                })))
                        .then(ClientCommands.literal("update").executes(ctx -> {
                            this.core.async(() -> {
                                this.chat(Component.literal("Re-downloading the bridge..."));
                                this.core.runner().reinstall(this::chat);
                                this.chat(Component.literal("Bridge updated. Join the realm again."));
                            }, this::chatError);
                            return 1;
                        }))
                        .then(ClientCommands.literal("stop").executes(ctx -> {
                            this.core.runner().stop();
                            this.chat(Component.literal("Bridge stopped."));
                            return 1;
                        }))));
    }

    private void playCommand(final String name) {
        this.core.async(() -> {
            final BedrockRealmsService service = this.core.realms();
            final List<RealmsServer> worlds = service.getWorlds().stream()
                    .filter(w -> w.isCompatible() && !w.isExpired()).toList();
            final RealmsServer realm = name == null
                    ? (worlds.size() == 1 ? worlds.get(0) : null)
                    : worlds.stream().filter(w -> w.getName() != null
                            && w.getName().toLowerCase().contains(name.toLowerCase())).findFirst().orElse(null);
            if (realm == null) {
                this.chat(Component.literal("Pick a realm: " + worlds.stream().map(RealmsServer::getName).toList()));
                return;
            }
            JoinFlow.start(this.core, realm, null, this::chat);
        }, this::chatError);
    }

    private void chatError(final Throwable e) {
        this.chat(Component.translatable("realmbridge.status.failed", RealmBridgeCore.rootMessage(e)));
    }

    private void chat(final Component message) {
        final Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> minecraft.gui.hud.getChat().addClientSystemMessage(
                Component.literal("[RealmBridge] ").append(message)));
    }

}
