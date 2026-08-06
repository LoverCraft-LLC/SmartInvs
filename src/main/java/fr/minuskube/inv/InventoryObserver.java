package fr.minuskube.inv;

import java.util.UUID;

/**
 * A read-only witness to what happens to the inventories an {@link InventoryManager} owns:
 * one callback when a {@link SmartInventory} reaches the screen, one per click that lands on it,
 * and exactly one when it leaves the screen.
 *
 * <p>The point of this hook is that SmartInvs sits <em>below</em> everything that would want to
 * measure it — the LoverCraft Commons library depends on SmartInvs, never the other way round —
 * so instrumentation cannot be written here. An observer lets the layer above register itself
 * without SmartInvs gaining a dependency on anything: every callback takes a player {@link UUID}
 * and this library's own types, nothing else.</p>
 *
 * <p><strong>Guarantee:</strong> every {@link #onMenuOpen} is followed by exactly one
 * {@link #onMenuClose} for the same player, because {@link InventoryManager#setInventory} is the
 * single writer of the manager's inventory map in both directions. Players are identified by UUID
 * rather than by {@code Player} because one of the close paths (the update task noticing the
 * player is gone) has no {@code Player} object left to hand over.</p>
 *
 * <p><strong>Callbacks run on the server main thread</strong>, inside the open/click/close call
 * that triggered them. An implementation must be cheap and must never throw — SmartInvs does not
 * guard against it, and an exception here would abort a menu open.</p>
 *
 * @see InventoryManager#setObserver(InventoryObserver)
 */
public interface InventoryObserver {

    /**
     * An observer that does nothing — the default an {@link InventoryManager} starts with, so the
     * hook costs a virtual call and nothing else when nobody registered one.
     */
    InventoryObserver NOOP = new InventoryObserver() {

        @Override
        public void onMenuOpen(UUID player, SmartInventory inventory) {
        }

        @Override
        public void onMenuClick(UUID player, SmartInventory inventory) {
        }

        @Override
        public void onMenuClose(UUID player, SmartInventory inventory, MenuCloseReason reason) {
        }
    };

    /**
     * Called once the inventory has actually been drawn for the player — after the
     * {@link fr.minuskube.inv.opener.InventoryOpener} succeeded, never before.
     *
     * @param player    the player the inventory was opened for
     * @param inventory the inventory now on screen
     */
    void onMenuOpen(UUID player, SmartInventory inventory);

    /**
     * Called for every click that lands inside the inventory's own grid. Clicks on the player's
     * own inventory, and clicks outside any slot, are not reported.
     *
     * @param player    the player who clicked
     * @param inventory the inventory that was clicked
     */
    void onMenuClick(UUID player, SmartInventory inventory);

    /**
     * Called once the inventory has left the screen, for any reason.
     *
     * @param player    the player the inventory was open for
     * @param inventory the inventory that left the screen
     * @param reason    why it left
     */
    void onMenuClose(UUID player, SmartInventory inventory, MenuCloseReason reason);
}
