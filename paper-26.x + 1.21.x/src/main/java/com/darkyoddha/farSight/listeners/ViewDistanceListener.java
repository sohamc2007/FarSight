package com.darkyoddha.farSight.listeners;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

public class ViewDistanceListener implements Listener {
    private final Plugin plugin;
    private boolean warningLogged = false;

    public ViewDistanceListener(Plugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        int viewDistance;

        if (plugin.getConfig().getBoolean("dynamic-view-distance.enabled", true)) {
            int playerCount = Bukkit.getOnlinePlayers().size(); // Includes the joining player
            double targetChunks = plugin.getConfig().getDouble("dynamic-view-distance.target-total-chunks", 4500.0);
            int minDistance = plugin.getConfig().getInt("dynamic-view-distance.min-view-distance", 4);
            int maxDistance = plugin.getConfig().getInt("dynamic-view-distance.max-view-distance", 16);

            // Calculate optimal view distance for the target chunk limit
            double chunksPerPlayer = targetChunks / Math.max(1, playerCount);
            viewDistance = (int) Math.round((Math.sqrt(chunksPerPlayer) - 1) / 2.0);
            
            // Apply minimum and maximum bounds
            viewDistance = Math.max(minDistance, Math.min(viewDistance, maxDistance));
        } else {
            viewDistance = plugin.getConfig().getInt("custom-view-distance", 16);
        }

        // Overwrite view distance for the player using reflection to support standard Spigot cleanly
        setPlayerViewDistance(event.getPlayer(), viewDistance);
        
        if (event.getPlayer().isOp()) {
            com.darkyoddha.farSight.FarSight fs = (com.darkyoddha.farSight.FarSight) plugin;
            if (fs.getAutoGeneratorTask() != null && !fs.getAutoGeneratorTask().isConfigured()) {
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (event.getPlayer().isOnline()) {
                        event.getPlayer().sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', "&b[FarSight] &eIt looks like you haven't configured the background chunk AutoGenerator yet!"));
                        event.getPlayer().sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', "&bWe recommend loading a good amount of chunks to provide a seamless distant view."));
                        event.getPlayer().sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', "&bUse &b/farsight radius <number> &eto set the generation radius and enable it."));
                    }
                }, 40L);
            }
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
                if (!warningLogged) {
                    plugin.getLogger().warning("Could not set dynamic view distance. This feature requires Paper (or a fork like Purpur). The chunk loading will still work perfectly on Spigot!");
                    warningLogged = true;
                }
            }
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(org.bukkit.event.block.BlockPlaceEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> syncBlock(event.getBlock()));
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(org.bukkit.event.block.BlockBreakEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> syncBlock(event.getBlock()));
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(org.bukkit.event.player.PlayerBucketEmptyEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> syncBlock(event.getBlock()));
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(org.bukkit.event.player.PlayerBucketFillEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> syncBlock(event.getBlock()));
    }
    
    // ======== NEW ADVANCED SYNCING ========

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(org.bukkit.event.block.BlockPistonExtendEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            syncBlock(event.getBlock());
            for (org.bukkit.block.Block b : event.getBlocks()) {
                syncBlock(b);
                syncBlock(b.getRelative(event.getDirection()));
            }
        });
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(org.bukkit.event.block.BlockPistonRetractEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            syncBlock(event.getBlock());
            for (org.bukkit.block.Block b : event.getBlocks()) {
                syncBlock(b);
                syncBlock(b.getRelative(event.getDirection()));
            }
        });
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(org.bukkit.event.entity.EntityExplodeEvent event) {
        // Must delay 1 tick so the blocks are actually air when the packet is sent
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (org.bukkit.block.Block b : event.blockList()) {
                syncBlock(b);
            }
        }, 1L);
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(org.bukkit.event.block.BlockExplodeEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (org.bukkit.block.Block b : event.blockList()) {
                syncBlock(b);
            }
        }, 1L);
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockForm(org.bukkit.event.block.BlockFormEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> syncBlock(event.getBlock()));
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFade(org.bukkit.event.block.BlockFadeEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> syncBlock(event.getBlock()));
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockGrow(org.bukkit.event.block.BlockGrowEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> syncBlock(event.getBlock()));
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeavesDecay(org.bukkit.event.block.LeavesDecayEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> syncBlock(event.getBlock()));
    }
    
    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockSpread(org.bukkit.event.block.BlockSpreadEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> syncBlock(event.getBlock()));
    }
    
    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBurn(org.bukkit.event.block.BlockBurnEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> syncBlock(event.getBlock()));
    }

    private void syncBlock(org.bukkit.block.Block block) {
        com.darkyoddha.farSight.FarSight fs = (com.darkyoddha.farSight.FarSight) plugin;
        com.darkyoddha.farSight.models.ChunkLocation loc = new com.darkyoddha.farSight.models.ChunkLocation(block.getWorld().getName(), block.getChunk().getX(), block.getChunk().getZ());
        
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld().equals(block.getWorld())) {
                boolean sent = false;
                
                if (fs.getChunkLoaderTask() != null) {
                    java.util.Set<com.darkyoddha.farSight.models.ChunkLocation> sentChunks = fs.getChunkLoaderTask().getSentChunksForPlayer(p.getUniqueId());
                    if (sentChunks != null && sentChunks.contains(loc)) {
                        sent = true;
                    }
                }
                
                if (!sent && fs.getGhostChunkSenderTask() != null) {
                    java.util.Set<com.darkyoddha.farSight.models.ChunkLocation> processed = fs.getGhostChunkSenderTask().getProcessedChunks(p.getUniqueId());
                    if (processed != null && processed.contains(loc)) {
                        sent = true;
                    }
                }
                
                if (sent) {
                    p.sendBlockChange(block.getLocation(), block.getBlockData());
                }
            }
        }
    }
}
