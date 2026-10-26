package fr.aurexalium.aurexawars;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

@SuppressWarnings("deprecation")
public class Arena {

    public enum State { WAITING, STARTING, PLAYING, ENDING }

    private static class Saved {
        Location location;
        ItemStack[] contents;
        ItemStack[] armor;
        GameMode mode;
        double health;
        int food;
        int level;
        float exp;
        boolean allowFlight;
    }

    private static class Snap {
        final int id;
        final byte data;
        Snap(int id, byte data) { this.id = id; this.data = data; }
    }

    private final AurexaWars plugin;
    private final String name;
    private final Random random = new Random();
    private int minPlayers = 2;

    private Location lobby, spec, center;
    private final List<Location> spawns = new ArrayList<Location>();
    private Cuboid region;

    private State state = State.WAITING;
    private final Set<UUID> members = new LinkedHashSet<UUID>();
    private final Set<UUID> alive = new LinkedHashSet<UUID>();
    private final List<UUID> eliminated = new ArrayList<UUID>();
    private final Map<UUID, Integer> kills = new HashMap<UUID, Integer>();
    private final Map<UUID, Saved> saved = new HashMap<UUID, Saved>();
    private final Map<Location, Snap> changes = new HashMap<Location, Snap>();
    private final Map<UUID, UUID> lastHit = new HashMap<UUID, UUID>();
    private final Map<UUID, Long> lastHitTime = new HashMap<UUID, Long>();

    private boolean frozen = false;
    private int freezeLeft;
    private int refillLeft;
    private int elapsed;
    private int countdown;
    private BukkitTask countdownTask;
    private BukkitTask gameTask;
    private BukkitTask endTask;

    public Arena(AurexaWars plugin, String name) {
        this.plugin = plugin;
        this.name = name;
    }

    // ------------------------------------------------------------
    //  Configuration de l'arene
    // ------------------------------------------------------------

    public String getName() { return name; }
    public int getMinPlayers() { return minPlayers; }
    public int getMaxPlayers() { return spawns.size(); }
    public int getPlayerCount() { return members.size(); }
    public int getSpawnCount() { return spawns.size(); }
    public State getState() { return state; }
    public String getStateName() { return state.name(); }
    public boolean isAlive(UUID u) { return alive.contains(u); }
    public boolean containsLocation(Location l) { return region != null && region.contains(l); }

    public void setMinPlayers(int n) { this.minPlayers = n; }
    public void setLobby(Location l) { this.lobby = l; }
    public void setSpec(Location l) { this.spec = l; }
    public void setCenter(Location l) { this.center = l; }
    public void addSpawn(Location l) { spawns.add(l); }
    public void clearSpawns() { spawns.clear(); }
    public void setRegion(Cuboid c) { this.region = c; }

    public boolean isConfigured() {
        return lobby != null && spec != null && region != null && spawns.size() >= 2;
    }

    public List<String> problems() {
        List<String> p = new ArrayList<String>();
        if (!isConfigured()) return p;
        for (Location s : spawns) {
            if (s.getY() <= region.getMinY()) {
                p.add("Le bas de la zone de jeu doit etre EN DESSOUS des iles (refais /sw setregion).");
                break;
            }
        }
        if (minPlayers > spawns.size()) {
            p.add("Le minimum de joueurs (" + minPlayers + ") depasse le nombre d'iles (" + spawns.size() + ").");
        }
        return p;
    }

    public String info() {
        StringBuilder err = new StringBuilder();
        for (String s : problems()) err.append("\n&c✘ ").append(s);
        return "&7Iles : &f" + spawns.size() + " &7(joueurs max) &8| &7Minimum : &f" + minPlayers + "\n"
                + "&7Lobby : " + ok(lobby != null) + " &7Spectateurs : " + ok(spec != null)
                + " &7Centre : " + ok(center != null) + " &7Zone de jeu : " + ok(region != null)
                + err.toString();
    }

    private String ok(boolean b) { return b ? "&a✔" : "&c✘"; }

    public void save(ConfigurationSection s) {
        s.set("min", minPlayers);
        s.set("lobby", lobby);
        s.set("spec", spec);
        s.set("center", center);
        s.set("spawns", spawns);
        if (region != null) region.save(s.createSection("region"));
    }

