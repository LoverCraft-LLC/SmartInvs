package fr.minuskube.inv;

/**
 * Why a {@link SmartInventory} stopped being the inventory a player has open.
 *
 * <p>Reported to an {@link InventoryObserver} so an observer can tell a player who gave up on a
 * screen apart from one who simply moved on to the next screen of the same flow.</p>
 */
public enum MenuCloseReason {

    /**
     * Another {@link SmartInventory} was opened while this one was on screen — the player moved
     * on rather than leaving. Emitted from {@link SmartInventory#open(org.bukkit.entity.Player, int)}.
     */
    NAVIGATED,

    /**
     * The player closed the inventory (ESC, a click outside, or a plugin calling
     * {@link SmartInventory#close(org.bukkit.entity.Player)}).
     */
    CLOSED,

    /**
     * The player disconnected while the inventory was open.
     */
    QUIT,

    /**
     * The owning plugin was disabled (server shutdown or a reload) while the inventory was open.
     */
    DISABLE
}
