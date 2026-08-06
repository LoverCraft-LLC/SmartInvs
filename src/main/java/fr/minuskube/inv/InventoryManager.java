package fr.minuskube.inv;

import fr.minuskube.inv.content.InventoryContents;
import fr.minuskube.inv.opener.ChestInventoryOpener;
import fr.minuskube.inv.opener.InventoryOpener;
import fr.minuskube.inv.opener.SpecialInventoryOpener;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.logging.Level;

public class InventoryManager {

    private JavaPlugin plugin;
    private PluginManager pluginManager;

    private Map<UUID, SmartInventory> inventories;
    private Map<UUID, InventoryContents> contents;

    private List<InventoryOpener> defaultOpeners;
    private List<InventoryOpener> openers;

    private InventoryObserver observer = InventoryObserver.NOOP;

    public InventoryManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.pluginManager = Bukkit.getPluginManager();

        this.inventories = new HashMap<>();
        this.contents = new HashMap<>();

        this.defaultOpeners = Arrays.asList(
                new ChestInventoryOpener(),
                new SpecialInventoryOpener()
        );

        this.openers = new ArrayList<>();
    }

    public void init() {
        pluginManager.registerEvents(new InvListener(), plugin);

        new InvTask().runTaskTimer(plugin, 1, 1);
    }

    public Optional<InventoryOpener> findOpener(InventoryType type) {
        Optional<InventoryOpener> opInv = this.openers.stream()
                .filter(opener -> opener.supports(type))
                .findAny();

        if (!opInv.isPresent()) {
            opInv = this.defaultOpeners.stream()
                    .filter(opener -> opener.supports(type))
                    .findAny();
        }

        return opInv;
    }

    public void registerOpeners(InventoryOpener... openers) {
        this.openers.addAll(Arrays.asList(openers));
    }

    public List<Player> getOpenedPlayers(SmartInventory inv) {
        List<Player> list = new ArrayList<>();

        this.inventories.forEach((player, playerInv) -> {
            if (inv.equals(playerInv))
                list.add(Bukkit.getPlayer(player));
        });

        return list;
    }

    public Optional<SmartInventory> getInventory(Player p) {
        return Optional.ofNullable(this.inventories.get(p.getUniqueId()));
    }

    /**
     * Registers the witness notified whenever an inventory this manager owns is opened, clicked
     * or closed. There is one observer per manager; registering replaces the previous one.
     *
     * @param observer the observer to notify, or null to go back to {@link InventoryObserver#NOOP}
     */
    public void setObserver(InventoryObserver observer) {
        this.observer = observer == null ? InventoryObserver.NOOP : observer;
    }

    /**
     * @return the currently registered observer, never null
     */
    public InventoryObserver getObserver() {
        return this.observer;
    }

    protected void setInventory(Player p, SmartInventory inv) {
        setInventory(p, inv, MenuCloseReason.CLOSED);
    }

    /**
     * The single writer of the inventory map, in both directions — every open and every close
     * goes through here, which is what makes {@link InventoryObserver}'s open/close pairing a
     * guarantee rather than a hope. The reason is only consulted when clearing.
     *
     * @param p      the player whose current inventory is being set or cleared
     * @param inv    the inventory now on screen, or null to clear
     * @param reason why the inventory is being cleared; ignored when {@code inv} is not null
     */
    protected void setInventory(Player p, SmartInventory inv, MenuCloseReason reason) {
        if (inv == null) {
            SmartInventory previous = this.inventories.remove(p.getUniqueId());

            if (previous != null)
                this.observer.onMenuClose(p.getUniqueId(), previous, reason);
        } else {
            this.inventories.put(p.getUniqueId(), inv);
            this.observer.onMenuOpen(p.getUniqueId(), inv);
        }
    }

    public Optional<InventoryContents> getContents(Player p) {
        return Optional.ofNullable(this.contents.get(p.getUniqueId()));
    }

    protected void setContents(Player p, InventoryContents contents) {
        if (contents == null)
            this.contents.remove(p.getUniqueId());
        else
            this.contents.put(p.getUniqueId(), contents);
    }

    public void handleInventoryOpenError(SmartInventory inventory, Player player, Exception exception) {
        inventory.close(player);

        Bukkit.getLogger().log(Level.SEVERE, "Error while opening SmartInventory:", exception);
    }

    public void handleInventoryUpdateError(SmartInventory inventory, Player player, Exception exception) {
        inventory.close(player);

        Bukkit.getLogger().log(Level.SEVERE, "Error while updating SmartInventory:", exception);
    }

    @SuppressWarnings("unchecked")
    class InvListener implements Listener {

        @EventHandler(priority = EventPriority.LOW)
        public void onInventoryClick(InventoryClickEvent e) {
            Player p = (Player) e.getWhoClicked();

            if (!inventories.containsKey(p.getUniqueId()))
                return;

            SmartInventory inv = inventories.get(p.getUniqueId());
            Inventory topInventory = p.getOpenInventory().getTopInventory();
            int topSize = topInventory.getSize();

            // Classify by the authoritative raw slot.
            int rawSlot = e.getRawSlot();

            // Resolve click outside GUI
            if (rawSlot < 0 || rawSlot >= topSize) {
                InventoryAction action = e.getAction();
                if (action == InventoryAction.COLLECT_TO_CURSOR
                        || action == InventoryAction.MOVE_TO_OTHER_INVENTORY
                        || (action == InventoryAction.NOTHING && e.getClick() != ClickType.MIDDLE)) {
                    e.setResult(Event.Result.DENY);
                    e.setCancelled(true);
                    p.updateInventory();
                }
                return;
            }

            // Resolve click inside GUI
            e.setResult(Event.Result.DENY);
            e.setCancelled(true);

            int columns = inv.getColumns();
            int row = rawSlot / columns;
            int column = rawSlot % columns;

            if (row >= 0 && column >= 0 && row < inv.getRows() && column < inv.getColumns()) {
                observer.onMenuClick(p.getUniqueId(), inv);

                inv.getListeners().stream()
                        .filter(listener -> listener.getType() == InventoryClickEvent.class)
                        .forEach(listener -> ((InventoryListener<InventoryClickEvent>) listener).accept(e));

                InventoryContents invContents = contents.get(p.getUniqueId());
                if (invContents != null)
                    invContents.get(row, column).ifPresent(item -> item.run(e));

                if (inventories.get(p.getUniqueId()) == inv
                        && p.getOpenInventory().getTopInventory() == topInventory) {
                    InventoryContents current = contents.get(p.getUniqueId());
                    ClickableItem slotItem = current != null ? current.get(row, column).orElse(null) : null;
                    topInventory.setItem(rawSlot, slotItem != null ? slotItem.getItem() : null);
                }
            }

            p.updateInventory();
        }

        @EventHandler(priority = EventPriority.LOW)
        public void onInventoryDrag(InventoryDragEvent e) {
            Player p = (Player) e.getWhoClicked();

            if (!inventories.containsKey(p.getUniqueId()))
                return;

            SmartInventory inv = inventories.get(p.getUniqueId());

            int topSize = p.getOpenInventory().getTopInventory().getSize();
            for (int slot : e.getRawSlots()) {
                if (slot >= topSize)
                    continue;

                e.setResult(Event.Result.DENY);
                e.setCancelled(true);
                p.updateInventory();
                break;
            }

            inv.getListeners().stream()
                    .filter(listener -> listener.getType() == InventoryDragEvent.class)
                    .forEach(listener -> ((InventoryListener<InventoryDragEvent>) listener).accept(e));
        }

        @EventHandler(priority = EventPriority.LOW)
        public void onInventoryOpen(InventoryOpenEvent e) {
            Player p = (Player) e.getPlayer();

            if (!inventories.containsKey(p.getUniqueId()))
                return;

            SmartInventory inv = inventories.get(p.getUniqueId());

            inv.getListeners().stream()
                    .filter(listener -> listener.getType() == InventoryOpenEvent.class)
                    .forEach(listener -> ((InventoryListener<InventoryOpenEvent>) listener).accept(e));
        }

        @EventHandler(priority = EventPriority.LOW)
        public void onInventoryClose(InventoryCloseEvent e) {
            Player p = (Player) e.getPlayer();

            if (!inventories.containsKey(p.getUniqueId()))
                return;

            SmartInventory inv = inventories.get(p.getUniqueId());

            inv.getListeners().stream()
                    .filter(listener -> listener.getType() == InventoryCloseEvent.class)
                    .forEach(listener -> ((InventoryListener<InventoryCloseEvent>) listener).accept(e));

            if (inv.isCloseable()) {
                e.getInventory().clear();

                // Routed through setInventory/setContents rather than touching the maps directly
                // so this — the common close path — stays behind the single writer the observer
                // contract depends on. Behaviour is identical: both calls clear the same entries.
                setInventory(p, null, MenuCloseReason.CLOSED);
                setContents(p, null);
            } else
                Bukkit.getScheduler().runTask(plugin, () -> p.openInventory(e.getInventory()));
        }

        @EventHandler(priority = EventPriority.LOW)
        public void onPlayerQuit(PlayerQuitEvent e) {
            Player p = e.getPlayer();

            if (!inventories.containsKey(p.getUniqueId()))
                return;

            SmartInventory inv = inventories.get(p.getUniqueId());

            inv.getListeners().stream()
                    .filter(listener -> listener.getType() == PlayerQuitEvent.class)
                    .forEach(listener -> ((InventoryListener<PlayerQuitEvent>) listener).accept(e));

            setInventory(p, null, MenuCloseReason.QUIT);
            setContents(p, null);
        }

        @EventHandler(priority = EventPriority.LOW)
        public void onPluginDisable(PluginDisableEvent e) {
            new HashMap<>(inventories).forEach((uuid, inv) -> {
                inv.getListeners().stream()
                        .filter(listener -> listener.getType() == PluginDisableEvent.class)
                        .forEach(listener -> ((InventoryListener<PluginDisableEvent>) listener).accept(e));

                Player player = Bukkit.getPlayer(uuid);

                if (player != null) {
                    inv.close(player, MenuCloseReason.DISABLE);
                } else {
                    // Nobody to close an inventory for; drop the entry ourselves so the observer
                    // still sees the close (SmartInventory.close would NPE on a null player).
                    inventories.remove(uuid);
                    contents.remove(uuid);
                    observer.onMenuClose(uuid, inv, MenuCloseReason.DISABLE);
                }
            });

            inventories.clear();
            contents.clear();
        }

    }

    class InvTask extends BukkitRunnable {

        @Override
        public void run() {
            Iterator<UUID> iterator = inventories.keySet().iterator();

            while (iterator.hasNext()) {
                UUID uuid = iterator.next();
                SmartInventory inventory = inventories.get(uuid);

                Player player = Bukkit.getPlayer(uuid);
                if (player == null) {
                    iterator.remove();
                    contents.remove(uuid);
                    // Fourth exit from the map, and the only one with no Player left to hand over:
                    // the player went offline without this manager seeing a close or a quit. Still
                    // a close as far as an observer is concerned, or the pairing would leak.
                    observer.onMenuClose(uuid, inventory, MenuCloseReason.QUIT);
                    continue;
                }

                try {
                    inventory.getProvider().update(player, contents.get(uuid));
                } catch (Exception e) {
                    handleInventoryUpdateError(inventory, player, e);
                }
            }
        }

    }

}
