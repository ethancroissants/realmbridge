package dev.neilb.realmbridge;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

/**
 * The maintenance actions, as buttons.
 *
 * These all existed as /realmbridge chat commands, which is the one place you
 * cannot reach them: chat needs a world loaded, and a failed realm join leaves
 * you on the title screen. Everything here is reachable from the Realms screen
 * without joining anything first.
 */
public final class RealmBridgeDebugScreen extends Screen {

    private static final int CONTENT_WIDTH = 308;
    private static final int BUTTON_WIDTH = 200;
    private static final int BUTTON_HEIGHT = 20;
    private static final int ROW_SPACING = 6;
    /** A RakNet server: everything a realm join uses except NetherNet. */
    private static final String DEFAULT_TEST_SERVER = "geo.hivebedrock.network:19132";

    private final Screen parent;
    private final RealmBridgeCore core;

    private HeaderAndFooterLayout layout;
    private EditBox serverBox;

    private volatile Component status = Component.empty();
    private volatile boolean busy;

    public RealmBridgeDebugScreen(final Screen parent, final RealmBridgeCore core) {
        super(Component.translatable("realmbridge.debug.title"));
        this.parent = parent;
        this.core = core;
    }

    @Override
    protected void init() {
        this.layout = new HeaderAndFooterLayout(this);
        this.layout.addTitleHeader(this.title, this.font);

        final LinearLayout content = this.layout.addToContents(LinearLayout.vertical().spacing(ROW_SPACING));
        content.defaultCellSetting().alignHorizontallyCenter();

        content.addChild(new StringWidget(CONTENT_WIDTH, this.font.lineHeight,
                Component.translatable("realmbridge.debug.bridge",
                        Component.translatable(this.core.runner().isRunning()
                                ? "realmbridge.debug.bridge.running"
                                : "realmbridge.debug.bridge.stopped")).withStyle(ChatFormatting.GRAY),
                this.font));

        this.action(content, "realmbridge.debug.update", "realmbridge.debug.update.tooltip", () -> {
            this.setStatus(Component.translatable("realmbridge.debug.updating"), ChatFormatting.GRAY);
            this.core.runner().reinstall(m -> this.setStatus(m, ChatFormatting.GRAY));
            this.setStatus(Component.translatable("realmbridge.debug.updated"), ChatFormatting.GREEN);
        });

        this.action(content, "realmbridge.debug.invites", "realmbridge.debug.invites.tooltip", () -> {
            this.setStatus(Component.translatable("realmbridge.debug.auditing"), ChatFormatting.GRAY);
            Diagnostics.auditInvites(this.core);
            this.setStatus(Component.translatable("realmbridge.debug.audited"), ChatFormatting.GREEN);
        });

        this.action(content, "realmbridge.debug.diagnostics", "realmbridge.debug.diagnostics.tooltip", () -> {
            Diagnostics.logEnvironment(this.core);
            this.setStatus(Component.translatable("realmbridge.debug.logged"), ChatFormatting.GREEN);
        });

        // Bedrock server test: address field plus its own button.
        final LinearLayout testRow = content.addChild(LinearLayout.horizontal().spacing(ROW_SPACING));
        testRow.defaultCellSetting().alignVerticallyMiddle();
        final String carriedOver = this.serverBox == null ? DEFAULT_TEST_SERVER : this.serverBox.getValue();
        this.serverBox = new EditBox(this.font, 196, BUTTON_HEIGHT,
                Component.translatable("realmbridge.debug.test.hint"));
        this.serverBox.setMaxLength(128);
        this.serverBox.setHint(Component.translatable("realmbridge.debug.test.hint"));
        this.serverBox.setValue(carriedOver);
        testRow.addChild(this.serverBox);
        final Button test = Button.builder(Component.translatable("realmbridge.debug.test"), b -> this.testServer())
                .size(106, BUTTON_HEIGHT)
                .tooltip(Tooltip.create(Component.translatable("realmbridge.debug.test.tooltip")))
                .build();
        test.active = !this.busy;
        testRow.addChild(test);

        this.action(content, "realmbridge.debug.stop", "realmbridge.debug.stop.tooltip", () -> {
            this.core.runner().stop();
            this.setStatus(Component.translatable("realmbridge.debug.stopped"), ChatFormatting.GREEN);
        });

        // The bridge log is the thing worth reaching, and a sandboxed launcher
        // puts it somewhere the player's own file manager cannot open.
        final LinearLayout logRow = content.addChild(LinearLayout.horizontal().spacing(ROW_SPACING));
        logRow.defaultCellSetting().alignVerticallyMiddle();
        logRow.addChild(Button.builder(Component.translatable("realmbridge.debug.copypath"), b -> {
            final String path = this.core.runner().installDir().resolve("logs").toAbsolutePath().toString();
            this.minecraft.keyboardHandler.setClipboard(path);
            this.setStatus(Component.translatable("realmbridge.debug.copied", path), ChatFormatting.GREEN);
            this.refresh();
        }).size(151, BUTTON_HEIGHT).build());
        logRow.addChild(Button.builder(Component.translatable("realmbridge.debug.openfolder"),
                        b -> Util.getPlatform().openUri(this.core.runner().installDir().toUri()))
                .size(151, BUTTON_HEIGHT).build());

        final Component text = this.status;
        if (!text.getString().isEmpty()) {
            content.addChild(new MultiLineTextWidget(text, this.font)
                    .setMaxWidth(CONTENT_WIDTH).setMaxRows(3).setCentered(true));
        }

        final LinearLayout footer = this.layout.addToFooter(LinearLayout.horizontal().spacing(8));
        footer.defaultCellSetting().alignVerticallyMiddle();
        footer.addChild(Button.builder(CommonComponents.GUI_BACK, b -> this.onClose())
                .size(BUTTON_WIDTH, BUTTON_HEIGHT).build());

        this.layout.visitWidgets(this::addRenderableWidget);
        this.repositionElements();
    }

