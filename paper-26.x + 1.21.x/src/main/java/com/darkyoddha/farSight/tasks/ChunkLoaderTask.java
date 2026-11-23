package com.darkyoddha.farSight.tasks;

import com.darkyoddha.farSight.models.ChunkLocation;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import com.darkyoddha.farSight.network.PacketManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class ChunkLoaderTask extends BukkitRunnable {

    private final Plugin plugin;
    private final PacketManager packetManager;
    private final Set<ChunkLocation> loadedChunks = new HashSet<>();
    private final Map<ChunkLocation, Long> pendingUnloads = new HashMap<>();
    private final Map<PlayerPair, PairCacheData> pairCache = new HashMap<>();
    private final Map<UUID, Set<ChunkLocation>> sentChunksPerPlayer = new HashMap<>();

    public ChunkLoaderTask(Plugin plugin, PacketManager packetManager) {
        this.plugin = plugin;
        this.packetManager = packetManager;
    }

    @Override
    public void run() {
        updateLoadedChunks();
    }

    private void updateLoadedChunks() {
        int R = plugin.getConfig().getInt("dynamic-view-distance.max-view-distance", 16);
        int r = plugin.getConfig().getInt("dynamic-view-distance.min-view-distance", 4);
        int N = Bukkit.getOnlinePlayers().size();
        if (N == 0) return;
        
        // Calculate max bridge length dynamically based on the chunk budget
        double maxChunksPerPair = (Math.pow(R, 2) - Math.pow(r, 2)) * N * 3.14 / 5.0;
        double maxDistSquared = Math.pow(maxChunksPerPair * 16, 2);
        int cacheRadius = plugin.getConfig().getInt("update-cache-radius", 2);
        
        Set<ChunkLocation> chunksToLoad = new HashSet<>();
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        Set<PlayerPair> activePairs = new HashSet<>();

        Map<UUID, Set<ChunkLocation>> targetChunksPerPlayer = new HashMap<>();
        for (Player p : players) {
            targetChunksPerPlayer.put(p.getUniqueId(), new HashSet<>());
        }

        // Find pairs of players and calculate chunks between them
        for (int i = 0; i < players.size(); i++) {
            for (int j = i + 1; j < players.size(); j++) {
                Player p1 = players.get(i);
                Player p2 = players.get(j);
                if (p1.getTicksLived() < 100 || p2.getTicksLived() < 100) continue;
                if (!p1.getWorld().equals(p2.getWorld())) continue;
                
                if (p1.getLocation().distanceSquared(p2.getLocation()) > maxDistSquared) continue;

                PlayerPair pair = new PlayerPair(p1.getUniqueId(), p2.getUniqueId());
                activePairs.add(pair);

                ChunkLocation c1 = new ChunkLocation(p1.getWorld().getName(), p1.getLocation().getBlockX() >> 4, p1.getLocation().getBlockZ() >> 4);
                ChunkLocation c2 = new ChunkLocation(p2.getWorld().getName(), p2.getLocation().getBlockX() >> 4, p2.getLocation().getBlockZ() >> 4);

                PairCacheData cachedData = pairCache.get(pair);
                boolean useCache = false;

                // Check if players have moved outside the cache radius since the last calculation
                if (cachedData != null) {
                    int dist1 = Math.max(Math.abs(c1.getX() - cachedData.p1Loc.getX()), Math.abs(c1.getZ() - cachedData.p1Loc.getZ()));
                    int dist2 = Math.max(Math.abs(c2.getX() - cachedData.p2Loc.getX()), Math.abs(c2.getZ() - cachedData.p2Loc.getZ()));
                    
                    if (dist1 <= cacheRadius && dist2 <= cacheRadius) {
                        useCache = true;
                    }
                }

                Set<ChunkLocation> lineSet = new HashSet<>();

                if (useCache) {
                    // Players haven't moved enough to justify recalculating the line, use old chunks
                    lineSet.addAll(cachedData.chunks);
                } else {
                    // Recalculate the line
                    List<ChunkLocation> lineChunks = getChunksBetween(p1, p2);
                    
                    if (lineChunks.size() <= maxChunksPerPair * 25) {
                        lineSet.addAll(lineChunks);
                        // Save to cache
                        pairCache.put(pair, new PairCacheData(c1, c2, lineSet));
                    } else {
                        // Exceeded cap, ensure we don't keep old cached chunks loaded
                        pairCache.remove(pair);
                    }
                }

                chunksToLoad.addAll(lineSet);
                targetChunksPerPlayer.get(p1.getUniqueId()).addAll(lineSet);
                targetChunksPerPlayer.get(p2.getUniqueId()).addAll(lineSet);
            }
        }

        // Clean up inactive pairs from cache (e.g. player left or went too far)
        pairCache.keySet().retainAll(activePairs);

        long currentTime = System.currentTimeMillis();
        long unloadDelayMillis = plugin.getConfig().getInt("chunk-unload-delay-seconds", 120) * 1000L;

        // Identify chunks that are no longer actively needed by any player pair
        Set<ChunkLocation> toRemove = new HashSet<>(loadedChunks);
        toRemove.removeAll(chunksToLoad);
        
        for (ChunkLocation loc : toRemove) {
            // Move from active loadedChunks to pendingUnloads
            if (!pendingUnloads.containsKey(loc)) {
                pendingUnloads.put(loc, currentTime + unloadDelayMillis);
            }
            loadedChunks.remove(loc); 
        }

        // Identify new chunks that are now needed
        Set<ChunkLocation> toAdd = new HashSet<>(chunksToLoad);
        toAdd.removeAll(loadedChunks);
        
        for (ChunkLocation loc : toAdd) {
            if (pendingUnloads.containsKey(loc)) {
                // The chunk was scheduled for unload, but is needed again! Just cancel the unload.
                pendingUnloads.remove(loc);
            } else {
                // Truly a new chunk, add the plugin ticket
                World world = Bukkit.getWorld(loc.getWorldName());
                if (world != null) {
                    world.addPluginChunkTicket(loc.getX(), loc.getZ(), plugin);
                }
            }
            loadedChunks.add(loc);
        }

        // Process actual unloads for chunks whose delay has expired
        List<ChunkLocation> expiredChunks = new ArrayList<>();
        for (Map.Entry<ChunkLocation, Long> entry : pendingUnloads.entrySet()) {
            if (currentTime >= entry.getValue()) {
                expiredChunks.add(entry.getKey());
            }
        }
        
        for (ChunkLocation loc : expiredChunks) {
            World world = Bukkit.getWorld(loc.getWorldName());
            if (world != null) {
                world.removePluginChunkTicket(loc.getX(), loc.getZ(), plugin);
            }
            pendingUnloads.remove(loc);
        }

        // Sync with clients
        for (Player p : players) {
            Set<ChunkLocation> targetChunks = targetChunksPerPlayer.get(p.getUniqueId());
            Set<ChunkLocation> sentChunks = sentChunksPerPlayer.getOrDefault(p.getUniqueId(), new HashSet<>());
            
            if (!targetChunks.equals(sentChunks)) {
                Set<ChunkLocation> actuallySent = packetManager.updateClientChunks(p, targetChunks, sentChunks);
                sentChunksPerPlayer.put(p.getUniqueId(), actuallySent);
            }
        }

        // Cleanup disconnected players from sentChunksPerPlayer
        Set<UUID> onlineUUIDs = players.stream().map(Player::getUniqueId).collect(Collectors.toSet());
        sentChunksPerPlayer.keySet().retainAll(onlineUUIDs);
    }

    private List<ChunkLocation> getChunksBetween(Player p1, Player p2) {
        List<ChunkLocation> chunks = new ArrayList<>();
        int x1 = p1.getLocation().getBlockX() >> 4;
        int z1 = p1.getLocation().getBlockZ() >> 4;
        int x2 = p2.getLocation().getBlockX() >> 4;
        int z2 = p2.getLocation().getBlockZ() >> 4;

        String worldName = p1.getWorld().getName();

        int dx = Math.abs(x2 - x1);
        int dz = Math.abs(z2 - z1);
        int sx = x1 < x2 ? 1 : -1;
        int sz = z1 < z2 ? 1 : -1;
        int err = dx - dz;

        Set<ChunkLocation> thickLine = new HashSet<>();

        while (true) {
            // A Minecraft client requires the surrounding chunks to be loaded before it will construct the visual mesh!
            // Breadth-First-Search rendering can't travel diagonally. A 5x5 grid ensures a cardinal path is always meshed.
            for (int ox = -2; ox <= 2; ox++) {
                for (int oz = -2; oz <= 2; oz++) {
                    thickLine.add(new ChunkLocation(worldName, x1 + ox, z1 + oz));
                }
            }
            
            if (x1 == x2 && z1 == z2) break;
            int e2 = 2 * err;
            if (e2 > -dz) {
                err -= dz;
                x1 += sx;
            }
            if (e2 < dx) {
                err += dx;
                z1 += sz;
            }
        }
        chunks.addAll(thickLine);
        return chunks;
    }

    public void cleanup() {
        for (ChunkLocation loc : loadedChunks) {
            World world = Bukkit.getWorld(loc.getWorldName());
            if (world != null) {
                world.removePluginChunkTicket(loc.getX(), loc.getZ(), plugin);
            }
        }
        for (ChunkLocation loc : pendingUnloads.keySet()) {
            World world = Bukkit.getWorld(loc.getWorldName());
            if (world != null) {
                world.removePluginChunkTicket(loc.getX(), loc.getZ(), plugin);
            }
        }
        loadedChunks.clear();
        pendingUnloads.clear();
        pairCache.clear();
    }

    // Helper class to uniquely identify a pair of players regardless of order
    private static class PlayerPair {
        public final UUID p1;
        public final UUID p2;

        public PlayerPair(UUID p1, UUID p2) {
            if (p1.compareTo(p2) > 0) {
                this.p1 = p1;
                this.p2 = p2;
            } else {
                this.p1 = p2;
                this.p2 = p1;
            }
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PlayerPair that = (PlayerPair) o;
            return p1.equals(that.p1) && p2.equals(that.p2);
        }

        @Override
        public int hashCode() {
            return Objects.hash(p1, p2);
        }
    }

    // Helper class to store the last known calculation for a player pair
    private static class PairCacheData {
        public final ChunkLocation p1Loc;
        public final ChunkLocation p2Loc;
        public final Set<ChunkLocation> chunks;

        public PairCacheData(ChunkLocation p1Loc, ChunkLocation p2Loc, Set<ChunkLocation> chunks) {
            this.p1Loc = p1Loc;
            this.p2Loc = p2Loc;
            this.chunks = chunks;
        }
    }

    public Set<ChunkLocation> getSentChunksForPlayer(UUID playerId) {
        return sentChunksPerPlayer.getOrDefault(playerId, new HashSet<>());
    }
}
