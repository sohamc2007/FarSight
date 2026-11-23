package com.darkyoddha.farSight.tasks;

import com.darkyoddha.farSight.FarSight;
import com.darkyoddha.farSight.models.ChunkLocation;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class GhostChunkSenderTask extends BukkitRunnable {

    private final FarSight plugin;

    // Tracks chunks that have been queued or sent to each player to avoid resending
    private final Map<UUID, Set<ChunkLocation>> processedChunks = new ConcurrentHashMap<>();

    // A queue of chunks waiting to be sent to the player
    private final Map<UUID, Queue<ChunkLocation>> chunkQueues = new ConcurrentHashMap<>();

    // Tracks the last cache radius sent to the client to avoid packet spam
    private final Map<UUID, Integer> lastSentRadius = new ConcurrentHashMap<>();

    public Set<ChunkLocation> getProcessedChunks(UUID uuid) {
        return processedChunks.get(uuid);
    }

    public GhostChunkSenderTask(FarSight plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        int maxGhostRadius = plugin.getConfig().getInt("ghost-chunks.max-ghost-chunk-radius", 64);

        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID uuid = p.getUniqueId();

            Set<ChunkLocation> processed = processedChunks.get(uuid);
            Queue<ChunkLocation> queue = chunkQueues.get(uuid);

            if (processed == null) {
                processed = new HashSet<>();
                processedChunks.put(uuid, processed);
                queue = new LinkedList<>();
                chunkQueues.put(uuid, queue);
            }

            // Calculate dynamic cache radius based on client setting, bounded by max cap.
            int clientVD = p.getClientViewDistance(); // Usually 0 right on join, but updates
            int targetRadius = maxGhostRadius;

            // Wait until client actually sends their settings (clientVD > 0)
            if (clientVD > 0) {
                targetRadius = Math.min(clientVD, maxGhostRadius);
                int lastSent = lastSentRadius.getOrDefault(uuid, -1);

                if (targetRadius != lastSent) {
                    plugin.getPacketManager().forceClientChunkCacheRadius(p, targetRadius);
                    lastSentRadius.put(uuid, targetRadius);
                }
            } else {
                continue; // Wait until we know their actual render distance
            }

            // 1. Calculate new chunks in range and queue them
            int px = p.getLocation().getBlockX() >> 4;
            int pz = p.getLocation().getBlockZ() >> 4;
            String worldName = p.getWorld().getName();

            // We only need to check chunks up to targetRadius.
            List<ChunkLocation> newChunks = new ArrayList<>();
            for (int x = -targetRadius; x <= targetRadius; x++) {
                for (int z = -targetRadius; z <= targetRadius; z++) {
                    if (x * x + z * z <= targetRadius * targetRadius) {
                        ChunkLocation loc = new ChunkLocation(worldName, px + x, pz + z);
                        if (!processed.contains(loc)) {
                            newChunks.add(loc);
                        }
                    }
                }
            }

            if (!newChunks.isEmpty()) {
                // Sort by distance to player so they load circularly
                newChunks.sort(Comparator.comparingInt(c -> {
                    int dx = c.getX() - px;
                    int dz = c.getZ() - pz;
                    return dx * dx + dz * dz;
                }));

                for (ChunkLocation loc : newChunks) {
                    processed.add(loc);
                    queue.add(loc);
                }
            }

            // 2. Process a few chunks from the queue
            int sentThisTick = 0;
            World world = p.getWorld();
            
            // DYNAMIC SEND RATE: Calculate how many chunks to send based on the player's current ping.
            // A low ping means the client is processing chunks and network is clear.
            // A high ping means the client is lagging (CPU or Network), so we should slow down to avoid keepalive timeouts.
            int playerPing = p.getPing();
            int dynamicSendRate = plugin.getConfig().getInt("ghost-chunks.max-send-rate-per-tick", 40);
            
            if (playerPing > 800) {
                dynamicSendRate = 0; // Pause completely if they are severely lagging
            } else if (playerPing > 400) {
                dynamicSendRate = 2; // Extremely slow for high latency
            } else if (playerPing > 200) {
                dynamicSendRate = 5; // Moderate pace
            } else if (playerPing > 100) {
                dynamicSendRate = 15; // Fast pace
            }
            // If ping < 100, we use the max dynamicSendRate (default 40)
            
            if (plugin.getPacketManager().isConnectionWritable(p)) {
                while (!queue.isEmpty() && sentThisTick < dynamicSendRate) {
                    ChunkLocation loc = queue.poll();
                    
                    // Ensure player hasn't changed worlds
                    if (!world.getName().equals(loc.getWorldName())) {
                        queue.clear();
                        processed.clear();
                        break;
                    }
                    
                    // If it's already loaded, just send it
                    if (world.isChunkLoaded(loc.getX(), loc.getZ())) {
                        plugin.getPacketManager().sendChunkToPlayer(p, world.getChunkAt(loc.getX(), loc.getZ()));
                    } else {
                        // Load it asynchronously, send it, and let Paper immediately unload it (no ticket added)
                        world.getChunkAtAsync(loc.getX(), loc.getZ()).thenAccept(chunk -> {
                            if (p.isOnline() && p.getWorld().equals(chunk.getWorld())) {
                                plugin.getPacketManager().sendChunkToPlayer(p, chunk);
                            }
                        });
                    }
                    
                    sentThisTick++;
                }
            }

            // 3. Clean up processed chunks that are extremely far away so if the player
            // returns they reload
            // (Only do this occasionally or if memory grows too large. For now, since
            // radius is targetRadius, we can just retain near chunks)
            final int finalRadius = targetRadius;
            processed.removeIf(loc -> {
                if (!loc.getWorldName().equals(p.getWorld().getName()))
                    return true;
                int dx = loc.getX() - (p.getLocation().getBlockX() >> 4);
                int dz = loc.getZ() - (p.getLocation().getBlockZ() >> 4);
                // Remove if it's way outside the view distance (e.g. they walked far away)
                return (dx * dx + dz * dz > (finalRadius + 2) * (finalRadius + 2));
            });
        }

        // Clean up offline players
        Set<UUID> online = Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId)
                .collect(java.util.stream.Collectors.toSet());
        processedChunks.keySet().retainAll(online);
        chunkQueues.keySet().retainAll(online);
        lastSentRadius.keySet().retainAll(online);
    }
}
