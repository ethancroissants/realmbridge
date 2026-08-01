package net.raphimc.viabedrock.experimental;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.packet.PacketWrapper;
import net.raphimc.viabedrock.ViaBedrock;
import net.raphimc.viabedrock.api.model.container.Container;
import net.raphimc.viabedrock.api.util.PacketFactory;
import net.raphimc.viabedrock.experimental.model.inventory.BedrockInventoryTransaction;
import net.raphimc.viabedrock.experimental.model.inventory.InventoryActionData;
import net.raphimc.viabedrock.experimental.model.inventory.InventorySource;
import net.raphimc.viabedrock.experimental.model.inventory.InventoryTransactionData;
import net.raphimc.viabedrock.experimental.rewriter.InventoryTransactionRewriter;
import net.raphimc.viabedrock.protocol.BedrockProtocol;
import net.raphimc.viabedrock.protocol.ServerboundBedrockPackets;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ComplexInventoryTransaction_Type;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySourceType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySource_InventorySourceFlags;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.ContainerInput;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Player-inventory click emulation.
 *
 * ViaBedrock has no handler for clicks in the player's own inventory window:
 * the click is cancelled and the tracked contents are pushed back to the Java
 * client, so every drag snaps back. Bedrock clients in legacy (non
 * server-authoritative) inventory mode apply the move locally and report it to
 * the server as an InventoryTransaction - which is what this does.
 */
public final class VppInventoryClicks {

    private static final int MAX_STACK = 64;
    private static final int CURSOR_SLOT = 0; // HUD container slot 0 is the cursor

    private VppInventoryClicks() {
    }

    private record Target(Container container, int slot) {
    }

    private record Change(Container container, int slot, BedrockItem from, BedrockItem to) {
    }

    /** Maps a Java player-inventory window slot to the Bedrock container + slot. */
    private static Target resolve(final InventoryTracker tracker, final int javaSlot) {
        if (javaSlot >= 36 && javaSlot <= 44) return new Target(tracker.getInventoryContainer(), javaSlot - 36); // hotbar
        if (javaSlot >= 9 && javaSlot <= 35) return new Target(tracker.getInventoryContainer(), javaSlot); // main
        if (javaSlot >= 5 && javaSlot <= 8) return new Target(tracker.getArmorContainer(), javaSlot - 5);
        if (javaSlot == 45) return new Target(tracker.getOffhandContainer(), 0);
        if (javaSlot >= 1 && javaSlot <= 4) return new Target(tracker.getHudContainer(), 27 + javaSlot); // crafting grid
        return null; // crafting result and anything unexpected
    }

    private static boolean sameItem(final BedrockItem a, final BedrockItem b) {
        return !a.isEmpty() && !b.isEmpty() && a.identifier() == b.identifier() && a.data() == b.data()
                && a.tag() == null && b.tag() == null;
    }

    private static BedrockItem withAmount(final BedrockItem item, final int amount) {
        final BedrockItem copy = (BedrockItem) item.copy();
        copy.setAmount(amount);
        return copy;
    }

    private static void set(final List<Change> changes, final Container container, final int slot, final BedrockItem to) {
        changes.add(new Change(container, slot, container.getItem(slot), to));
    }

