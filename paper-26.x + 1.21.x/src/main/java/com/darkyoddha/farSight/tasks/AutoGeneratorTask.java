package com.darkyoddha.farSight.tasks;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public class AutoGeneratorTask extends BukkitRunnable {

    private final Plugin plugin;
    private boolean autoGenerate; // From /farsight autogenerate <true|false>
    private boolean generateForced; // From /farsight generate <start|stop>
    private int radius;
    private int centerX = 0;
    private int centerZ = 0;
    
    private final File progressFile;
    private int currentIndex = 0;
    private long emptyServerTicks = 0;
    private boolean isGenerating = false; // Is a batch currently processing?
    private World defaultWorld;
    private long lastLogTime = 0;
    private int lastLoggedIndex = 0;
    private boolean finishLogged = false;

    public AutoGeneratorTask(Plugin plugin) {
        this.plugin = plugin;
        this.progressFile = new File(plugin.getDataFolder(), "pregen_progress.yml");
        
        loadConfig();
    }
    
    public void loadConfig() {
        // autogenerate is true by default
        this.autoGenerate = plugin.getConfig().getBoolean("auto-generate.enabled", true);
        this.radius = plugin.getConfig().getInt("auto-generate.radius", 64);
        this.centerX = plugin.getConfig().getInt("auto-generate.center-x", 0);
        this.centerZ = plugin.getConfig().getInt("auto-generate.center-z", 0);
        
        if (progressFile.exists()) {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(progressFile);
            this.currentIndex = config.getInt("progress-index", 0);
        }
    }
    
    public void setAutoGenerate(boolean val) {
        this.autoGenerate = val;
        plugin.getConfig().set("auto-generate.enabled", val);
        plugin.saveConfig();
    }
    
    public void setGenerateForced(boolean val) {
        this.generateForced = val;
        if (!val) {
            isGenerating = false;
        }
    }
    
    public void setRadius(int r) {
        this.radius = r;
        plugin.getConfig().set("auto-generate.radius", r);
        plugin.getConfig().set("auto-generate.configured", true); // Mark as configured
        plugin.saveConfig();
    }
    
    public void setCenter(int x, int z) {
        this.centerX = x;
        this.centerZ = z;
        plugin.getConfig().set("auto-generate.center-x", x);
        plugin.getConfig().set("auto-generate.center-z", z);
        plugin.getConfig().set("auto-generate.configured", true); // Mark as configured
        this.currentIndex = 0; // Reset progress when center changes
        saveProgress();
        plugin.saveConfig();
    }
    
    public boolean isConfigured() {
        return plugin.getConfig().getBoolean("auto-generate.configured", false);
    }
    
    public int getRadius() {
        return radius;
    }
    
    public int getProgressIndex() {
        // Clamp: progress files saved by older versions may hold maxIndex + 1
        return Math.min(currentIndex, getMaxIndex());
    }
    
    public int getMaxIndex() {
        return (2 * radius + 1) * (2 * radius + 1);
    }

    @Override
    public void run() {
        if (!isConfigured() && !generateForced) {
            return; // Don't run if it hasn't been configured and not forced
        }
        
        if (!autoGenerate && !generateForced) {
            return; // Stopped
        }

        if (defaultWorld == null) {
            defaultWorld = Bukkit.getWorlds().get(0);
        }

        if (generateForced) {
            // Generate immediately regardless of online players
            generateNextBatch();
        } else if (Bukkit.getOnlinePlayers().isEmpty()) {
            emptyServerTicks += 1; // Task runs every 1 tick
            if (emptyServerTicks >= 1200) { // 60 seconds
                generateNextBatch();
            }
        } else {
            emptyServerTicks = 0;
            isGenerating = false;
        }
    }

    private void generateNextBatch() {
        if (isGenerating) return;

        int maxIndex = getMaxIndex();
        if (currentIndex >= maxIndex) {
            if (!finishLogged) {
                Bukkit.getLogger().info("[FarSight] Task finished. 100.00% complete.");
                finishLogged = true; // Flag instead of bumping currentIndex past maxIndex
            }
            if (generateForced) {
                generateForced = false;
            }
            return; // Finished
        }

        isGenerating = true;
        finishLogged = false; // Work remains (e.g. radius was increased), so log again when done

        // Generate up to 10 chunks per tick
        int chunksToGen = 10;
        CompletableFuture<?>[] futures = new CompletableFuture[chunksToGen];
        
        for (int i = 0; i < chunksToGen; i++) {
            if (currentIndex >= maxIndex) break;
            
            int[] coords = getSpiralCoords(currentIndex);
            int cx = centerX + coords[0];
            int cz = centerZ + coords[1];
            
            currentIndex++;
            
            futures[i] = defaultWorld.getChunkAtAsync(cx, cz).thenAccept(chunk -> {});
        }
        
        CompletableFuture.allOf(java.util.Arrays.stream(futures).filter(java.util.Objects::nonNull).toArray(CompletableFuture[]::new))
            .thenRun(() -> {
                isGenerating = false;
                saveProgress();
                
                long now = System.currentTimeMillis();
                if (lastLogTime == 0) {
                    lastLogTime = now;
                    lastLoggedIndex = currentIndex;
                } else if (now - lastLogTime >= 10000) { // Log every 10 seconds
                    int total = getMaxIndex();
                    int generatedSinceLog = currentIndex - lastLoggedIndex;
                    double timeElapsedSecs = (now - lastLogTime) / 1000.0;
                    double cps = generatedSinceLog / timeElapsedSecs;
                    
                    int remainingChunks = total - currentIndex;
                    int remainingSeconds = cps > 0 ? (int) (remainingChunks / cps) : 0;
                    
                    double percent = total > 0 ? ((double) currentIndex / total) * 100 : 0;
                    
                    String eta;
                    if (remainingSeconds > 3600) {
                        eta = String.format("%dh %dm", remainingSeconds / 3600, (remainingSeconds % 3600) / 60);
                    } else if (remainingSeconds > 60) {
                        eta = String.format("%dm %ds", remainingSeconds / 60, remainingSeconds % 60);
                    } else {
                        eta = String.format("%ds", remainingSeconds);
                    }
                    
                    Bukkit.getLogger().info(String.format("[FarSight] Auto-generating... %.2f%% (%d/%d) | %.1f cps | ETA: %s", 
                        percent, currentIndex, total, cps, eta));
                    
                    lastLogTime = now;
                    lastLoggedIndex = currentIndex;
                }
            });
    }

    private void saveProgress() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("progress-index", currentIndex);
        try {
            config.save(progressFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private int[] getSpiralCoords(int n) {
        if (n == 0) return new int[]{0, 0};
        
        int k = (int) Math.ceil((Math.sqrt(n + 1) - 1) / 2.0);
        int t = 2 * k;
        int m = (t + 1) * (t + 1); 
        
        if (n >= m - t) {
            return new int[]{k - (m - n), -k};
        }
        m -= t;
        if (n >= m - t) {
            return new int[]{-k, -k + (m - n)};
        }
        m -= t;
        if (n >= m - t) {
            return new int[]{-k + (m - n), k};
        }
        return new int[]{k, k - (m - n - t)};
    }
}
