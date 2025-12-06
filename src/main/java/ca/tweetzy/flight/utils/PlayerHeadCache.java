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

import ca.tweetzy.flight.comp.enums.CompMaterial;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
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
 * - Permanent in-memory cache (no expiration)
 * - Updates player heads on login (24-hour rate limit)
 * - Extracts textures from online players (no API calls)
 * - Persistent storage across server restarts
 * - Rate limiting to prevent API abuse (max 10 concurrent requests)
 *
 * @author Kiran Hart
 */
public final class PlayerHeadCache {

    private static final long UPDATE_INTERVAL_MS = TimeUnit.HOURS.toMillis(24); // 24 hours between updates
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
            instance.registerListener();
        }
    }

    /**
     * Register the player join listener for automatic cache updates
     */
    private void registerListener() {
        Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
            public void onPlayerJoin(org.bukkit.event.player.PlayerJoinEvent event) {
                // Update player head cache when they join (rate-limited to 24h)
                // This runs async and uses local profile data - no API calls
                Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                    updateFromOnlinePlayer(event.getPlayer());
                }, 20L); // Wait 1 second after join to ensure profile is loaded
            }
        }, plugin);
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

        // If we have a cached head, always use it (cache never expires)
        if (cached != null) {
            // Check if player is online - update in background if needed
            Player onlinePlayer = Bukkit.getPlayer(playerUUID);
            if (onlinePlayer != null && cached.needsUpdate()) {
                // Update in background, don't wait
                updateFromOnlinePlayer(onlinePlayer);
            }

            // Return cached head immediately
            return CompletableFuture.completedFuture(cached.getItemStack());
        }

        // No cache - check if player is online first
        Player onlinePlayer = Bukkit.getPlayer(playerUUID);
        if (onlinePlayer != null) {
            // Player is online - use local data (no API call)
            return extractFromOnlinePlayer(onlinePlayer).thenApply(itemStack -> {
                // Cache the result
                if (itemStack != null) {
                    cache.put(playerUUID, new CachedHead(itemStack));
                    saveCache();
                }
                return itemStack;
            });
        }

        // Player offline and no cache - try to acquire rate limit permit
        if (!rateLimiter.tryAcquire()) {
            // Rate limit exceeded, wait for a permit with timeout
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

        // Load asynchronously with rate limiting (must fetch from Mojang)
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
     * Update player head cache from online player's local profile data.
     * This does NOT make any API calls - uses cached profile data.
     * Updates are rate-limited to once per 24 hours.
     *
     * @param player Online player to extract texture from
     */
    public void updateFromOnlinePlayer(Player player) {
        if (player == null) return;

        CachedHead cached = cache.get(player.getUniqueId());

        // Check if update is needed (rate limiting)
        if (cached != null && !cached.needsUpdate()) {
            return; // Skip - updated recently
        }

        // Extract texture from player's profile (no API call)
        extractFromOnlinePlayer(player).thenAccept(itemStack -> {
            if (itemStack != null) {
                // Update cache with new texture, preserving original timestamp
                CachedHead updated = new CachedHead(
                        itemStack,
                        cached != null ? cached.getTimestamp() : System.currentTimeMillis(),
                        System.currentTimeMillis() // lastUpdated set to now
                );

                cache.put(player.getUniqueId(), updated);
                saveCache();
            }
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.WARNING, "Failed to update head cache for " + player.getName(), ex);
            return null;
        });
    }

    /**
     * Extract texture from online player and return head immediately.
     * This uses the player's locally cached profile data - no API calls.
     *
     * @param player Online player to extract from
     * @return CompletableFuture with the player's head ItemStack
     */
    private CompletableFuture<ItemStack> extractFromOnlinePlayer(Player player) {
        CompletableFuture<ItemStack> future = new CompletableFuture<>();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                ItemStack skull = CompMaterial.PLAYER_HEAD.parseItem();
                if (skull == null) {
                    future.complete(null);
                    return;
                }

                SkullMeta meta = (SkullMeta) skull.getItemMeta();
                if (meta == null) {
                    future.complete(null);
                    return;
                }

                // Use reflection to access PlayerProfile (version-compatible approach)
                try {
                    // Try modern Paper/Spigot API (1.18+)
                    Method getPlayerProfileMethod = player.getClass().getMethod("getPlayerProfile");
                    Object profile = getPlayerProfileMethod.invoke(player);

                    // Set the profile on the skull meta
                    Method setOwnerProfileMethod = meta.getClass().getMethod("setOwnerProfile", profile.getClass().getInterfaces()[0]);
                    setOwnerProfileMethod.invoke(meta, profile);

                    skull.setItemMeta(meta);

                    // Return on main thread
                    Bukkit.getScheduler().runTask(plugin, () -> future.complete(skull));
                } catch (Exception e) {
                    // Fallback to simple setOwningPlayer for older versions or if reflection fails
                    meta.setOwningPlayer(player);
                    skull.setItemMeta(meta);

                    Bukkit.getScheduler().runTask(plugin, () -> future.complete(skull));
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to extract head from online player " + player.getName(), e);
                future.completeExceptionally(e);
            }
        });

        return future;
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
                    long lastUpdated = yaml.getLong(key + ".lastUpdated", timestamp); // Default to timestamp for backward compatibility

                    if (serialized != null) {
                        ItemStack itemStack = SerializeUtil.stringToItem(serialized);
                        cache.put(uuid, new CachedHead(itemStack, timestamp, lastUpdated));
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

                    // Save all entries (cache never expires)
                    yaml.set(key + ".data", SerializeUtil.itemToString(cached.getItemStack()));
                    yaml.set(key + ".timestamp", cached.getTimestamp());
                    yaml.set(key + ".lastUpdated", cached.getLastUpdated());
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
     * Cached head entry with timestamp and last update tracking
     */
    private static class CachedHead {
        private final ItemStack itemStack;
        private final long timestamp;
        private final long lastUpdated;

        public CachedHead(ItemStack itemStack) {
            this(itemStack, System.currentTimeMillis(), System.currentTimeMillis());
        }

        public CachedHead(ItemStack itemStack, long timestamp) {
            this(itemStack, timestamp, timestamp);
        }

        public CachedHead(ItemStack itemStack, long timestamp, long lastUpdated) {
            this.itemStack = itemStack;
            this.timestamp = timestamp;
            this.lastUpdated = lastUpdated;
        }

        public ItemStack getItemStack() {
            return itemStack.clone();
        }

        public long getTimestamp() {
            return timestamp;
        }

        public long getLastUpdated() {
            return lastUpdated;
        }

        /**
         * Cache entries never expire - they are kept permanently
         * @return always false
         */
        public boolean isExpired() {
            return false;
        }

        /**
         * Check if the cached head needs an update (24 hour interval)
         * @return true if more than 24 hours since last update
         */
        public boolean needsUpdate() {
            return System.currentTimeMillis() - lastUpdated > UPDATE_INTERVAL_MS;
        }
    }
}
