package dev.neilb.realmbridge.mixin;

import com.mojang.realmsclient.RealmsMainScreen;
import dev.neilb.realmbridge.RealmBridgeButton;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.Layout;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adds "Bedrock Realms" to the button block at the bottom of the vanilla Realms
 * screen, spanning the full width under Play/Configure/Renew/Leave/Add/Back.
 *
 * It is appended to the footer's own {@link GridLayout} rather than positioned
 * by hand, so it inherits the vanilla column widths, spacing and re-layout on
 * resize, and lands in the right place in every layout state (the footer holds
 * fewer buttons while the realm list is still loading).
 *
 * {@code require = 0} is deliberate: if Mojang reshapes this footer, the button
 * quietly does not appear instead of crashing the game on startup. The
 * /realmbridge chat commands still reach every feature in that case.
 */
@Mixin(RealmsMainScreen.class)
public abstract class RealmsMainScreenMixin {

    /** The vanilla footer grid: 3 columns of 100px with 4px spacing. */
    private static final int FOOTER_COLUMNS = 3;
    private static final int FOOTER_WIDTH = 308;

    @Inject(method = "createFooter", at = @At("RETURN"), require = 0)
    private void realmbridge$addBedrockRealmsButton(@Coerce final Object layoutState,
                                                    final CallbackInfoReturnable<Layout> cir) {
        if (!(cir.getReturnValue() instanceof GridLayout footer)) {
            return;
        }
        final Screen self = (Screen) (Object) this;
        final int[] cells = {0};
        footer.visitChildren(child -> cells[0]++);
        final int row = (cells[0] + FOOTER_COLUMNS - 1) / FOOTER_COLUMNS;

        footer.addChild(RealmBridgeButton.create(self, FOOTER_WIDTH), row, 0, 1, FOOTER_COLUMNS);
        RealmBridgeButton.markPlacedByMixin();
    }

}
