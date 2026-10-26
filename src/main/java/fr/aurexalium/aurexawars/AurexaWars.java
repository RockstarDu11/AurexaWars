package fr.aurexalium.aurexawars;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class AurexaWars extends JavaPlugin {

    public static final String LEAVE_NAME = "&cQuitter la partie";

    private final Map<String, Arena> arenas = new LinkedHashMap<String, Arena>();
    private final Map<UUID, Arena> playerArenas = new HashMap<UUID, Arena>();
    private final Map<UUID, Location> pos1 = new HashMap<UUID, Location>();
    private final Map<UUID, Location> pos2 = new HashMap<UUID, Location>();
    // panneaux : "monde;x;y;z" -> nom de l'arene (en minuscules)
    private final Map<String, String> signs = new HashMap<String, String>();
    private final Map<UUID, String> pendingSigns = new HashMap<UUID, String>();
    private File arenasFile;
    private String prefix;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        prefix = color(getConfig().getString("prefix", "&6&lAurexalium &c&l>> &e"));
        arenasFile = new File(getDataFolder(), "arenas.yml");
        getServer().getPluginManager().registerEvents(new GameListener(this), this);
        // on charge les arenes apres le chargement des mondes
        Bukkit.getScheduler().runTask(this, new Runnable() {
            public void run() { loadArenas(); }
        });
        getLogger().info("AurexaWars active.");
    }

    @Override
    public void onDisable() {
        for (Arena a : arenas.values()) a.shutdown();
        playerArenas.clear();
    }

    // ------------------------------------------------------------
    //  Utilitaires
    // ------------------------------------------------------------

    public String color(String s) { return ChatColor.translateAlternateColorCodes('&', s); }
    public String getPrefix() { return prefix; }
    public void msg(CommandSender s, String text) { s.sendMessage(prefix + color(text)); }

    public Arena getArena(Player p) { return playerArenas.get(p.getUniqueId()); }
    public void registerPlayer(UUID u, Arena a) { playerArenas.put(u, a); }
    public void unregisterPlayer(UUID u) { playerArenas.remove(u); }
    private Arena findArena(String n) { return arenas.get(n.toLowerCase()); }

    public Arena getArenaAt(Location l) {
        for (Arena a : arenas.values()) {
            if (a.containsLocation(l)) return a;
        }
        return null;
    }

    public ItemStack leaveItem() {
        ItemStack i = new ItemStack(Material.BED);
        ItemMeta meta = i.getItemMeta();
        meta.setDisplayName(color(LEAVE_NAME));
        List<String> lore = new ArrayList<String>();
        lore.add(color("&7Clic droit pour quitter la partie"));
        meta.setLore(lore);
        i.setItemMeta(meta);
        return i;
    }

    // ------------------------------------------------------------
    //  Sauvegarde
    // ------------------------------------------------------------

    private void saveArenas() {
        YamlConfiguration cfg = new YamlConfiguration();
        ConfigurationSection root = cfg.createSection("arenas");
        for (Arena a : arenas.values()) {
            a.save(root.createSection(a.getName()));
        }
        List<String> signList = new ArrayList<String>();
        for (Map.Entry<String, String> e : signs.entrySet()) {
            signList.add(e.getKey() + ";" + e.getValue());
        }
        cfg.set("signs", signList);
        try {
            cfg.save(arenasFile);
        } catch (IOException e) {
            getLogger().severe("Impossible de sauvegarder arenas.yml : " + e.getMessage());
        }
    }

    private void loadArenas() {
        arenas.clear();
        if (!arenasFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(arenasFile);
        ConfigurationSection root = cfg.getConfigurationSection("arenas");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            arenas.put(key.toLowerCase(), Arena.load(this, key, root.getConfigurationSection(key)));
        }
        getLogger().info(arenas.size() + " arene(s) chargee(s).");
        signs.clear();
        for (String s : cfg.getStringList("signs")) {
            String[] p = s.split(";");
            if (p.length == 5) signs.put(p[0] + ";" + p[1] + ";" + p[2] + ";" + p[3], p[4]);
        }
        updateAllSigns();
    }

    // ------------------------------------------------------------
    //  Commandes
    // ------------------------------------------------------------

    private void help(CommandSender s) {
        msg(s, "&6/sw join <arène> &e- rejoindre une partie");
        msg(s, "&6/sw leave &e- quitter la partie");
        msg(s, "&6/sw list &e- liste des arènes");
        if (s.hasPermission("aurexawars.admin")) {
            msg(s, "&c--- Admin ---");
            msg(s, "&6/sw create <nom> &e/ &6/sw delete <nom>");
            msg(s, "&6/sw setlobby <nom> &e(salle d'attente)");
            msg(s, "&6/sw setspec <nom> &e(zone des spectateurs)");
            msg(s, "&6/sw addspawn <nom> &e(une île, à répéter pour chaque île)");
            msg(s, "&6/sw clearspawns <nom> &e(supprime toutes les îles)");
            msg(s, "&6/sw setcenter <nom> &e(centre de la map : coffres mid)");
            msg(s, "&6/sw pos1 &e/ &6/sw pos2 &e+ &6/sw setregion <nom> &e(zone de jeu)");
            msg(s, "&6/sw setmin <nom> <n> &e(joueurs minimum)");
            msg(s, "&6/sw sign <nom> &e(puis clic droit sur un panneau vierge)");
            msg(s, "&6/sw info <nom>");
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }
        String sub = args[0].toLowerCase();

        if (sub.equals("list")) {
            if (arenas.isEmpty()) {
                msg(sender, "&cAucune arène.");
                return true;
            }
            for (Arena a : arenas.values()) {
                msg(sender, "&6" + a.getName() + " &f" + a.getStateName()
                        + " &7(" + a.getPlayerCount() + "/" + a.getMaxPlayers() + ")"
                        + (a.isConfigured() ? "" : " &c[non configurée]"));
            }
            return true;
        }

        if (!(sender instanceof Player)) {
            msg(sender, "&cCommande réservée aux joueurs.");
            return true;
        }
        Player p = (Player) sender;

        if (sub.equals("join")) {
            if (args.length < 2) { msg(p, "&cUtilisation : &6/sw join <arène>"); return true; }
            if (getArena(p) != null) { msg(p, "&cTu es déjà dans une partie (&6/sw leave&c)."); return true; }
            Arena a = findArena(args[1]);
            if (a == null) { msg(p, "&cArène introuvable."); return true; }
            a.join(p);
            return true;
        }
        if (sub.equals("leave")) {
            Arena a = getArena(p);
            if (a == null) { msg(p, "&cTu n'es dans aucune partie."); return true; }
            a.leave(p);
            msg(p, "&eTu as quitté la partie.");
            return true;
        }

        // ---------- commandes admin ----------
        if (!p.hasPermission("aurexawars.admin")) {
            msg(p, "&cTu n'as pas la permission.");
            return true;
        }

        if (sub.equals("pos1")) {
            pos1.put(p.getUniqueId(), p.getLocation());
            msg(p, "&ePosition 1 définie.");
            return true;
        }
        if (sub.equals("pos2")) {
            pos2.put(p.getUniqueId(), p.getLocation());
            msg(p, "&ePosition 2 définie.");
            return true;
        }

        if (sub.equals("create")) {
            if (args.length < 2) { msg(p, "&cUtilisation : &6/sw create <nom>"); return true; }
            if (findArena(args[1]) != null) { msg(p, "&cCette arène existe déjà."); return true; }
            arenas.put(args[1].toLowerCase(), new Arena(this, args[1]));
            saveArenas();
            msg(p, "&aArène &6" + args[1] + " &acréée. Configure-la avec &6/sw info " + args[1]);
            return true;
        }

        if (args.length < 2) {
            help(p);
            return true;
        }
        Arena a = findArena(args[1]);
        if (a == null) {
            msg(p, "&cArène introuvable.");
            return true;
        }

        if (sub.equals("delete")) {
            a.shutdown();
            arenas.remove(args[1].toLowerCase());
            saveArenas();
            msg(p, "&eArène supprimée.");
            return true;
        }
        if (sub.equals("info")) {
            for (String line : a.info().split("\n")) msg(p, line);
            return true;
        }
        if (sub.equals("setlobby")) {
            a.setLobby(p.getLocation());
            saveArenas();
            msg(p, "&aSalle d'attente définie.");
            return true;
        }
        if (sub.equals("setspec")) {
            a.setSpec(p.getLocation());
            saveArenas();
            msg(p, "&aZone des spectateurs définie.");
            return true;
        }
        if (sub.equals("setcenter")) {
            a.setCenter(p.getLocation());
            saveArenas();
            msg(p, "&aCentre de la map défini (coffres mid autour de ce point).");
            return true;
        }
        if (sub.equals("addspawn")) {
            a.addSpawn(p.getLocation());
            saveArenas();
            msg(p, "&aÎle &6" + a.getSpawnCount() + " &aajoutée (&6" + a.getSpawnCount() + " &ajoueurs maximum).");
            return true;
        }
        if (sub.equals("clearspawns")) {
            a.clearSpawns();
            saveArenas();
            msg(p, "&eToutes les îles ont été supprimées.");
            return true;
        }
        if (sub.equals("setregion")) {
            Cuboid c = selection(p);
            if (c == null) return true;
            a.setRegion(c);
            saveArenas();
            msg(p, "&aZone de jeu définie. Un joueur qui tombe sous Y=&6" + c.getMinY() + " &aest éliminé.");
            return true;
        }
        if (sub.equals("sign")) {
            pendingSigns.put(p.getUniqueId(), args[1].toLowerCase());
            msg(p, "&eFais un &6clic droit &esur un panneau vierge pour le lier à &6" + a.getName() + "&e.");
            return true;
        }
        if (sub.equals("setmin")) {
            if (args.length < 3) { msg(p, "&cUtilisation : &6/sw setmin <nom> <n>"); return true; }
            try {
                int min = Integer.parseInt(args[2]);
                if (min < 2) { msg(p, "&cLe minimum est de &62&c."); return true; }
                a.setMinPlayers(min);
                saveArenas();
                msg(p, "&aMinimum de joueurs : &6" + min + "&a.");
            } catch (NumberFormatException ex) {
                msg(p, "&cNombre invalide.");
            }
            return true;
        }

        help(p);
        return true;
    }

    private Cuboid selection(Player p) {
        Location a = pos1.get(p.getUniqueId());
        Location b = pos2.get(p.getUniqueId());
        if (a == null || b == null) {
            msg(p, "&cFais d'abord &6/sw pos1 &cet &6/sw pos2&c.");
            return null;
        }
        if (!a.getWorld().equals(b.getWorld())) {
            msg(p, "&cLes deux positions doivent être dans le même monde.");
            return null;
        }
        return new Cuboid(a, b);
    }

    // ------------------------------------------------------------
    //  Panneaux
    // ------------------------------------------------------------

    private String signKey(Block b) {
        return b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
    }

    public String takePendingSign(UUID u) { return pendingSigns.remove(u); }

    public boolean isRegisteredSign(Block b) { return signs.containsKey(signKey(b)); }

    public Arena getSignArena(Block b) {
        String k = signs.get(signKey(b));
        return k == null ? null : arenas.get(k);
    }

    public void registerSign(Block b, String arenaKey) {
        signs.put(signKey(b), arenaKey);
        saveArenas();
        Arena a = arenas.get(arenaKey);
        if (a != null) updateSign(signKey(b), a);
    }

    public void unregisterSign(Block b) {
        signs.remove(signKey(b));
        saveArenas();
    }

    public void updateSigns(Arena a) {
        String key = a.getName().toLowerCase();
        for (Map.Entry<String, String> e : new HashMap<String, String>(signs).entrySet()) {
            if (e.getValue().equals(key)) updateSign(e.getKey(), a);
        }
    }

    private void updateAllSigns() {
        for (Map.Entry<String, String> e : new HashMap<String, String>(signs).entrySet()) {
            Arena a = arenas.get(e.getValue());
            if (a != null) updateSign(e.getKey(), a);
        }
    }

    private void updateSign(String key, Arena a) {
        String[] k = key.split(";");
        World w = Bukkit.getWorld(k[0]);
        if (w == null) return;
        Block b = w.getBlockAt(Integer.parseInt(k[1]), Integer.parseInt(k[2]), Integer.parseInt(k[3]));
        BlockState st = b.getState();
        if (!(st instanceof Sign)) return;
        Sign s = (Sign) st;

        String arenaName = a.getName();
        if (arenaName.length() > 13) arenaName = arenaName.substring(0, 13);
        String stateText;
        if (!a.isConfigured()) {
            stateText = "&4Non prete";
        } else {
            switch (a.getState()) {
                case WAITING: stateText = "&aEn attente"; break;
                case STARTING: stateText = "&eDepart..."; break;
                case PLAYING: stateText = "&cEn jeu"; break;
                default: stateText = "&cFin"; break;
            }
        }
        s.setLine(0, color("&6AurexaWars"));
        s.setLine(1, color("&f" + arenaName));
        s.setLine(2, color(stateText));
        s.setLine(3, color("&e" + a.getPlayerCount() + "/" + a.getMaxPlayers()));
        s.update();
    }
}
