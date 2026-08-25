package dev.neilb.realmbridge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The "Bedrock Realms" entry point on the vanilla Realms screen.
 *
 * Normally {@code RealmsMainScreenMixin} drops this straight into the Realms
 * footer grid, where it lays itself out with the vanilla buttons. That mixin is
 * deliberately allowed to fail (see its javadoc), so the mixin reports back here
 * and RealmBridgeClient pins a copy to the top-right corner if it never ran -
 * an out-of-the-way spot with no vanilla widget in it.
 */
public final class RealmBridgeButton {

    public static final int WIDTH = 100;
    public static final int HEIGHT = 20;

    private static volatile boolean placedByMixin;

    private RealmBridgeButton() {
    }

    public static Button create(final Screen parent, final int width) {
        return Button.builder(Component.translatable("realmbridge.button.open"),
                        b -> Minecraft.getInstance().gui.setScreen(new RealmBridgeScreen(parent, RealmBridgeCore.get())))
                .size(width, HEIGHT)
                .tooltip(Tooltip.create(Component.translatable("realmbridge.button.open.tooltip")))
                .build();
    }

    public static void markPlacedByMixin() {
        if (!placedByMixin) {
            placedByMixin = true;
            RealmBridgeCore.LOGGER.debug("Bedrock Realms button added to the Realms screen footer");
        }
    }

    public static boolean needsFallback() {
        return !placedByMixin;
    }

}
