package fr.aurexalium.aurexawars;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;

public class Cuboid {

    private final String world;
    private final int minX, minY, minZ, maxX, maxY, maxZ;

    public Cuboid(Location a, Location b) {
        this(a.getWorld().getName(),
                a.getBlockX(), a.getBlockY(), a.getBlockZ(),
                b.getBlockX(), b.getBlockY(), b.getBlockZ());
    }

    public Cuboid(String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        this.world = world;
        this.minX = Math.min(x1, x2);
        this.minY = Math.min(y1, y2);
        this.minZ = Math.min(z1, z2);
        this.maxX = Math.max(x1, x2);
        this.maxY = Math.max(y1, y2);
        this.maxZ = Math.max(z1, z2);
    }

    public boolean contains(Location l) {
        if (l == null || l.getWorld() == null || !l.getWorld().getName().equals(world)) return false;
        int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public String getWorldName() { return world; }
    public int getMinX() { return minX; }
    public int getMaxX() { return maxX; }
    public int getMinY() { return minY; }
    public int getMinZ() { return minZ; }
    public int getMaxZ() { return maxZ; }

    public void save(ConfigurationSection s) {
        s.set("world", world);
        s.set("minX", minX);
        s.set("minY", minY);
        s.set("minZ", minZ);
        s.set("maxX", maxX);
        s.set("maxY", maxY);
        s.set("maxZ", maxZ);
    }

    public static Cuboid load(ConfigurationSection s) {
        if (s == null || s.getString("world") == null) return null;
        return new Cuboid(s.getString("world"),
                s.getInt("minX"), s.getInt("minY"), s.getInt("minZ"),
                s.getInt("maxX"), s.getInt("maxY"), s.getInt("maxZ"));
    }
}
