package com.darkyoddha.farSight.network;

import com.darkyoddha.farSight.models.ChunkLocation;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.Bukkit;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import java.lang.reflect.Method;

/**
 * Handles sending manual chunk and entity packets to clients using Paper NMS (Mojang Mappings).
 */
public class PacketManager {

    private final Plugin plugin;
    private final Map<UUID, Set<UUID>> spawnedEntities = new HashMap<>();

    public PacketManager(Plugin plugin) {
        this.plugin = plugin;
        startEntitySync();
    }

    /**
     * Forces the client to render a chunk outside their view distance using NMS.
     */
    public void sendChunkToPlayer(Player player, Chunk chunk) {
        try {
            Method getHandleMethod = player.getClass().getMethod("getHandle");
            ServerPlayer sp = (ServerPlayer) getHandleMethod.invoke(player);
            
            Method worldGetHandle = player.getWorld().getClass().getMethod("getHandle");
            net.minecraft.server.level.ServerLevel serverLevel = (net.minecraft.server.level.ServerLevel) worldGetHandle.invoke(player.getWorld());

            // Fetch the loaded NMS LevelChunk directly from the ServerLevel
            net.minecraft.world.level.chunk.LevelChunk levelChunk = serverLevel.getChunk(chunk.getX(), chunk.getZ());
            
            // Construct and send the raw chunk packet
            ClientboundLevelChunkWithLightPacket packet = 
                new ClientboundLevelChunkWithLightPacket(
                    levelChunk, serverLevel.getLightEngine(), null, null
                );
                
            sp.connection.send(packet);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Tells the client to unload the chunk.
     */
    public void unloadChunkForPlayer(Player player, int x, int z) {
        try {
            Method getHandleMethod = player.getClass().getMethod("getHandle");
            ServerPlayer sp = (ServerPlayer) getHandleMethod.invoke(player);
            
            ClientboundForgetLevelChunkPacket packet = new ClientboundForgetLevelChunkPacket(new ChunkPos(x, z));
                
            sp.connection.send(packet);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Checks if the player's Netty channel buffer is writable.
     * If false, the player is lagging or has a slow connection, and we should pause sending heavy packets.
     */
    public boolean isConnectionWritable(Player player) {
        try {
            Method getHandleMethod = player.getClass().getMethod("getHandle");
            ServerPlayer sp = (ServerPlayer) getHandleMethod.invoke(player);
            io.netty.channel.Channel channel = sp.connection.connection.channel;
            return channel.isWritable();
        } catch (Exception e) {
            return true; // Fallback to true if reflection fails
        }
    }

    /**
     * Forces the server to send entity data to the client.
     */
    public void sendEntityToPlayer(Player viewer, Entity target) {
        try {
            Method getHandleMethod = viewer.getClass().getMethod("getHandle");
            ServerPlayer sp = (ServerPlayer) getHandleMethod.invoke(viewer);
            
            Method getHandleMethod2 = target.getClass().getMethod("getHandle");
            net.minecraft.world.entity.Entity nmsTarget = (net.minecraft.world.entity.Entity) getHandleMethod2.invoke(target);

            Method worldGetHandle = viewer.getWorld().getClass().getMethod("getHandle");
            net.minecraft.server.level.ServerLevel serverLevel = (net.minecraft.server.level.ServerLevel) worldGetHandle.invoke(viewer.getWorld());

            // Get the TrackedEntity from ChunkMap to get the ServerEntity for the spawn packet
            net.minecraft.server.level.ChunkMap chunkMap = serverLevel.getChunkSource().chunkMap;
            Object trackedEntity = chunkMap.entityMap.get(nmsTarget.getId());
            
            net.minecraft.server.level.ServerEntity serverEntity = null;
            if (trackedEntity != null) {
                for (java.lang.reflect.Field field : trackedEntity.getClass().getDeclaredFields()) {
                    if (field.getType().getSimpleName().equals("ServerEntity")) {
                        field.setAccessible(true);
                        try {
                            serverEntity = (net.minecraft.server.level.ServerEntity) field.get(trackedEntity);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                        break;
                    }
                }
            }

            if (serverEntity != null) {
                // 1. Force the client to spawn the entity using NMS!
                net.minecraft.network.protocol.game.ClientboundAddEntityPacket spawnPacket = 
                    new net.minecraft.network.protocol.game.ClientboundAddEntityPacket(nmsTarget, serverEntity);
                sp.connection.send(spawnPacket);

                // 1.5 Send ALL entity metadata (fixes ViaVersion clients on older versions missing skin layers!)
                java.util.List<net.minecraft.network.syncher.SynchedEntityData.DataValue<?>> metadata = getAllDataValues(nmsTarget.getEntityData());
                if (metadata != null && !metadata.isEmpty()) {
                    net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket dataPacket = 
                        new net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket(nmsTarget.getId(), metadata);
                    sp.connection.send(dataPacket);
                }
                
                // 2. Send the current equipment (armor, weapons)
                if (nmsTarget instanceof net.minecraft.world.entity.LivingEntity) {
                    net.minecraft.world.entity.LivingEntity living = (net.minecraft.world.entity.LivingEntity) nmsTarget;
                    java.util.List<com.mojang.datafixers.util.Pair<net.minecraft.world.entity.EquipmentSlot, net.minecraft.world.item.ItemStack>> equipment = new java.util.ArrayList<>();
                    for (net.minecraft.world.entity.EquipmentSlot slot : net.minecraft.world.entity.EquipmentSlot.values()) {
                        equipment.add(com.mojang.datafixers.util.Pair.of(slot, living.getItemBySlot(slot)));
                    }
                    net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket equipPacket = 
                        new net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket(nmsTarget.getId(), equipment);
                    sp.connection.send(equipPacket);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private java.util.List<net.minecraft.network.syncher.SynchedEntityData.DataValue<?>> getAllDataValues(net.minecraft.network.syncher.SynchedEntityData entityData) {
        java.util.List<net.minecraft.network.syncher.SynchedEntityData.DataValue<?>> list = new java.util.ArrayList<>();
        try {
            // Find the itemsById field (it's an array in some versions, or Int2ObjectMap in 1.21+)
            for (java.lang.reflect.Field field : entityData.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(entityData);
                if (value == null) continue;
                
                // If it's an array of DataItem
                if (field.getType().isArray()) {
                    Object[] items = (Object[]) value;
                    if (items.length > 0 && items[0] != null && items[0].getClass().getSimpleName().equals("DataItem")) {
                        for (Object item : items) {
                            if (item != null) {
                                Method valueMethod = item.getClass().getMethod("value");
                                list.add((net.minecraft.network.syncher.SynchedEntityData.DataValue<?>) valueMethod.invoke(item));
                            }
                        }
                        return list;
                    }
                }
                // If it's an Int2ObjectMap (1.21.1)
                else if (value instanceof it.unimi.dsi.fastutil.ints.Int2ObjectMap) {
                    it.unimi.dsi.fastutil.ints.Int2ObjectMap<?> map = (it.unimi.dsi.fastutil.ints.Int2ObjectMap<?>) value;
                    if (!map.isEmpty()) {
                        Object firstItem = map.values().iterator().next();
                        if (firstItem.getClass().getSimpleName().equals("DataItem")) {
                            for (Object item : map.values()) {
                                Method valueMethod = item.getClass().getMethod("value");
                                list.add((net.minecraft.network.syncher.SynchedEntityData.DataValue<?>) valueMethod.invoke(item));
                            }
                            return list;
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        // Fallback to non-default if reflection fails
        return entityData.getNonDefaultValues();
    }

    /**
     * Starts a 1-tick repeating task to manually sync distant players.
     */
    private void startEntitySync() {
        new BukkitRunnable() {
            int tickCount = 0;
            public void run() {
                tickCount++;
                com.darkyoddha.farSight.FarSight fs = (com.darkyoddha.farSight.FarSight) plugin;
                
                for (Player viewer : Bukkit.getOnlinePlayers()) {
                    injectPlayer(viewer);
                    
                    Set<UUID> spawned = spawnedEntities.computeIfAbsent(viewer.getUniqueId(), k -> new HashSet<>());
                    Set<UUID> currentlyTracking = new HashSet<>();
                    
                    // Always track other players globally if they are far away
                    for (Player target : Bukkit.getOnlinePlayers()) {
                        if (viewer.equals(target)) continue;
                        if (!viewer.getWorld().equals(target.getWorld())) continue;
                        
                        double dist = viewer.getLocation().distanceSquared(target.getLocation());
                        if (dist > 4096) { // 64 blocks
                            syncEntity(viewer, target, tickCount, spawned);
                            currentlyTracking.add(target.getUniqueId());
                        }
                    }
                    
                    // Track entities in bridge chunks
                    if (fs.getChunkLoaderTask() != null) {
                        Set<ChunkLocation> bridgeChunks = fs.getChunkLoaderTask().getSentChunksForPlayer(viewer.getUniqueId());
                        if (bridgeChunks != null) {
                            for (ChunkLocation loc : bridgeChunks) {
                                if (viewer.getWorld().getName().equals(loc.getWorldName()) && viewer.getWorld().isChunkLoaded(loc.getX(), loc.getZ())) {
                                    Chunk chunk = viewer.getWorld().getChunkAt(loc.getX(), loc.getZ());
                                    for (Entity target : chunk.getEntities()) {
                                        if (target.equals(viewer) || target instanceof Player) continue; // Players already handled
                                        
                                        double dist = viewer.getLocation().distanceSquared(target.getLocation());
                                        if (dist > 4096) { // 64 blocks
                                            syncEntity(viewer, target, tickCount, spawned);
                                            currentlyTracking.add(target.getUniqueId());
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    // Clean up entities we are no longer tracking (they moved into native view or died/unloaded)
                    spawned.removeIf(uuid -> !currentlyTracking.contains(uuid));
                }
                
                // Clean up disconnected players
                java.util.Set<UUID> online = Bukkit.getOnlinePlayers().stream().map(Player::getUniqueId).collect(java.util.stream.Collectors.toSet());
                spawnedEntities.keySet().retainAll(online);
            }
        }.runTaskTimer(plugin, 0L, 2L); // Run every 2 ticks to save performance
    }
    
    private void syncEntity(Player viewer, Entity target, int tickCount, Set<UUID> spawned) {
        // Resend entity packet every 60 ticks (3 seconds) to counter Bukkit's untracking at ~130 blocks
        if (!spawned.contains(target.getUniqueId()) || tickCount % 60 == 0) {
            sendEntityToPlayer(viewer, target);
            spawned.add(target.getUniqueId());
        }

        try {
            Method getHandleMethod1 = viewer.getClass().getMethod("getHandle");
            ServerPlayer sp1 = (ServerPlayer) getHandleMethod1.invoke(viewer);

            Method getHandleMethod2 = target.getClass().getMethod("getHandle");
            net.minecraft.world.entity.Entity nmsTarget = (net.minecraft.world.entity.Entity) getHandleMethod2.invoke(target);

            Object movePacket = null;
            try {
                // Primary fallback: ClientboundTeleportEntityPacket (Works on all versions including 1.21.1)
                Class<?> teleportClass = Class.forName("net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket");
                try {
                    Class<?> pmrClass = Class.forName("net.minecraft.world.entity.PositionMoveRotation");
                    Method ofMethod = pmrClass.getMethod("of", net.minecraft.world.entity.Entity.class);
                    Object pmr = ofMethod.invoke(null, nmsTarget);
                    Method teleportMethod = teleportClass.getMethod("teleport", int.class, pmrClass, java.util.Set.class, boolean.class);
                    movePacket = teleportMethod.invoke(null, target.getEntityId(), pmr, java.util.Collections.emptySet(), target.isOnGround());
                } catch (Exception pmrEx) {
                    // 1.21.1 constructor without PositionMoveRotation (Fallback)
                    movePacket = teleportClass.getConstructor(net.minecraft.world.entity.Entity.class).newInstance(nmsTarget);
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            
            if (movePacket != null) {
                sp1.connection.send((net.minecraft.network.protocol.Packet<?>) movePacket);
            }
            
            // Sync head rotation
            net.minecraft.network.protocol.game.ClientboundRotateHeadPacket headPacket = 
                new net.minecraft.network.protocol.game.ClientboundRotateHeadPacket(
                    nmsTarget, (byte) (nmsTarget.getYHeadRot() * 256.0F / 360.0F)
                );
            sp1.connection.send(headPacket);

            if (nmsTarget.getEntityData() != null) {
                java.util.List<net.minecraft.network.syncher.SynchedEntityData.DataValue<?>> dataValues = nmsTarget.getEntityData().getNonDefaultValues();
                if (dataValues != null) {
                    net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket dataPacket = 
                        new net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket(
                            nmsTarget.getId(), dataValues
                        );
                    sp1.connection.send(dataPacket);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    public Set<ChunkLocation> updateClientChunks(Player player, Set<ChunkLocation> targetChunks, Set<ChunkLocation> sentChunks) {
        Set<ChunkLocation> successfullySent = new HashSet<>(sentChunks);

        Set<ChunkLocation> toSend = new HashSet<>(targetChunks);
        toSend.removeAll(sentChunks);

        Set<ChunkLocation> toUnload = new HashSet<>(sentChunks);
        toUnload.removeAll(targetChunks);

        for (ChunkLocation loc : toUnload) {
            int vd = Bukkit.getServer().getViewDistance();
            com.darkyoddha.farSight.FarSight fs = (com.darkyoddha.farSight.FarSight) plugin;
            if (fs.getDynamicViewDistanceTask() != null && fs.getDynamicViewDistanceTask().getCurrentViewDistance() > 0) {
                vd = fs.getDynamicViewDistanceTask().getCurrentViewDistance();
            }
            int px = player.getLocation().getBlockX() >> 4;
            int pz = player.getLocation().getBlockZ() >> 4;
            if (Math.abs(px - loc.getX()) <= vd && Math.abs(pz - loc.getZ()) <= vd) {
                successfullySent.remove(loc);
                continue;
            }
            unloadChunkForPlayer(player, loc.getX(), loc.getZ());
            successfullySent.remove(loc);
        }

        if (!toSend.isEmpty()) {
            forceClientChunkCacheRadius(player, 64);
        }

        int sentThisTick = 0;
        for (ChunkLocation loc : toSend) {
            if (!isConnectionWritable(player) || sentThisTick >= 20) {
                break; // Stop sending chunks this tick if buffer is full or cap reached
            }
            
            World world = Bukkit.getWorld(loc.getWorldName());
            if (world != null) {
                if (!world.isChunkLoaded(loc.getX(), loc.getZ())) {
                    world.getChunkAt(loc.getX(), loc.getZ()); 
                }
                if (world.isChunkLoaded(loc.getX(), loc.getZ())) {
                    sendChunkToPlayer(player, world.getChunkAt(loc.getX(), loc.getZ()));
                    successfullySent.add(loc);
                    sentThisTick++;
                }
            }
        }
        return successfullySent;
    }

    /**
     * Forces the client to allocate a larger chunk cache radius.
     * This prevents the client from dropping chunks that are far away.
     */
    public void forceClientChunkCacheRadius(Player player, int radius) {
        try {
            Method getHandleMethod = player.getClass().getMethod("getHandle");
            ServerPlayer sp = (ServerPlayer) getHandleMethod.invoke(player);
            
            net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket packet = 
                new net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket(radius);
            sp.connection.send(packet);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Injects a Netty channel handler to intercept and block native server chunk unloads.
     */
    private void injectPlayer(Player player) {
        try {
            Method getHandleMethod = player.getClass().getMethod("getHandle");
            ServerPlayer sp = (ServerPlayer) getHandleMethod.invoke(player);
            io.netty.channel.Channel channel = sp.connection.connection.channel;

            String pipelineName = "FarSight_Injector";
            if (channel.pipeline().get(pipelineName) == null) {
                channel.pipeline().addBefore("packet_handler", pipelineName, new io.netty.channel.ChannelDuplexHandler() {
                    @Override
                    public void write(io.netty.channel.ChannelHandlerContext ctx, Object msg, io.netty.channel.ChannelPromise promise) throws Exception {
                        if (msg instanceof net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket) {
                            net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket packet = 
                                (net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket) msg;
                            // ChunkPos.x/z became private in 26.1 (record); getMinBlockX/Z works on 1.21.x and 26.x
                            ChunkLocation loc = new ChunkLocation(player.getWorld().getName(), packet.pos().getMinBlockX() >> 4, packet.pos().getMinBlockZ() >> 4);
                            
                            com.darkyoddha.farSight.FarSight fs = (com.darkyoddha.farSight.FarSight) plugin;
                            // 1. Check if it's a bridge chunk
                            if (fs.getChunkLoaderTask() != null) {
                                Set<ChunkLocation> sent = fs.getChunkLoaderTask().getSentChunksForPlayer(player.getUniqueId());
                                if (sent.contains(loc)) {
                                    // Block the native unload packet!
                                    return;
                                }
                            }
                            // 2. Check if it's a ghost chunk
                            int maxGhostRadius = plugin.getConfig().getInt("ghost-chunks.max-ghost-chunk-radius", 64);
                            int clientVd = player.getClientViewDistance();
                            int targetRadius = clientVd > 0 ? Math.min(clientVd, maxGhostRadius) : maxGhostRadius;
                            
                            int px = player.getLocation().getBlockX() >> 4;
                            int pz = player.getLocation().getBlockZ() >> 4;
                            
                            if (Math.abs(px - loc.getX()) <= targetRadius && Math.abs(pz - loc.getZ()) <= targetRadius) {
                                // Block the native unload packet for ghost chunks inside the client's grid!
                                return;
                            }
                        }
                        if (msg instanceof net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket) {
                            // Ensure Paper doesn't shrink the client's grid when dynamic-view-distance kicks in
                            int maxGhostRadius = plugin.getConfig().getInt("ghost-chunks.max-ghost-chunk-radius", 64);
                            int clientVd = player.getClientViewDistance();
                            int targetRadius;
                            
                            if (clientVd > 0) {
                                targetRadius = Math.min(clientVd, maxGhostRadius);
                            } else {
                                // Fallback to the server's requested radius on join so we don't freeze the client allocating 16,000 chunks!
                                targetRadius = org.bukkit.Bukkit.getServer().getViewDistance();
                            }
                            
                            msg = new net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket(targetRadius);
                        }
                        
                        super.write(ctx, msg, promise);
                    }
                });
            }
        } catch (Exception e) {
            // Ignore if player disconnects during injection or reflection fails
        }
    }
}
