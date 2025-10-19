/*
 * Flight
 * Copyright 2022 Kiran Hart
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ca.tweetzy.flight.utils;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Cache system for player heads with rate limiting and persistence.
 *
 * Features:
 * - In-memory cache with 24-hour TTL
 * - Persistent storage across server restarts
 * - Rate limiting to prevent API abuse (max 10 concurrent requests)
 * - Automatic cleanup of expired entries
 *
 * @author Kiran Hart
 */
public final class PlayerHeadCache {

    private static final long CACHE_TTL_MS = TimeUnit.HOURS.toMillis(24); // 24 hours
    private static final int MAX_CONCURRENT_REQUESTS = 10;
    private static final String CACHE_FILE_NAME = "player-head-cache.yml";

    private final Plugin plugin;
    private final File cacheFile;
    private final Map<UUID, CachedHead> cache = new ConcurrentHashMap<>();
    private final Semaphore rateLimiter = new Semaphore(MAX_CONCURRENT_REQUESTS);

    private static PlayerHeadCache instance;

    private PlayerHeadCache(Plugin plugin) {
        this.plugin = plugin;
        this.cacheFile = new File(plugin.getDataFolder(), CACHE_FILE_NAME);
        loadCache();
        startCleanupTask();
    }

    /**
     * Initialize the cache system
     * @param plugin the plugin instance
     */
    public static void init(Plugin plugin) {
        if (instance == null) {
            instance = new PlayerHeadCache(plugin);
        }
    }

    /**
     * Get the cache instance
     * @return the cache instance
     */
    public static PlayerHeadCache getInstance() {
        if (instance == null) {
            throw new IllegalStateException("PlayerHeadCache not initialized! Call init() first.");
        }
        return instance;
    }

    /**
     * Get a player head from cache or load it asynchronously
     *
     * @param playerUUID the player's UUID
     * @param loader the async loader function to fetch the head if not cached
     * @return CompletableFuture with the cached or freshly loaded head
     */
    public CompletableFuture<ItemStack> getOrLoad(UUID playerUUID, Supplier<CompletableFuture<ItemStack>> loader) {
        // Check cache first
        CachedHead cached = cache.get(playerUUID);
        if (cached != null && !cached.isExpired()) {
            return CompletableFuture.completedFuture(cached.getItemStack());
        }

        // Try to acquire rate limit permit
        if (!rateLimiter.tryAcquire()) {
            // Rate limit exceeded, use cached even if expired, or wait
            if (cached != null) {
                plugin.getLogger().log(Level.WARNING, "Rate limit reached, using expired cache for " + playerUUID);
                return CompletableFuture.completedFuture(cached.getItemStack());
            }

            // No cache available, wait for a permit with timeout
            return CompletableFuture.supplyAsync(() -> {
                try {
                    if (!rateLimiter.tryAcquire(5, TimeUnit.SECONDS)) {
                        throw new TimeoutException("Rate limit timeout for " + playerUUID);
                    }
                    return loadAndCache(playerUUID, loader);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to acquire rate limit permit: " + e.getMessage());
                    throw new CompletionException(e);
                } finally {
                    rateLimiter.release();
                }
            }).thenCompose(future -> future);
        }

        // Load asynchronously with rate limiting
        return loadAndCache(playerUUID, loader)
                .whenComplete((result, error) -> rateLimiter.release());
    }

    private CompletableFuture<ItemStack> loadAndCache(UUID playerUUID, Supplier<CompletableFuture<ItemStack>> loader) {
        return loader.get().thenApply(itemStack -> {
            // Cache the result
            cache.put(playerUUID, new CachedHead(itemStack));
            saveCache();
            return itemStack;
        });
    }

    /**
     * Manually cache a player head
     *
     * @param playerUUID the player's UUID
     * @param itemStack the player head ItemStack
     */
    public void put(UUID playerUUID, ItemStack itemStack) {
        cache.put(playerUUID, new CachedHead(itemStack));
        saveCache();
    }

    /**
     * Clear expired entries from cache
     */
    public void cleanupExpired() {
        cache.entrySet().removeIf(entry -> entry.getValue().isExpired());
        saveCache();
    }

    /**
     * Clear all cached entries
     */
    public void clearAll() {
        cache.clear();
        saveCache();
    }

    /**
     * Get cache statistics
     * @return string with cache stats
     */
    public String getStats() {
        long expired = cache.values().stream().filter(CachedHead::isExpired).count();
        return String.format("Cache size: %d (valid: %d, expired: %d)",
                cache.size(), cache.size() - expired, expired);
    }

    /**
     * Load cache from disk
     */
    private void loadCache() {
        if (!cacheFile.exists()) {
            return;
        }

        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(cacheFile);

            for (String key : yaml.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    String serialized = yaml.getString(key + ".data");
                    long timestamp = yaml.getLong(key + ".timestamp");

                    if (serialized != null) {
                        ItemStack itemStack = SerializeUtil.stringToItem(serialized);
                        cache.put(uuid, new CachedHead(itemStack, timestamp));
                    }
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to load cached head for " + key, e);
                }
            }

            plugin.getLogger().info("Loaded " + cache.size() + " player heads from cache");
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load player head cache", e);
        }
    }

    /**
     * Save cache to disk
     */
    private void saveCache() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (!cacheFile.exists()) {
                    cacheFile.getParentFile().mkdirs();
                    cacheFile.createNewFile();
                }

                YamlConfiguration yaml = new YamlConfiguration();

                for (Map.Entry<UUID, CachedHead> entry : cache.entrySet()) {
                    String key = entry.getKey().toString();
                    CachedHead cached = entry.getValue();

                    // Don't save expired entries
                    if (!cached.isExpired()) {
                        yaml.set(key + ".data", SerializeUtil.itemToString(cached.getItemStack()));
                        yaml.set(key + ".timestamp", cached.getTimestamp());
                    }
                }

                yaml.save(cacheFile);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save player head cache", e);
            }
        });
    }

    /**
     * Start periodic cleanup task
     */
    private void startCleanupTask() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            int before = cache.size();
            cleanupExpired();
            int after = cache.size();
            if (before != after) {
                plugin.getLogger().info("Cleaned up " + (before - after) + " expired player head cache entries");
            }
        }, 20 * 60 * 60, 20 * 60 * 60); // Run every hour
    }

    /**
     * Shutdown the cache system and save to disk
     */
    public void shutdown() {
        cleanupExpired();
        saveCache();
    }

    /**
     * Cached head entry with timestamp
     */
    private static class CachedHead {
        private final ItemStack itemStack;
        private final long timestamp;

        public CachedHead(ItemStack itemStack) {
            this(itemStack, System.currentTimeMillis());
        }

        public CachedHead(ItemStack itemStack, long timestamp) {
            this.itemStack = itemStack;
            this.timestamp = timestamp;
        }

        public ItemStack getItemStack() {
            return itemStack.clone();
        }

        public long getTimestamp() {
            return timestamp;
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - timestamp > CACHE_TTL_MS;
        }
    }
}
