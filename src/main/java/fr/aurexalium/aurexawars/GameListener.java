package fr.aurexalium.aurexawars;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

@SuppressWarnings("deprecation")
public class GameListener implements Listener {

    private final AurexaWars plugin;

    public GameListener(AurexaWars plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Arena a = plugin.getArena(e.getPlayer());
        if (a != null) a.leave(e.getPlayer());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Arena a = plugin.getArena(e.getPlayer());
        if (a != null) a.handleMove(e);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        Player p = (Player) e.getEntity();
        Arena a = plugin.getArena(p);
        if (a != null) a.handleDamage(e, p);
    }

    // ---- blocs ----

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Arena a = plugin.getArena(e.getPlayer());
        if (a == null) return;
        if (!a.canPlace(e.getPlayer(), e.getBlock())) {
            e.setCancelled(true);
            return;
        }
        BlockState old = e.getBlockReplacedState();
        a.recordChange(e.getBlock().getLocation(), old.getType(), old.getRawData());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Arena a = plugin.getArena(e.getPlayer());
        if (a == null) return;
        Block b = e.getBlock();
        if (!a.canBreak(e.getPlayer(), b)) {
            e.setCancelled(true);
            return;
        }
        a.recordChange(b.getLocation(), b.getType(), b.getData());
    }

    @EventHandler
    public void onExplode(EntityExplodeEvent e) {
        Arena a = plugin.getArenaAt(e.getLocation());
        if (a == null || a.getState() != Arena.State.PLAYING) return;
        for (Block b : e.blockList()) {
            a.recordChange(b.getLocation(), b.getType(), b.getData());
        }
    }

    // les seaux sont bloques : l'eau et la lave qui coulent ne peuvent pas etre remises en etat
    @EventHandler
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        if (plugin.getArena(e.getPlayer()) != null) e.setCancelled(true);
    }

    @EventHandler
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (plugin.getArena(e.getPlayer()) != null) e.setCancelled(true);
    }

    // ---- joueurs ----

    @EventHandler
    public void onFood(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player && plugin.getArena((Player) e.getEntity()) != null) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        Arena a = plugin.getArena(e.getPlayer());
        if (a == null) return;
        if (a.getState() != Arena.State.PLAYING || !a.isAlive(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onPickup(PlayerPickupItemEvent e) {
        Arena a = plugin.getArena(e.getPlayer());
        if (a == null) return;
        if (a.getState() != Arena.State.PLAYING || !a.isAlive(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked();
        Arena a = plugin.getArena(p);
        if (a == null) return;
        // avant le debut et pour les spectateurs : on ne touche a rien
        if (a.getState() != Arena.State.PLAYING || !a.isAlive(p.getUniqueId())) {
            e.setCancelled(true);
        }
    }

    // ---- panneaux ----

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreakSign(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (!plugin.isRegisteredSign(b)) return;
        if (e.getPlayer().hasPermission("aurexawars.admin")) {
            plugin.unregisterSign(b);
            plugin.msg(e.getPlayer(), "&ePanneau AurexaWars supprimé.");
        } else {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        Action act = e.getAction();
        Arena inArena = plugin.getArena(p);
        boolean rightClick = act == Action.RIGHT_CLICK_BLOCK || act == Action.RIGHT_CLICK_AIR;

        // objet "quitter la partie"
        if (inArena != null && rightClick) {
            ItemStack it = e.getItem();
            if (it != null && it.getType() == Material.BED && it.hasItemMeta()
                    && it.getItemMeta().hasDisplayName()
                    && it.getItemMeta().getDisplayName().equals(plugin.color(AurexaWars.LEAVE_NAME))) {
                e.setCancelled(true);
                inArena.leave(p);
                plugin.msg(p, "&eTu as quitté la partie.");
                return;
            }
        }

        // clic droit sur un panneau
        if (act == Action.RIGHT_CLICK_BLOCK && e.getClickedBlock().getState() instanceof Sign) {
            Block b = e.getClickedBlock();

            String pending = plugin.takePendingSign(p.getUniqueId());
            if (pending != null) {
                e.setCancelled(true);
                plugin.registerSign(b, pending);
                plugin.msg(p, "&aPanneau lié à l'arène &6" + pending + "&a.");
                return;
            }

            Arena target = plugin.getSignArena(b);
            if (target != null) {
                e.setCancelled(true);
                if (inArena != null) {
                    plugin.msg(p, "&cTu es déjà dans une partie.");
                    return;
                }
                target.join(p);
                return;
            }
        }

        // en partie : pas d'interaction pour les spectateurs, ni avant le debut (coffres, portes...)
        if (inArena != null && !inArena.canInteract(p)) {
            e.setCancelled(true);
        }
    }
}
