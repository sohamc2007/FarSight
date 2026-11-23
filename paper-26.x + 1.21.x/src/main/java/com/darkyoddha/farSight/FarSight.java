package com.darkyoddha.farSight;

import com.darkyoddha.farSight.listeners.ViewDistanceListener;
import com.darkyoddha.farSight.tasks.ChunkLoaderTask;
import com.darkyoddha.farSight.tasks.DynamicViewDistanceTask;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class FarSight extends JavaPlugin {

    private ChunkLoaderTask chunkLoaderTask;
    private DynamicViewDistanceTask dynamicViewDistanceTask;
    private com.darkyoddha.farSight.network.PacketManager packetManager;
    private com.darkyoddha.farSight.tasks.AutoGeneratorTask autoGeneratorTask;

    public com.darkyoddha.farSight.tasks.AutoGeneratorTask getAutoGeneratorTask() {
        return autoGeneratorTask;
    }

    public com.darkyoddha.farSight.network.PacketManager getPacketManager() {
        return packetManager;
    }
    
    private com.darkyoddha.farSight.tasks.GhostChunkSenderTask ghostChunkSenderTask;
    
    public com.darkyoddha.farSight.tasks.GhostChunkSenderTask getGhostChunkSenderTask() {
        return ghostChunkSenderTask;
    }

    public ChunkLoaderTask getChunkLoaderTask() {
        return chunkLoaderTask;
    }

    public DynamicViewDistanceTask getDynamicViewDistanceTask() {
        return dynamicViewDistanceTask;
    }

    @Override
    public void onEnable() {
        // Save default config.yml if it doesn't exist
        saveDefaultConfig();

        // Register listeners
        Bukkit.getPluginManager().registerEvents(new ViewDistanceListener(this), this);
        
        try {
            final java.lang.reflect.Field bukkitCommandMap = Bukkit.getServer().getClass().getDeclaredField("commandMap");
            bukkitCommandMap.setAccessible(true);
            org.bukkit.command.CommandMap commandMap = (org.bukkit.command.CommandMap) bukkitCommandMap.get(Bukkit.getServer());
            
            org.bukkit.command.Command farsightCmd = new org.bukkit.command.Command("farsight") {
                private final com.darkyoddha.farSight.commands.FarSightCommand executor = new com.darkyoddha.farSight.commands.FarSightCommand(FarSight.this);

                @Override
                public boolean execute(org.bukkit.command.CommandSender sender, String commandLabel, String[] args) {
                    return executor.onCommand(sender, this, commandLabel, args);
                }

                @Override
                public java.util.List<String> tabComplete(org.bukkit.command.CommandSender sender, String alias, String[] args) throws IllegalArgumentException {
                    return executor.onTabComplete(sender, this, alias, args);
                }
            };
            commandMap.register(getName(), farsightCmd);
        } catch (Exception e) {
            e.printStackTrace();
        }

        packetManager = new com.darkyoddha.farSight.network.PacketManager(this);

        // Register and start chunk loader task
        long interval = getConfig().getLong("task-interval-ticks", 40L);
        chunkLoaderTask = new ChunkLoaderTask(this, packetManager);
        chunkLoaderTask.runTaskTimer(this, 0L, interval); 
        
        // Register and start dynamic view distance task
        // We run it slightly less frequently (e.g., every 5 seconds = 100 ticks) as it shouldn't be overly aggressive
        dynamicViewDistanceTask = new DynamicViewDistanceTask(this);
        dynamicViewDistanceTask.runTaskTimer(this, 0L, 100L);
        
        // Start Auto Generator Task
        autoGeneratorTask = new com.darkyoddha.farSight.tasks.AutoGeneratorTask(this);
        autoGeneratorTask.runTaskTimer(this, 0L, 1L);
        
        // Start Ghost Chunk Sender Task
        ghostChunkSenderTask = new com.darkyoddha.farSight.tasks.GhostChunkSenderTask(this);
        ghostChunkSenderTask.runTaskTimer(this, 0L, 1L);
        
        getLogger().info("FarSight plugin enabled - overriding view distance and managing chunks!");
    }

    @Override
    public void onDisable() {
        if (chunkLoaderTask != null) {
            chunkLoaderTask.cleanup();
            chunkLoaderTask.cancel();
        }
        if (dynamicViewDistanceTask != null) {
            dynamicViewDistanceTask.cancel();
        }
        getLogger().info("FarSight plugin disabled.");
    }
}
