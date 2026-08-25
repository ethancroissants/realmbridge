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
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Util;
import net.raphimc.minecraftauth.extra.realms.model.RealmsServer;
import net.raphimc.minecraftauth.extra.realms.service.impl.BedrockRealmsService;
import net.raphimc.minecraftauth.msa.model.MsaDeviceCode;

import java.net.URI;
import java.util.List;

/**
 * The RealmBridge screen: sign in, accept invite codes, and one-click join
 * Bedrock Realms from Java Edition.
 *
 * Everything on screen is a vanilla widget arranged by a vanilla layout - no
 * custom rendering - so it keeps working across Minecraft's render-pipeline
 * churn and picks up the current GUI scale and font for free.
 *
 * The screen is a pure function of the fields below: background work only ever
 * mutates state and asks for a rebuild, never touches widgets directly. That is
 * what keeps the device code on screen - the previous version wrote it into a
 * label that the next rebuild immediately overwrote.
 */
public final class RealmBridgeScreen extends Screen {

    /** Matches the vanilla Realms screen's content column. */
    private static final int CONTENT_WIDTH = 308;
    private static final int BUTTON_WIDTH = 200;
    private static final int BUTTON_HEIGHT = 20;
    /** Three of these plus the layout spacing fill the content column. */
    private static final int FOOTER_BUTTON_WIDTH = 96;
    private static final int ROW_SPACING = 8;
    /** Header + footer bands, the invite row and the status line. */
    private static final int CHROME_HEIGHT = 44 + 40 + 28 + 40;
    /** Even on a tall window, a wall of buttons is not a realm picker. */
    private static final int MAX_LISTED_REALMS = 8;

    private final Screen parent;
    private final RealmBridgeCore core;

    private HeaderAndFooterLayout layout;
    private EditBox inviteBox;
    private StringWidget expiryWidget;

    // Screen state. Written from the worker thread, read on the render thread.
    private volatile List<RealmsServer> realms;
    private volatile MsaDeviceCode deviceCode;
    private volatile Component status = Component.empty();
    private volatile boolean busy;
    private volatile boolean loadRequested;

    public RealmBridgeScreen(final Screen parent, final RealmBridgeCore core) {
        super(Component.translatable("realmbridge.title"));
        this.parent = parent;
        this.core = core;
    }

    @Override
    protected void init() {
        this.layout = new HeaderAndFooterLayout(this);
        this.layout.addTitleHeader(this.title, this.font);
        this.expiryWidget = null;

        // Read the state once: the worker thread can move on mid-build.
        final MsaDeviceCode code = this.deviceCode;
        final List<RealmsServer> loaded = this.realms;

        final LinearLayout content = this.layout.addToContents(LinearLayout.vertical().spacing(ROW_SPACING));
        content.defaultCellSetting().alignHorizontallyCenter();
        final LinearLayout footer = this.layout.addToFooter(LinearLayout.horizontal().spacing(ROW_SPACING));
        footer.defaultCellSetting().alignVerticallyMiddle();

        if (code != null) {
            this.buildDeviceCode(content, footer, code);
        } else if (!this.core.auth().isLoggedIn()) {
            this.buildSignIn(content, footer);
        } else if (loaded == null) {
            this.buildLoading(content, footer);
            this.requestRealms();
        } else {
            this.buildRealmList(content, footer, loaded);
        }

        this.layout.visitWidgets(this::addRenderableWidget);
        this.repositionElements();
    }

