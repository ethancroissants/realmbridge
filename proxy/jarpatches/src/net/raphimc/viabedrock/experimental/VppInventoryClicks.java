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
import net.raphimc.viabedrock.protocol.data.enums.bedrock.ComplexInventoryTransaction_Type;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.ContainerID;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySourceType;
import net.raphimc.viabedrock.protocol.data.enums.bedrock.generated.InventorySource_InventorySourceFlags;
import net.raphimc.viabedrock.protocol.data.enums.java.generated.ContainerInput;
import net.raphimc.viabedrock.protocol.model.BedrockItem;
import net.raphimc.viabedrock.protocol.storage.InventoryTracker;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Container click emulation for every window: the player inventory and any open
 * container (chest, shulker, furnace, ...).
 *
 * ViaBedrock's {@code Container#handleClick} is a stub returning false and no
 * subclass overrides it, so upstream answers every click by pushing the tracked
 * contents back at the client - items snap back to where they were. Bedrock
 * clients in legacy (non server-authoritative) inventory mode apply the move
 * locally and report it as an InventoryTransaction, which is what this does.
 */
public final class VppInventoryClicks {

    private static final int MAX_STACK = 64;
    private static final int CURSOR_SLOT = 0; // HUD container slot 0 holds the cursor

    private VppInventoryClicks() {
    }

    private record Target(Container container, int slot) {
    }

    private record Change(Container container, int slot, BedrockItem from, BedrockItem to) {
    }

