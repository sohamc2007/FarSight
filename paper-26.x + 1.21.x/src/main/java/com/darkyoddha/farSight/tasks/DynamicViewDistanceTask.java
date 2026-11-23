package com.darkyoddha.farSight.tasks;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.lang.reflect.Method;

public class DynamicViewDistanceTask extends BukkitRunnable {

    private final Plugin plugin;
    private int currentViewDistance = -1;

    public DynamicViewDistanceTask(Plugin plugin) {
        this.plugin = plugin;
    }

    public int getCurrentViewDistance() {
        return currentViewDistance;
    }

    @Override
    public void run() {
        if (!plugin.getConfig().getBoolean("dynamic-view-distance.enabled", true)) {
            return;
        }

        int playerCount = Bukkit.getOnlinePlayers().size();
        if (playerCount == 0) return;

        int minDistance = plugin.getConfig().getInt("dynamic-view-distance.min-view-distance", 4);
        int maxDistance = plugin.getConfig().getInt("dynamic-view-distance.max-view-distance", 16);

        // New Algorithm: 3.14 * R^2 - 3.14 * V^2 = (5.0 * x) / N
        // This keeps the total loaded chunks constant by reducing the view distance area to exactly offset the chunks loaded by the bridges!
        // where R = maxDistance, V = calculatedDistance, x = total distance in chunks of all active bridges, N = playerCount
        // Solving for V: V = sqrt(R^2 - (5.0 * x) / (N * 3.14))
        
        double x = 0.0;
        double x_max = (Math.pow(maxDistance, 2) - Math.pow(minDistance, 2)) * playerCount * 3.14 / 5.0;
        double maxDistSquared = Math.pow(x_max * 16, 2);

        java.util.List<Player> players = new java.util.ArrayList<>(Bukkit.getOnlinePlayers());
        for (int i = 0; i < players.size(); i++) {
            for (int j = i + 1; j < players.size(); j++) {
                Player p1 = players.get(i);
                Player p2 = players.get(j);
                if (p1.getTicksLived() < 100 || p2.getTicksLived() < 100) continue;
                if (p1.getWorld().equals(p2.getWorld())) {
                    if (p1.getLocation().distanceSquared(p2.getLocation()) <= maxDistSquared) {
                        double dx = (p1.getLocation().getBlockX() >> 4) - (p2.getLocation().getBlockX() >> 4);
                        double dz = (p1.getLocation().getBlockZ() >> 4) - (p2.getLocation().getBlockZ() >> 4);
                        x += Math.sqrt(dx * dx + dz * dz);
                    }
                }
            }
        }

        int calculatedDistance;
        double insideSqrt = (maxDistance * maxDistance) - ((5.0 * x) / (playerCount * 3.14));
        
        if (insideSqrt <= 0) {
            calculatedDistance = minDistance;
        } else {
            calculatedDistance = (int) Math.round(Math.sqrt(insideSqrt));
        }

        // Bound the calculated distance
        calculatedDistance = Math.max(minDistance, Math.min(calculatedDistance, maxDistance));

        // Update only if the optimal view distance has changed
        if (calculatedDistance != currentViewDistance) {
            currentViewDistance = calculatedDistance;
            
            for (Player player : Bukkit.getOnlinePlayers()) {
                setPlayerViewDistance(player, currentViewDistance);
            }
            
            plugin.getLogger().info("Dynamic view distance adjusted to " + currentViewDistance + " chunks for " + playerCount + " players.");
        }
    }

    private void setPlayerViewDistance(Player player, int distance) {
        try {
            Method method = player.getClass().getMethod("setViewDistance", int.class);
            method.invoke(player, distance);
        } catch (Exception e) {
            try {
                Method method = player.getClass().getMethod("setSendViewDistance", int.class);
                method.invoke(player, distance);
            } catch (Exception ex) {
                // Ignore silently here, the listener already warned the console once on join.
            }
        }
    }
}