    /**
     * Applies a click to the tracked inventory and tells the server about it.
     *
     * @return true when the click was emulated (the client's own prediction stands)
     */
    public static boolean handleClick(final UserConnection user, final short javaSlot, final byte button, final ContainerInput action) {
        try {
            final InventoryTracker tracker = user.get(InventoryTracker.class);
            if (tracker == null) return false;
            final Container hud = tracker.getHudContainer();
            final Container inventory = tracker.getInventoryContainer();
            final Target target = resolve(tracker, javaSlot);
            if (target == null) return false;

            final List<Change> changes = new ArrayList<>();
            final BedrockItem cursor = hud.getItem(CURSOR_SLOT);
            final BedrockItem clicked = target.container().getItem(target.slot());

            switch (action) {
                case PICKUP -> {
                    if (button == 0) { // left click: take all / place all / merge / swap
                        if (cursor.isEmpty()) {
                            if (clicked.isEmpty()) return true;
                            set(changes, hud, CURSOR_SLOT, clicked);
                            set(changes, target.container(), target.slot(), BedrockItem.empty());
                        } else if (clicked.isEmpty()) {
                            set(changes, target.container(), target.slot(), cursor);
                            set(changes, hud, CURSOR_SLOT, BedrockItem.empty());
                        } else if (sameItem(cursor, clicked)) {
                            final int moved = Math.min(cursor.amount(), MAX_STACK - clicked.amount());
                            if (moved <= 0) return true;
                            set(changes, target.container(), target.slot(), withAmount(clicked, clicked.amount() + moved));
                            set(changes, hud, CURSOR_SLOT, cursor.amount() - moved <= 0
                                    ? BedrockItem.empty() : withAmount(cursor, cursor.amount() - moved));
                        } else { // swap
                            set(changes, target.container(), target.slot(), cursor);
                            set(changes, hud, CURSOR_SLOT, clicked);
                        }
                    } else if (button == 1) { // right click: take half / place one
                        if (cursor.isEmpty()) {
                            if (clicked.isEmpty()) return true;
                            final int taken = (clicked.amount() + 1) / 2;
                            set(changes, hud, CURSOR_SLOT, withAmount(clicked, taken));
                            set(changes, target.container(), target.slot(), clicked.amount() - taken <= 0
                                    ? BedrockItem.empty() : withAmount(clicked, clicked.amount() - taken));
                        } else if (clicked.isEmpty()) {
                            set(changes, target.container(), target.slot(), withAmount(cursor, 1));
                            set(changes, hud, CURSOR_SLOT, cursor.amount() - 1 <= 0
                                    ? BedrockItem.empty() : withAmount(cursor, cursor.amount() - 1));
                        } else if (sameItem(cursor, clicked) && clicked.amount() < MAX_STACK) {
                            set(changes, target.container(), target.slot(), withAmount(clicked, clicked.amount() + 1));
                            set(changes, hud, CURSOR_SLOT, cursor.amount() - 1 <= 0
                                    ? BedrockItem.empty() : withAmount(cursor, cursor.amount() - 1));
                        } else {
                            set(changes, target.container(), target.slot(), cursor);
                            set(changes, hud, CURSOR_SLOT, clicked);
                        }
                    } else {
                        return false;
                    }
                }
                case SWAP -> { // number key: swap with hotbar slot
                    if (button < 0 || button > 8) return false;
                    final BedrockItem hotbarItem = inventory.getItem(button);
                    if (hotbarItem.isEmpty() && clicked.isEmpty()) return true;
                    set(changes, inventory, button, clicked);
                    set(changes, target.container(), target.slot(), hotbarItem);
                }
                case QUICK_MOVE -> { // shift click: hotbar <-> main inventory
                    if (clicked.isEmpty()) return true;
                    final int start;
                    final int end;
                    if (target.container() == inventory && target.slot() < 9) {
                        start = 9; end = 36; // hotbar -> main
                    } else {
                        start = 0; end = 9;  // main / armor / offhand -> hotbar
                    }
                    int remaining = clicked.amount();
                    for (int slot = start; slot < end && remaining > 0; slot++) { // merge
                        final BedrockItem candidate = inventory.getItem(slot);
                        if (sameItem(candidate, clicked) && candidate.amount() < MAX_STACK) {
                            final int moved = Math.min(MAX_STACK - candidate.amount(), remaining);
                            set(changes, inventory, slot, withAmount(candidate, candidate.amount() + moved));
                            remaining -= moved;
                        }
                    }
                    for (int slot = start; slot < end && remaining > 0; slot++) { // first empty
                        if (inventory.getItem(slot).isEmpty()) {
                            set(changes, inventory, slot, withAmount(clicked, remaining));
                            remaining = 0;
                        }
                    }
                    if (remaining == clicked.amount()) return true; // nowhere to go
                    set(changes, target.container(), target.slot(), remaining <= 0
                            ? BedrockItem.empty() : withAmount(clicked, remaining));
                }
                default -> {
                    return false;
                }
            }

            if (changes.isEmpty()) return true;
            apply(user, changes);
            PacketFactory.sendJavaContainerSetContent(user, inventory);
            return true;
        } catch (Throwable e) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "[VP+] inventory click emulation failed", e);
            return false;
        }
    }

    private static void apply(final UserConnection user, final List<Change> changes) {
        final List<InventoryActionData> actions = new ArrayList<>(changes.size());
        for (final Change change : changes) {
            actions.add(new InventoryActionData(
                    new InventorySource(InventorySourceType.ContainerInventory,
                            change.container().containerId(), InventorySource_InventorySourceFlags.NoFlag),
                    change.slot(), change.from(), change.to()));
        }
        for (final Change change : changes) { // apply after snapshotting the "from" items
            change.container().setItem(change.slot(), change.to());
        }

        final InventoryTransactionRewriter rewriter = user.get(InventoryTransactionRewriter.class);
        if (rewriter == null) return;
        final PacketWrapper transaction = PacketWrapper.create(ServerboundBedrockPackets.INVENTORY_TRANSACTION, user);
        transaction.write(rewriter.getInventoryTransactionType(), new BedrockInventoryTransaction(
                0, null, actions,
                ComplexInventoryTransaction_Type.NormalTransaction,
                new InventoryTransactionData.NormalTransactionData()));
        transaction.sendToServer(BedrockProtocol.class);
    }

}