    @Override
    protected void repositionElements() {
        if (this.layout != null) {
            this.layout.arrangeElements();
        }
    }

    /** A full-width button that runs its task off-thread and reports the outcome. */
    private void action(final LinearLayout content, final String key, final String tooltipKey,
                        final RealmBridgeCore.ThrowingRunnable task) {
        final Button button = Button.builder(Component.translatable(key), b -> this.run(task))
                .size(BUTTON_WIDTH, BUTTON_HEIGHT)
                .tooltip(Tooltip.create(Component.translatable(tooltipKey)))
                .build();
        button.active = !this.busy;
        content.addChild(button);
    }

    private void run(final RealmBridgeCore.ThrowingRunnable task) {
        this.busy = true;
        this.refresh();
        this.core.async(() -> {
            task.run();
            this.busy = false;
            this.refresh();
        }, e -> {
            this.busy = false;
            this.setStatus(Component.translatable("realmbridge.status.failed", RealmBridgeCore.rootMessage(e)),
                    ChatFormatting.RED);
            this.refresh();
        });
    }

    private void testServer() {
        final String address = this.serverBox == null ? "" : this.serverBox.getValue().trim();
        if (address.isEmpty()) {
            this.setStatus(Component.translatable("realmbridge.debug.test.empty"), ChatFormatting.RED);
            this.refresh();
            return;
        }
        this.run(() -> {
            this.setStatus(Component.translatable("realmbridge.debug.testing", address), ChatFormatting.GRAY);
            this.core.runner().ensureInstalled(m -> this.setStatus(m, ChatFormatting.GRAY));
            final int index = this.core.runner().ensureAccount(this.core.auth().serialized());
            this.core.runner().startServer(address, index);
            this.setStatus(Component.translatable("realmbridge.debug.tested", ViaProxyRunner.BIND),
                    ChatFormatting.GREEN);
        });
    }

    private void setStatus(final Component text, final ChatFormatting style) {
        this.status = text.copy().withStyle(style);
        this.refresh();
    }

    private void refresh() {
        this.minecraft.execute(() -> {
            if (this.minecraft.gui.screen() == this) {
                this.rebuildWidgets();
            }
        });
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }

}