    /**
     * Maps a Java window slot to the Bedrock container + slot.
     *
     * @param open the open container, or null for the player's own inventory window
     */
    private static Target resolve(final InventoryTracker tracker, final Container open, final int javaSlot) {
        final Container inventory = tracker.getInventoryContainer();
        if (open == null) { // player inventory window
            if (javaSlot >= 36 && javaSlot <= 44) return new Target(inventory, javaSlot - 36); // hotbar
            if (javaSlot >= 9 && javaSlot <= 35) return new Target(inventory, javaSlot); // main
            if (javaSlot >= 5 && javaSlot <= 8) return new Target(tracker.getArmorContainer(), javaSlot - 5);
            if (javaSlot == 45) return new Target(tracker.getOffhandContainer(), 0);
            if (javaSlot >= 1 && javaSlot <= 4) return new Target(tracker.getHudContainer(), 27 + javaSlot); // crafting grid
            return null; // crafting result
        }
        final int size = open.size();
        if (javaSlot < size) return new Target(open, javaSlot);
        if (javaSlot < size + 27) return new Target(inventory, javaSlot - size + 9); // player main
        if (javaSlot < size + 36) return new Target(inventory, javaSlot - size - 27); // hotbar
        return null;
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

    /** Region of the player inventory a quick-move should target. */
    private record Region(Container container, int start, int end) {
    }

    private static List<Region> quickMoveTargets(final InventoryTracker tracker, final Container open, final Target source) {
        final Container inventory = tracker.getInventoryContainer();
        final List<Region> regions = new ArrayList<>(2);
        if (open != null && source.container() == open) { // container -> player: main, then hotbar
            regions.add(new Region(inventory, 9, 36));
            regions.add(new Region(inventory, 0, 9));
        } else if (open != null) { // player -> container
            regions.add(new Region(open, 0, open.size()));
        } else if (source.container() == inventory && source.slot() < 9) { // hotbar -> main
            regions.add(new Region(inventory, 9, 36));
        } else { // main / armor / offhand -> hotbar
            regions.add(new Region(inventory, 0, 9));
        }
        return regions;
    }

    /**
     * Applies a click to the tracked containers and reports it to the server.
     *
     * @return true when the click was emulated (the client's own prediction stands)
     */
    public static boolean handleClick(final UserConnection user, final Container open,
                                      final short javaSlot, final byte button, final ContainerInput action) {
        try {
            final InventoryTracker tracker = user.get(InventoryTracker.class);
            if (tracker == null) return false;
            final Container hud = tracker.getHudContainer();
            final Container inventory = tracker.getInventoryContainer();

            final List<Change> changes = new ArrayList<>();
            final BedrockItem cursor = hud.getItem(CURSOR_SLOT);
            boolean dropped = false;

            if (javaSlot == -999) { // click outside the window: throw the cursor stack
                if (cursor.isEmpty()) return true;
                final int amount = button == 1 ? 1 : cursor.amount();
                set(changes, hud, CURSOR_SLOT, cursor.amount() - amount <= 0
                        ? BedrockItem.empty() : withAmount(cursor, cursor.amount() - amount));
                dropped = true;
                applyAndReport(user, changes, withAmount(cursor, amount));
                PacketFactory.sendJavaContainerSetContent(user, open != null ? open : inventory);
                return true;
            }

            final Target target = resolve(tracker, open, javaSlot);
            if (target == null) return false;
            final BedrockItem clicked = target.container().getItem(target.slot());

            switch (action) {
                case PICKUP -> {
                    if (button == 0) { // left: take all / place all / merge / swap
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
                        } else {
                            set(changes, target.container(), target.slot(), cursor);
                            set(changes, hud, CURSOR_SLOT, clicked);
                        }
                    } else if (button == 1) { // right: take half / place one
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
                case SWAP -> { // number keys 1-9 swap with the hotbar
                    if (button < 0 || button > 8) return false;
                    final BedrockItem hotbarItem = inventory.getItem(button);
                    if (hotbarItem.isEmpty() && clicked.isEmpty()) return true;
                    set(changes, inventory, button, clicked);
                    set(changes, target.container(), target.slot(), hotbarItem);
                }
                case THROW -> { // Q: drop one / drop stack
                    if (clicked.isEmpty()) return true;
                    final int amount = button == 0 ? 1 : clicked.amount();
                    set(changes, target.container(), target.slot(), clicked.amount() - amount <= 0
                            ? BedrockItem.empty() : withAmount(clicked, clicked.amount() - amount));
                    applyAndReport(user, changes, withAmount(clicked, amount));
                    PacketFactory.sendJavaContainerSetContent(user, open != null ? open : inventory);
                    return true;
                }
                case PICKUP_ALL -> { // double click: gather matching stacks into the cursor
                    if (cursor.isEmpty()) return true;
                    int held = cursor.amount();
                    final List<Region> regions = new ArrayList<>();
                    if (open != null) regions.add(new Region(open, 0, open.size()));
                    regions.add(new Region(inventory, 0, 36));
                    for (final Region region : regions) {
                        for (int slot = region.start(); slot < region.end() && held < MAX_STACK; slot++) {
                            final BedrockItem candidate = region.container().getItem(slot);
                            if (!sameItem(candidate, cursor)) continue;
                            final int taken = Math.min(candidate.amount(), MAX_STACK - held);
                            held += taken;
                            set(changes, region.container(), slot, candidate.amount() - taken <= 0
                                    ? BedrockItem.empty() : withAmount(candidate, candidate.amount() - taken));
                        }
                    }
                    if (held == cursor.amount()) return true;
                    set(changes, hud, CURSOR_SLOT, withAmount(cursor, held));
                }
                case QUICK_MOVE -> { // shift click
                    if (clicked.isEmpty()) return true;
                    int remaining = clicked.amount();
                    for (final Region region : quickMoveTargets(tracker, open, target)) {
                        for (int slot = region.start(); slot < region.end() && remaining > 0; slot++) { // merge
                            if (region.container() == target.container() && slot == target.slot()) continue;
                            final BedrockItem candidate = region.container().getItem(slot);
                            if (sameItem(candidate, clicked) && candidate.amount() < MAX_STACK) {
                                final int moved = Math.min(MAX_STACK - candidate.amount(), remaining);
                                set(changes, region.container(), slot, withAmount(candidate, candidate.amount() + moved));
                                remaining -= moved;
                            }
                        }
                        for (int slot = region.start(); slot < region.end() && remaining > 0; slot++) { // first empty
                            if (region.container() == target.container() && slot == target.slot()) continue;
                            if (region.container().getItem(slot).isEmpty()) {
                                set(changes, region.container(), slot, withAmount(clicked, remaining));
                                remaining = 0;
                            }
                        }
                    }
                    if (remaining == clicked.amount()) return true; // nowhere to put it
                    set(changes, target.container(), target.slot(), remaining <= 0
                            ? BedrockItem.empty() : withAmount(clicked, remaining));
                }
                default -> {
                    return false; // CLONE (creative) and QUICK_CRAFT drags fall through
                }
            }

            if (changes.isEmpty()) return true;
            applyAndReport(user, changes, dropped ? cursor : null);
            PacketFactory.sendJavaContainerSetContent(user, open != null ? open : inventory);
            return true;
        } catch (Throwable e) {
            ViaBedrock.getPlatform().getLogger().log(Level.WARNING, "[VP+] inventory click emulation failed", e);
            return false;
        }
    }

    /** Applies the changes to the tracked containers and sends the matching transaction. */
    private static void applyAndReport(final UserConnection user, final List<Change> changes, final BedrockItem droppedItem) {
        final List<InventoryActionData> actions = new ArrayList<>(changes.size() + 1);
        for (final Change change : changes) {
            actions.add(new InventoryActionData(
                    new InventorySource(InventorySourceType.Container_Inventory,
                            change.container().containerId(), InventorySource_InventorySourceFlags.No_Flag),
                    change.slot(), change.from(), change.to()));
        }
        if (droppedItem != null && !droppedItem.isEmpty()) { // the world receives the dropped stack
            actions.add(new InventoryActionData(
                    new InventorySource(InventorySourceType.World_Interaction,
                            ContainerID.CONTAINER_ID_NONE.getValue(), InventorySource_InventorySourceFlags.No_Flag),
                    0, BedrockItem.empty(), droppedItem));
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
