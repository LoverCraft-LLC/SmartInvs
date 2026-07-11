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
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

public class InventoryManager {

    private JavaPlugin plugin;
    private PluginManager pluginManager;

    private Map<UUID, SmartInventory> inventories;
    private Map<UUID, InventoryContents> contents;

    private List<InventoryOpener> defaultOpeners;
    private List<InventoryOpener> openers;

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

    protected void setInventory(Player p, SmartInventory inv) {
        if (inv == null)
            this.inventories.remove(p.getUniqueId());
        else
            this.inventories.put(p.getUniqueId(), inv);
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

            // Classify by the authoritative raw slot rather than by inventory object-identity:
            // getRawSlot() < topSize is a click inside the GUI, otherwise it is the player's own
            // inventory (or -999 for a click outside the window). This is robust against the object
            // identity ever mismatching and against a null getClickedInventory().
            int rawSlot = e.getRawSlot();

            // ---- Click resolved OUTSIDE the GUI (player's own inventory, or -999) ----
            if (rawSlot < 0 || rawSlot >= topSize) {
                // Only actions that can pull FROM or push INTO the top GUI are blocked; ordinary
                // interaction with the player's own inventory is left untouched.
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

            // ---- Click resolved INSIDE the GUI (top inventory): the painted item never moves ----
            e.setResult(Event.Result.DENY);
            e.setCancelled(true);

            int columns = inv.getColumns();
            int row = rawSlot / columns;
            int column = rawSlot % columns;

            if (row >= 0 && column >= 0 && row < inv.getRows() && column < inv.getColumns()) {
                inv.getListeners().stream()
                        .filter(listener -> listener.getType() == InventoryClickEvent.class)
                        .forEach(listener -> ((InventoryListener<InventoryClickEvent>) listener).accept(e));

                InventoryContents invContents = contents.get(p.getUniqueId());
                if (invContents != null)
                    invContents.get(row, column).ifPresent(item -> item.run(e));

                // Authoritative re-paint: overwrite the client's view of the clicked slot with the
                // item the server knows belongs there, so a lag/desync-mispredicted "pickup" cannot
                // persist as a real grabbed item. Skipped when the click handler opened/replaced the
                // menu (guard: still the same tracked inventory AND same top inventory) so we never
                // corrupt a freshly-opened inventory. The slot is RE-READ after the consumer runs so
                // an in-place slot mutation by the consumer (toggle / counter / claim) is preserved,
                // not reverted to the pre-click item.
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

                inventories.remove(p.getUniqueId());
                contents.remove(p.getUniqueId());
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

            inventories.remove(p.getUniqueId());
            contents.remove(p.getUniqueId());
        }

        @EventHandler(priority = EventPriority.LOW)
        public void onPluginDisable(PluginDisableEvent e) {
            new HashMap<>(inventories).forEach((player, inv) -> {
                inv.getListeners().stream()
                        .filter(listener -> listener.getType() == PluginDisableEvent.class)
                        .forEach(listener -> ((InventoryListener<PluginDisableEvent>) listener).accept(e));

                inv.close(Bukkit.getPlayer(player));
            });

            inventories.clear();
            contents.clear();
        }

    }

    class InvTask extends BukkitRunnable {

        @Override
        public void run() {
            new HashMap<>(inventories).forEach((uuid, inv) -> {
                Player player = Bukkit.getPlayer(uuid);

                try {
                    inv.getProvider().update(player, contents.get(uuid));
                } catch (Exception e) {
                    handleInventoryUpdateError(inv, player, e);
                }
            });
        }

    }

}