    /** Ticks the sign-in countdown without rebuilding the widgets under it. */
    @Override
    public void tick() {
        final MsaDeviceCode code = this.deviceCode;
        if (this.expiryWidget != null && code != null) {
            this.expiryWidget.setMessage(expiryLine(code).withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    @Override
    protected void repositionElements() {
        if (this.layout != null) {
            this.layout.arrangeElements();
        }
    }

    // ---------------------------------------------------------------- states

    private void buildSignIn(final LinearLayout content, final LinearLayout footer) {
        content.addChild(new MultiLineTextWidget(
                Component.translatable("realmbridge.signin.blurb"), this.font)
                .setMaxWidth(CONTENT_WIDTH).setCentered(true));
        content.addChild(Button.builder(Component.translatable("realmbridge.signin.button"), b -> this.signIn())
                .size(BUTTON_WIDTH, BUTTON_HEIGHT).build()).active = !this.busy;
        this.addStatus(content);
        footer.addChild(this.backButton());
    }

    /**
     * The whole point of this screen when signed out: the user code has to be
     * big, obvious and copyable. The read-only edit box is deliberate - it makes
     * the code selectable with the mouse and Ctrl+C-able like any other field.
     */
    private void buildDeviceCode(final LinearLayout content, final LinearLayout footer,
                                 final MsaDeviceCode code) {
        content.addChild(new MultiLineTextWidget(
                Component.translatable("realmbridge.devicecode.instructions",
                        Component.literal(code.getVerificationUri()).withStyle(ChatFormatting.AQUA)), this.font)
                .setMaxWidth(CONTENT_WIDTH).setCentered(true));

        final LinearLayout codeRow = content.addChild(LinearLayout.horizontal().spacing(6));
        codeRow.defaultCellSetting().alignVerticallyMiddle();
        final EditBox codeField = new EditBox(this.font, 140, BUTTON_HEIGHT,
                Component.translatable("realmbridge.devicecode.field"));
        codeField.setMaxLength(64);
        codeField.setValue(code.getUserCode());
        codeField.setEditable(false);
        codeRow.addChild(codeField);
        codeRow.addChild(Button.builder(Component.translatable("realmbridge.devicecode.copy"), b -> {
            this.minecraft.keyboardHandler.setClipboard(code.getUserCode());
            this.setStatus(Component.translatable("realmbridge.devicecode.copied").withStyle(ChatFormatting.GREEN));
            this.refresh();
        }).size(80, BUTTON_HEIGHT).build());

        content.addChild(Button.builder(Component.translatable("realmbridge.devicecode.open"),
                        b -> Util.getPlatform().openUri(URI.create(code.getDirectVerificationUri())))
                .size(BUTTON_WIDTH, BUTTON_HEIGHT)
                .tooltip(Tooltip.create(Component.translatable("realmbridge.devicecode.open.tooltip")))
                .build());

        content.addChild(new StringWidget(CONTENT_WIDTH, this.font.lineHeight,
                Component.translatable("realmbridge.devicecode.waiting").withStyle(ChatFormatting.GRAY), this.font));
        this.expiryWidget = content.addChild(new StringWidget(CONTENT_WIDTH, this.font.lineHeight,
                expiryLine(code).withStyle(ChatFormatting.DARK_GRAY), this.font));

        this.addStatus(content);
        footer.addChild(this.backButton());
    }

    private void buildLoading(final LinearLayout content, final LinearLayout footer) {
        content.addChild(new StringWidget(CONTENT_WIDTH, this.font.lineHeight,
                Component.translatable("realmbridge.realms.loading").withStyle(ChatFormatting.GRAY), this.font));
        this.addStatus(content);
        footer.addChild(this.backButton());
    }

    private void buildRealmList(final LinearLayout content, final LinearLayout footer,
                                final List<RealmsServer> loaded) {
        // Nothing here scrolls, so only list what actually fits on this window.
        final int rowHeight = BUTTON_HEIGHT + ROW_SPACING;
        final int fits = Math.max(1, (this.height - CHROME_HEIGHT) / rowHeight);
        final int limit = Math.min(MAX_LISTED_REALMS, fits);
        final List<RealmsServer> shown = loaded.size() > limit ? loaded.subList(0, limit) : loaded;

        for (final RealmsServer realm : shown) {
            content.addChild(this.realmButton(realm));
        }
        if (shown.size() < loaded.size()) {
            content.addChild(new StringWidget(CONTENT_WIDTH, this.font.lineHeight,
                    Component.translatable("realmbridge.realms.more", loaded.size() - shown.size())
                            .withStyle(ChatFormatting.GRAY), this.font));
        }
        if (loaded.isEmpty()) {
            content.addChild(new MultiLineTextWidget(
                    Component.translatable("realmbridge.realms.empty"), this.font)
                    .setMaxWidth(CONTENT_WIDTH).setCentered(true));
        }

        final LinearLayout inviteRow = content.addChild(LinearLayout.horizontal().spacing(6));
        inviteRow.defaultCellSetting().alignVerticallyMiddle();
        final String carriedOver = this.inviteBox == null ? "" : this.inviteBox.getValue();
        this.inviteBox = new EditBox(this.font, 140, BUTTON_HEIGHT,
                Component.translatable("realmbridge.invite.hint"));
        this.inviteBox.setMaxLength(32);
        this.inviteBox.setHint(Component.translatable("realmbridge.invite.hint"));
        this.inviteBox.setValue(carriedOver);
        inviteRow.addChild(this.inviteBox);
        final Button add = Button.builder(Component.translatable("realmbridge.invite.add"), b -> this.acceptCode())
                .size(54, BUTTON_HEIGHT).build();
        add.active = !this.busy;
        inviteRow.addChild(add);

        this.addStatus(content);

        final Button refresh = Button.builder(Component.translatable("realmbridge.realms.refresh"), b -> {
            this.realms = null;
            this.rebuildWidgets();
        }).size(FOOTER_BUTTON_WIDTH, BUTTON_HEIGHT).build();
        refresh.active = !this.busy;
        footer.addChild(refresh);

        final Button signOut = Button.builder(Component.translatable("realmbridge.realms.signout"),
                b -> this.signOut()).size(FOOTER_BUTTON_WIDTH, BUTTON_HEIGHT).build();
        signOut.active = !this.busy;
        footer.addChild(signOut);

        footer.addChild(this.backButton());
    }

    // --------------------------------------------------------------- widgets

    private Button backButton() {
        return Button.builder(CommonComponents.GUI_BACK, b -> this.onClose())
                .size(FOOTER_BUTTON_WIDTH, BUTTON_HEIGHT).build();
    }

    private Button realmButton(final RealmsServer realm) {
        final boolean expired = realm.isExpired();
        final boolean usable = realm.isCompatible() && !expired;
        final String name = realm.getNameOr(realm.getMotdOr("Realm"));

        final MutableComponent label = usable
                ? Component.literal(name)
                : Component.translatable("realmbridge.realm.unavailable", name).withStyle(ChatFormatting.GRAY);

        final MutableComponent tooltip = Component.translatable("realmbridge.realm.tooltip.owner",
                realm.getOwnerNameOr("?"));
        final String version = realm.getActiveVersion();
        if (version != null && !version.isBlank()) {
            tooltip.append(CommonComponents.NEW_LINE)
                    .append(Component.translatable("realmbridge.realm.tooltip.version", version));
        }
        if (expired) {
            tooltip.append(CommonComponents.NEW_LINE)
                    .append(Component.translatable("realmbridge.realm.tooltip.expired").withStyle(ChatFormatting.RED));
        } else if (!realm.isCompatible()) {
            tooltip.append(CommonComponents.NEW_LINE)
                    .append(Component.translatable("realmbridge.realm.tooltip.incompatible").withStyle(ChatFormatting.RED));
        }

        final Button button = Button.builder(label, b -> this.join(realm))
                .size(BUTTON_WIDTH, BUTTON_HEIGHT)
                .tooltip(Tooltip.create(tooltip))
                .build();
        button.active = usable && !this.busy;
        return button;
    }

    /** The one status line, rebuilt from {@link #status} so it is never lost. */
    private void addStatus(final LinearLayout content) {
        final Component text = this.status;
        if (text.getString().isEmpty()) {
            return;
        }
        content.addChild(new MultiLineTextWidget(text, this.font)
                .setMaxWidth(CONTENT_WIDTH).setMaxRows(3).setCentered(true));
    }

    private static MutableComponent expiryLine(final MsaDeviceCode code) {
        final long remaining = Math.max(0L, code.getExpireTimeMs() - System.currentTimeMillis()) / 1000L;
        if (remaining == 0L) {
            return Component.translatable("realmbridge.devicecode.expired");
        }
        return Component.translatable("realmbridge.devicecode.expires",
                String.format("%d:%02d", remaining / 60L, remaining % 60L));
    }

    // ---------------------------------------------------------------- actions

    private void signIn() {
        this.busy = true;
        this.setStatus(Component.empty());
        this.refresh();
        this.core.async(() -> {
            this.core.auth().authManager(code -> {
                this.deviceCode = code;
                this.refresh();
            });
            this.deviceCode = null;
            this.busy = false;
            this.realms = null; // sign-in finished: pull the realm list next
            this.refresh();
        }, e -> {
            this.deviceCode = null;
            this.busy = false;
            this.setStatus(Component.translatable("realmbridge.status.signin_failed", RealmBridgeCore.rootMessage(e))
                    .withStyle(ChatFormatting.RED));
            this.refresh();
        });
    }

    /** Kicks off exactly one realm load, even though init() runs on every resize. */
    private void requestRealms() {
        if (this.busy || this.loadRequested) {
            return;
        }
        this.loadRequested = true;
        this.busy = true;
        this.core.async(() -> {
            final BedrockRealmsService service = this.core.realms();
            final List<RealmsServer> loaded = service.getWorlds();
            Diagnostics.logRealms(service, loaded);
            this.realms = loaded;
            this.busy = false;
            this.loadRequested = false;
            this.setStatus(loaded.isEmpty()
                    ? Component.empty()
                    : Component.translatable("realmbridge.realms.pick").withStyle(ChatFormatting.GRAY));
            this.refresh();
        }, e -> {
            this.realms = List.of();
            this.busy = false;
            this.loadRequested = false;
            this.setStatus(Component.translatable("realmbridge.status.realms_failed", RealmBridgeCore.rootMessage(e))
                    .withStyle(ChatFormatting.RED));
            this.refresh();
        });
    }

    private void acceptCode() {
        final String code = this.inviteBox == null ? "" : this.inviteBox.getValue().trim();
        if (code.isEmpty()) {
            this.setStatus(Component.translatable("realmbridge.invite.empty").withStyle(ChatFormatting.RED));
            this.refresh();
            return;
        }
        this.busy = true;
        this.setStatus(Component.translatable("realmbridge.invite.accepting").withStyle(ChatFormatting.GRAY));
        this.refresh();
        this.core.async(() -> {
            final RealmsServer realm = this.core.realms().acceptInvite(code);
            this.realms = this.core.realms().getWorlds();
            this.busy = false;
            this.minecraft.execute(() -> {
                if (this.inviteBox != null) {
                    this.inviteBox.setValue("");
                }
            });
            this.setStatus(Component.translatable("realmbridge.invite.joined", realm.getNameOr("realm"))
                    .withStyle(ChatFormatting.GREEN));
            this.refresh();
        }, e -> {
            this.busy = false;
            this.setStatus(Component.translatable("realmbridge.status.invite_failed", RealmBridgeCore.rootMessage(e))
                    .withStyle(ChatFormatting.RED));
            this.refresh();
        });
    }

    private void signOut() {
        this.busy = true;
        this.refresh();
        this.core.async(() -> {
            this.core.auth().logout();
            this.realms = null;
            this.deviceCode = null;
            this.busy = false;
            this.setStatus(Component.empty());
            this.refresh();
        }, e -> {
            this.busy = false;
            this.setStatus(Component.translatable("realmbridge.status.failed", RealmBridgeCore.rootMessage(e))
                    .withStyle(ChatFormatting.RED));
            this.refresh();
        });
    }

    private void join(final RealmsServer realm) {
        this.busy = true;
        this.setStatus(Component.translatable("realmbridge.status.waking", realm.getNameOr("realm"))
                .withStyle(ChatFormatting.GRAY));
        this.refresh();
        JoinFlow.start(this.core, realm, this.parent, text -> {
            this.setStatus(text);
            this.refresh();
        });
    }

    // ------------------------------------------------------------- plumbing

    private void setStatus(final Component text) {
        this.status = text;
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