    public static Arena load(AurexaWars plugin, String name, ConfigurationSection s) {
        Arena a = new Arena(plugin, name);
        a.minPlayers = s.getInt("min", 2);
        a.lobby = (Location) s.get("lobby");
        a.spec = (Location) s.get("spec");
        a.center = (Location) s.get("center");
        List<?> list = s.getList("spawns");
        if (list != null) {
            for (Object o : list) {
                if (o instanceof Location) a.spawns.add((Location) o);
            }
        }
        a.region = Cuboid.load(s.getConfigurationSection("region"));
        return a;
    }

    // ------------------------------------------------------------
    //  Rejoindre / quitter
    // ------------------------------------------------------------

    public void join(Player p) {
        if (!isConfigured()) {
            plugin.msg(p, "&cCette arène n'est pas encore configurée.");
            return;
        }
        List<String> problems = problems();
        if (!problems.isEmpty()) {
            plugin.msg(p, "&cCette arène est mal configurée, préviens un admin.");
            if (p.hasPermission("aurexawars.admin")) {
                for (String s : problems) plugin.msg(p, "&c" + s);
            }
            return;
        }
        if (state != State.WAITING && state != State.STARTING) {
            plugin.msg(p, "&cLa partie est déjà en cours.");
            return;
        }
        if (members.size() >= getMaxPlayers()) {
            plugin.msg(p, "&cL'arène est pleine.");
            return;
        }

        Saved s = new Saved();
        s.location = p.getLocation();
        s.contents = p.getInventory().getContents();
        s.armor = p.getInventory().getArmorContents();
        s.mode = p.getGameMode();
        s.health = p.getHealth();
        s.food = p.getFoodLevel();
        s.level = p.getLevel();
        s.exp = p.getExp();
        s.allowFlight = p.getAllowFlight();
        saved.put(p.getUniqueId(), s);

        members.add(p.getUniqueId());
        plugin.registerPlayer(p.getUniqueId(), this);

        p.getInventory().clear();
        p.getInventory().setArmorContents(new ItemStack[4]);
        p.setGameMode(GameMode.ADVENTURE);
        p.setHealth(p.getMaxHealth());
        p.setFoodLevel(20);
        p.setLevel(0);
        p.setExp(0f);
        p.setFireTicks(0);
        for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
        p.teleport(lobby);
        p.getInventory().setItem(8, plugin.leaveItem());   // objet "quitter" en case 9
        p.updateInventory();

        broadcast("&6" + p.getName() + " &erejoint la partie &7(" + members.size() + "/" + getMaxPlayers() + ")");

        if (state == State.WAITING && members.size() >= minPlayers) {
            startCountdown();
        }
        updateScoreboards();
        refreshSigns();
    }

    public void leave(Player p) {
        UUID u = p.getUniqueId();
        if (!members.contains(u)) return;

        boolean wasAlive = alive.contains(u);
        if (state == State.PLAYING && wasAlive) {
            dropItems(p);
            eliminated.add(u);
        }
        members.remove(u);
        alive.remove(u);
        kills.remove(u);
        lastHit.remove(u);
        lastHitTime.remove(u);
        plugin.unregisterPlayer(u);
        restore(p);
        showAll(p);
        p.setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());

        broadcast("&6" + p.getName() + " &equitte la partie.");

        if (members.isEmpty()) {
            reset();
            return;
        }
        if (state == State.STARTING && members.size() < minPlayers) {
            cancelCountdown();
            state = State.WAITING;
            broadcast("&cPas assez de joueurs, le décompte est annulé.");
        } else if (state == State.PLAYING && wasAlive) {
            checkWin();
        }
        updateScoreboards();
        refreshSigns();
    }

    public void shutdown() {
        for (UUID u : new ArrayList<UUID>(members)) {
            Player p = Bukkit.getPlayer(u);
            plugin.unregisterPlayer(u);
            if (p != null) {
                restore(p);
                showAll(p);
                p.setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());
            }
        }
        reset();
    }

    // ------------------------------------------------------------
    //  Deroulement de la partie
    // ------------------------------------------------------------

    private void startCountdown() {
        state = State.STARTING;
        refreshSigns();
        countdown = plugin.getConfig().getInt("start-countdown", 20);
        countdownTask = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            public void run() {
                if (countdown <= 0) {
                    cancelCountdown();
                    startGame();
                    return;
                }
                if (countdown <= 5 || countdown % 10 == 0) {
                    broadcast("&eDébut dans &6" + countdown + "&e seconde(s)...");
                    playSound(Sound.NOTE_PLING);
                }
                countdown--;
            }
        }, 0L, 20L);
    }

    private void cancelCountdown() {
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
    }

    private void startGame() {
        state = State.PLAYING;
        refreshSigns();
        changes.clear();
        alive.clear();
        alive.addAll(members);
        eliminated.clear();
        kills.clear();
        lastHit.clear();
        lastHitTime.clear();
        elapsed = 0;
        refillLeft = plugin.getConfig().getInt("refill-time", 180);
        freezeLeft = plugin.getConfig().getInt("freeze-seconds", 5);
        frozen = true;

        fillChests();

        List<Location> islands = new ArrayList<Location>(spawns);
        Collections.shuffle(islands);
        int i = 0;
        for (UUID u : members) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            Location spawn = islands.get(i % islands.size());
            i++;
            p.getInventory().clear();
            p.getInventory().setArmorContents(new ItemStack[4]);
            p.setGameMode(GameMode.SURVIVAL);
            p.setAllowFlight(false);
            p.setFlying(false);
            p.setHealth(p.getMaxHealth());
            p.setFoodLevel(20);
            p.setFireTicks(0);
            p.setFallDistance(0f);
            p.setVelocity(new Vector(0, 0, 0));
            p.teleport(spawn);
            p.updateInventory();
        }
        broadcast("&aLa partie commence ! Pille les coffres et élimine tes adversaires.");
        playSound(Sound.LEVEL_UP);
        updateScoreboards();

        gameTask = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            public void run() {
                if (state != State.PLAYING) return;
                if (frozen) {
                    if (freezeLeft > 0) {
                        broadcast("&6" + freezeLeft + "...");
                        playSound(Sound.NOTE_PLING);
                        freezeLeft--;
                    } else {
                        frozen = false;
                        broadcast("&a&lQue le meilleur gagne !");
                        playSound(Sound.LEVEL_UP);
                    }
                    updateScoreboards();
                    return;
                }
                elapsed++;
                if (refillLeft > 0) {
                    refillLeft--;
                    if (refillLeft == 0) {
                        fillChests();
                        broadcast("&6Les coffres ont été remplis !");
                        playSound(Sound.LEVEL_UP);
                    }
                }
                int max = plugin.getConfig().getInt("max-time", 900);
                if (max > 0 && elapsed >= max) {
                    timeUp();
                    return;
                }
                updateScoreboards();
            }
        }, 0L, 20L);
    }

    private void timeUp() {
        broadcast("&cTemps écoulé ! Le joueur avec le plus de kills gagne.");
        UUID best = null;
        int bestKills = -1;
        for (UUID u : alive) {
            Integer k = kills.get(u);
            int kk = k == null ? 0 : k;
            if (kk > bestKills) {
                bestKills = kk;
                best = u;
            }
        }
        if (best != null) win(best);
        else finish();
    }

    // ------------------------------------------------------------
    //  Coffres
    // ------------------------------------------------------------

    private void fillChests() {
        World w = Bukkit.getWorld(region.getWorldName());
        if (w == null) return;
        int radius = plugin.getConfig().getInt("chests.mid-radius", 15);
        long r2 = (long) radius * radius;
        for (int cx = region.getMinX() >> 4; cx <= (region.getMaxX() >> 4); cx++) {
            for (int cz = region.getMinZ() >> 4; cz <= (region.getMaxZ() >> 4); cz++) {
                Chunk ch = w.getChunkAt(cx, cz);
                for (BlockState st : ch.getTileEntities()) {
                    if (!(st instanceof Chest)) continue;
                    Location l = st.getLocation();
                    if (!region.contains(l)) continue;
                    boolean mid = center != null && center.getWorld() != null
                            && center.getWorld().equals(l.getWorld())
                            && l.distanceSquared(center) <= r2;
                    fill(((Chest) st).getInventory(), mid);
                }
            }
        }
    }

    private void fill(Inventory inv, boolean mid) {
        inv.clear();
        FileConfiguration c = plugin.getConfig();
        List<String> lines = c.getStringList(mid ? "loot.mid" : "loot.normal");
        int min = c.getInt(mid ? "chests.mid-min" : "chests.normal-min", mid ? 5 : 3);
        int max = c.getInt(mid ? "chests.mid-max" : "chests.normal-max", mid ? 9 : 6);
        int count = min + random.nextInt(Math.max(1, max - min + 1));
        for (int i = 0; i < count; i++) {
            ItemStack it = randomItem(lines);
            if (it == null) continue;
            for (int t = 0; t < 10; t++) {
                int slot = random.nextInt(inv.getSize());
                if (inv.getItem(slot) == null) {
                    inv.setItem(slot, it);
                    break;
                }
            }
        }
    }

    // une ligne = "MATERIAL min max poids"
    private ItemStack randomItem(List<String> lines) {
        List<String[]> parts = new ArrayList<String[]>();
        int totalWeight = 0;
        for (String l : lines) {
            String[] p = l.trim().split("\\s+");
            if (p.length < 4) continue;
            Material m = Material.matchMaterial(p[0]);
            if (m == null) continue;
            try {
                Integer.parseInt(p[1]);
                Integer.parseInt(p[2]);
                int w = Integer.parseInt(p[3]);
                if (w <= 0) continue;
                totalWeight += w;
                parts.add(p);
            } catch (NumberFormatException ex) {
                continue;
            }
        }
        if (parts.isEmpty()) return null;
        int r = random.nextInt(totalWeight);
        for (String[] p : parts) {
            r -= Integer.parseInt(p[3]);
            if (r < 0) {
                int min = Integer.parseInt(p[1]);
                int max = Integer.parseInt(p[2]);
                int amount = min + random.nextInt(Math.max(1, max - min + 1));
                return new ItemStack(Material.matchMaterial(p[0]), amount);
            }
        }
        return null;
    }

    // ------------------------------------------------------------
    //  Morts, victoire
    // ------------------------------------------------------------

    public void die(Player victim) {
        UUID v = victim.getUniqueId();
        if (state != State.PLAYING || !alive.contains(v)) return;

        Player killer = null;
        UUID k = lastHit.get(v);
        Long time = lastHitTime.get(v);
        if (k != null && time != null && System.currentTimeMillis() - time < 15000L && !k.equals(v)) {
            killer = Bukkit.getPlayer(k);
        }
        if (killer != null && alive.contains(killer.getUniqueId())) {
            broadcast("&6" + victim.getName() + " &ea été tué par &6" + killer.getName() + "&e.");
            Integer n = kills.get(killer.getUniqueId());
            kills.put(killer.getUniqueId(), n == null ? 1 : n + 1);
        } else {
            broadcast("&6" + victim.getName() + " &eest mort.");
        }
        lastHit.remove(v);
        lastHitTime.remove(v);

        dropItems(victim);
        victim.getWorld().strikeLightningEffect(victim.getLocation());
        alive.remove(v);
        eliminated.add(v);
        toSpec(victim);
        plugin.msg(victim, "&cTu es éliminé ! Tu peux regarder la fin ou quitter avec le lit.");
        broadcast("&7Joueurs restants : &f" + alive.size());
        updateScoreboards();
        checkWin();
    }

    private void dropItems(Player p) {
        Location l = p.getLocation();
        World w = p.getWorld();
        for (ItemStack it : p.getInventory().getContents()) {
            if (it != null && it.getType() != Material.AIR) w.dropItemNaturally(l, it);
        }
        for (ItemStack it : p.getInventory().getArmorContents()) {
            if (it != null && it.getType() != Material.AIR) w.dropItemNaturally(l, it);
        }
        p.getInventory().clear();
        p.getInventory().setArmorContents(new ItemStack[4]);
    }

    // un joueur elimine devient spectateur : il vole, et les joueurs en vie ne le voient pas
    private void toSpec(Player p) {
        p.getInventory().clear();
        p.getInventory().setArmorContents(new ItemStack[4]);
        p.setGameMode(GameMode.ADVENTURE);
        p.setAllowFlight(true);
        p.setFlying(true);
        p.setHealth(p.getMaxHealth());
        p.setFoodLevel(20);
        p.setFireTicks(0);
        p.setFallDistance(0f);
        p.setVelocity(new Vector(0, 0, 0));
        p.teleport(spec);
        p.getInventory().setItem(8, plugin.leaveItem());
        p.updateInventory();
        for (UUID a : alive) {
            Player ap = Bukkit.getPlayer(a);
            if (ap != null) ap.hidePlayer(p);
        }
    }

    private void showAll(Player p) {
        for (Player o : Bukkit.getOnlinePlayers()) {
            p.showPlayer(o);
            o.showPlayer(p);
        }
    }

    private void checkWin() {
        if (state != State.PLAYING) return;
        if (alive.size() == 1) {
            win(alive.iterator().next());
        } else if (alive.isEmpty()) {
            finish();
        }
    }

    private void win(UUID winner) {
        state = State.ENDING;
        refreshSigns();
        if (gameTask != null) {
            gameTask.cancel();
            gameTask = null;
        }
        frozen = false;

        Player wp = Bukkit.getPlayer(winner);
        broadcast("&6&l★ &e" + (wp != null ? wp.getName() : "?") + " &egagne la partie !");
        playSound(Sound.LEVEL_UP);

        UUID second = null;
        for (int i = eliminated.size() - 1; i >= 0; i--) {
            if (members.contains(eliminated.get(i))) {
                second = eliminated.get(i);
                break;
            }
        }
        for (UUID u : members) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            if (u.equals(winner)) giveCoins(p, plugin.getConfig().getInt("coins.win", 100));
            else if (u.equals(second)) giveCoins(p, plugin.getConfig().getInt("coins.second", 40));
            else giveCoins(p, plugin.getConfig().getInt("coins.participation", 10));
            showAll(p);
        }
        updateScoreboards();
        endTask = Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
            public void run() { finish(); }
        }, 140L);
    }

    private void finish() {
        for (UUID u : new ArrayList<UUID>(members)) {
            Player p = Bukkit.getPlayer(u);
            plugin.unregisterPlayer(u);
            if (p != null) {
                restore(p);
                showAll(p);
                p.setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());
            }
        }
        reset();
    }

    private void reset() {
        cancelCountdown();
        if (gameTask != null) {
            gameTask.cancel();
            gameTask = null;
        }
        if (endTask != null) {
            endTask.cancel();
            endTask = null;
        }
        restoreBlocks();
        clearItems();
        members.clear();
        alive.clear();
        eliminated.clear();
        kills.clear();
        saved.clear();
        lastHit.clear();
        lastHitTime.clear();
        frozen = false;
        state = State.WAITING;
        refreshSigns();
    }

    private void giveCoins(Player p, int amount) {
        if (amount <= 0) return;
        String cmd = plugin.getConfig().getString("coins-command", "");
        if (cmd == null || cmd.trim().isEmpty()) return;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                cmd.replace("{player}", p.getName()).replace("{amount}", String.valueOf(amount)));
    }

    // ------------------------------------------------------------
    //  Blocs, objets au sol
    // ------------------------------------------------------------

    public boolean canPlace(Player p, Block b) {
        return state == State.PLAYING && !frozen && alive.contains(p.getUniqueId()) && region.contains(b.getLocation());
    }

    public boolean canBreak(Player p, Block b) {
        return state == State.PLAYING && !frozen && alive.contains(p.getUniqueId()) && region.contains(b.getLocation());
    }

    public boolean canInteract(Player p) {
        return state == State.PLAYING && !frozen && alive.contains(p.getUniqueId());
    }

    public void recordChange(Location loc, Material m, byte data) {
        Location key = loc.getBlock().getLocation();
        if (!changes.containsKey(key)) {
            changes.put(key, new Snap(m.getId(), data));
        }
    }

    private void restoreBlocks() {
        for (Map.Entry<Location, Snap> e : changes.entrySet()) {
            e.getKey().getBlock().setTypeIdAndData(e.getValue().id, e.getValue().data, false);
        }
        changes.clear();
    }

    private void clearItems() {
        if (region == null) return;
        World w = Bukkit.getWorld(region.getWorldName());
        if (w == null) return;
        for (Entity en : w.getEntities()) {
            if (en instanceof Item && region.contains(en.getLocation())) en.remove();
        }
    }

    // ------------------------------------------------------------
    //  Evenements
    // ------------------------------------------------------------

    public void handleMove(PlayerMoveEvent e) {
        if (state != State.PLAYING) return;
        Player p = e.getPlayer();
        if (!alive.contains(p.getUniqueId())) return;
        Location to = e.getTo();

        if (frozen) {
            Location from = e.getFrom();
            if (from.getX() != to.getX() || from.getZ() != to.getZ()) {
                Location t = from.clone();
                t.setYaw(to.getYaw());
                t.setPitch(to.getPitch());
                e.setTo(t);
            }
            return;
        }
        if (to.getY() < region.getMinY()) {
            die(p);
        }
    }

    public void handleDamage(EntityDamageEvent e, Player victim) {
        UUID v = victim.getUniqueId();
        if (state != State.PLAYING || frozen || !alive.contains(v)) {
            e.setCancelled(true);
            return;
        }
        if (e instanceof EntityDamageByEntityEvent) {
            Entity d = ((EntityDamageByEntityEvent) e).getDamager();
            if (d instanceof Projectile && ((Projectile) d).getShooter() instanceof Entity) {
                d = (Entity) ((Projectile) d).getShooter();
            }
            if (d instanceof Player) {
                Player a = (Player) d;
                if (!alive.contains(a.getUniqueId())) {
                    e.setCancelled(true);
                    return;
                }
                if (!a.getUniqueId().equals(v)) {
                    lastHit.put(v, a.getUniqueId());
                    lastHitTime.put(v, System.currentTimeMillis());
                }
            }
        }
        e.setCancelled(false);   // un joueur invincible (op, god, mode staff) peut etre touche en partie
        if (e.getFinalDamage() >= victim.getHealth()) {
            e.setCancelled(true);
            die(victim);
        }
    }

    // ------------------------------------------------------------
    //  Divers
    // ------------------------------------------------------------

    private void restore(Player p) {
        restoreInventory(p);
        String cmd = plugin.getConfig().getString("end-command", "invreset {player}");
        if (cmd != null && !cmd.trim().isEmpty()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{player}", p.getName()));
        }
    }

    private void restoreInventory(Player p) {
        Saved s = saved.remove(p.getUniqueId());
        p.getInventory().clear();
        p.getInventory().setArmorContents(new ItemStack[4]);
        for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
        p.setFireTicks(0);
        p.setFlying(false);
        p.setAllowFlight(false);
        if (s == null) return;
        p.getInventory().setContents(s.contents);
        p.getInventory().setArmorContents(s.armor);
        p.setGameMode(s.mode);
        p.setAllowFlight(s.allowFlight);
        p.setHealth(Math.min(s.health, p.getMaxHealth()));
        p.setFoodLevel(s.food);
        p.setLevel(s.level);
        p.setExp(s.exp);
        p.teleport(s.location);
        p.updateInventory();
    }

    private void refreshSigns() {
        plugin.updateSigns(this);
    }

    private void broadcast(String s) {
        for (UUID u : members) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.sendMessage(plugin.getPrefix() + plugin.color(s));
        }
    }

    private void playSound(Sound sound) {
        for (UUID u : members) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.playSound(p.getLocation(), sound, 1f, 1f);
        }
    }

    private String shortName(String n, int max) {
        return n.length() > max ? n.substring(0, max) : n;
    }

    private String time(int seconds) {
        return (seconds / 60) + ":" + (seconds % 60 < 10 ? "0" : "") + (seconds % 60);
    }

    private void updateScoreboards() {
        for (UUID u : members) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;

            Scoreboard sb = Bukkit.getScoreboardManager().getNewScoreboard();
            Objective o = sb.registerNewObjective("aurexawars", "dummy");
            o.setDisplaySlot(DisplaySlot.SIDEBAR);
            o.setDisplayName(ChatColor.GOLD + "" + ChatColor.BOLD + "AurexaWars");

            List<String> lines = new ArrayList<String>();
            if (state == State.WAITING || state == State.STARTING) {
                lines.add(ChatColor.GRAY + "Arène :");
                lines.add(ChatColor.WHITE + shortName(name, 14));
                lines.add(ChatColor.RESET.toString());
                lines.add(ChatColor.GRAY + "Joueurs :");
                lines.add(ChatColor.YELLOW + "" + members.size() + "/" + getMaxPlayers());
            } else {
                Integer k = kills.get(u);
                lines.add(ChatColor.GRAY + "Joueurs: " + ChatColor.WHITE + alive.size());
                lines.add(ChatColor.GRAY + "Kills: " + ChatColor.WHITE + (k == null ? 0 : k));
                lines.add(ChatColor.RESET.toString());
                lines.add(ChatColor.GRAY + "Temps: " + ChatColor.WHITE + time(elapsed));
                if (refillLeft > 0) {
                    lines.add(ChatColor.GRAY + "Refill: " + ChatColor.WHITE + time(refillLeft));
                } else {
                    lines.add(ChatColor.GRAY + "Refill: " + ChatColor.GREEN + "fait");
                }
            }
            int score = lines.size();
            for (String l : lines) {
                o.getScore(l).setScore(score--);
            }
            p.setScoreboard(sb);
        }
    }
}
