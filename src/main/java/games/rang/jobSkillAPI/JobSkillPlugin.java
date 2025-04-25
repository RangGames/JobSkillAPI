package games.rang.jobSkillAPI;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import games.rang.jobSkillAPI.api.JobSkillAPI;
import games.rang.jobSkillAPI.config.ConfigManager;
import games.rang.jobSkillAPI.data.StaticDataManager;
import games.rang.jobSkillAPI.event.EventListener;
import games.rang.jobSkillAPI.log.TransactionLogger;
import games.rang.jobSkillAPI.redis.RedisManager;
import games.rang.jobSkillAPI.storage.Storage;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Main plugin class for JobSkillAPI.
 * Handles initialization of core components (Config, Logger, Storage, API, Redis, Listeners),
 * loading static data, managing player data for online players, and shutdown procedures.
 */
public class JobSkillPlugin extends JavaPlugin {
    private Storage storage;
    private TransactionLogger transactionLogger;
    private ConfigManager configManager;
    private StaticDataManager staticDataManager;
    private JobSkillAPI api;
    private RedisManager redisManager; // Optional Redis Manager

    /**
     * Called when the plugin is enabled. Initializes components in order:
     * Logger -> Config -> Storage -> StaticData -> API -> Redis (optional) -> Listeners.
     * Initiates asynchronous static data loading. If successful, loads data for online players
     * and starts the auto-save task. Disables plugin on critical initialization failures.
     */
    @Override
    public void onEnable() {
        // 1. Initialize Logger & Config
        this.transactionLogger = new TransactionLogger(this);
        this.configManager = new ConfigManager(this);
        logPluginInfo("Enabling"); // Log basic info early

        // 2. Initialize Storage (includes DatabaseHandler & StaticDataManager)
        try {
            this.storage = new Storage(this, configManager, transactionLogger);
            this.staticDataManager = storage.getStaticDataManager(); // Get instance from Storage
        } catch (RuntimeException e) {
            transactionLogger.error("!!! CRITICAL: Failed to initialize Storage/Database. Disabling plugin. !!!", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 3. Initialize API
        JobSkillAPI.init(storage, this); // Pass plugin instance
        this.api = JobSkillAPI.getInstance();

        // 4. Initialize RedisManager (if enabled)
        if (configManager.isRedisEnabled()) {
            this.redisManager = new RedisManager(this, configManager, transactionLogger, api);
            if (redisManager.initialize()) {
                redisManager.startSubscriber(); // Start listening for messages
            } else {
                transactionLogger.error("Failed to initialize Redis connection. Redis features (like global season reset) will be unavailable.");
                // Continue without Redis features? Or disable plugin? For now, just warn.
                this.redisManager = null; // Ensure redisManager is null if init failed
            }
        } else {
            transactionLogger.info("Redis is disabled in config.yml. Server-wide coordination via Redis is disabled.");
            this.redisManager = null;
        }

        // 5. Register Event Listeners
        getServer().getPluginManager().registerEvents(new EventListener(this, storage, transactionLogger, configManager), this);

        // 6. Load Static Data (asynchronously)
        transactionLogger.info("Initiating static data load...");
        staticDataManager.loadStaticData()
                .thenRunAsync(() -> { // Use thenRunAsync for actions after completion
                    // Ensure subsequent Bukkit tasks run on the main thread
                    Bukkit.getScheduler().runTask(this, () -> {
                        boolean staticLoadSuccess = staticDataManager.isStaticDataLoaded();
                        if (staticLoadSuccess) {
                            transactionLogger.info("Static data loaded successfully.");
                            // Load data for currently online players
                            loadDataForOnlinePlayers();
                            // Start auto-save task
                            storage.startAutoSave();
                            transactionLogger.info("{} enabled successfully.", getName());
                        } else {
                            // Critical failure - essential data missing
                            transactionLogger.error("!!! CRITICAL: Failed to load essential static game data. Disabling plugin !!!");
                            getServer().getPluginManager().disablePlugin(this);
                        }
                    });
                })
                .exceptionally(e -> {
                    // Handle exceptions during the async loading process
                    transactionLogger.error("!!! CRITICAL: Exception during static data loading. Disabling plugin !!!", e);
                    // Ensure disable happens on the main thread
                    Bukkit.getScheduler().runTask(this, () -> getServer().getPluginManager().disablePlugin(this));
                    return null; // Required for exceptionally block
                });
    }

    /**
     * Called when the plugin is disabled. Closes Redis connection (if active),
     * attempts to save all pending player data synchronously with a timeout,
     * then shuts down the storage scheduler and database connections.
     */
    @Override
    public void onDisable() {
        logPluginInfo("Disabling");

        // 1. Close Redis Manager (if enabled and initialized)
        if (redisManager != null) {
            redisManager.close();
        }

        // 2. Save all player data
        if (storage != null) {
            transactionLogger.info("Attempting to save all player data before shutdown...");
            CompletableFuture<Void> saveFuture = storage.saveAllPlayerData();
            try {
                // Wait for saves to complete (with timeout)
                saveFuture.get(10, TimeUnit.SECONDS);
                transactionLogger.info("Player data saving process completed or timed out.");
            } catch (InterruptedException e) {
                transactionLogger.error("Interrupted while waiting for player data saving.", e);
                Thread.currentThread().interrupt(); // Restore interrupt status
            } catch (ExecutionException e) {
                transactionLogger.error("Exception occurred during final player data saving.", e.getCause());
            } catch (TimeoutException e) {
                transactionLogger.error("Timed out waiting for player data saving! Some data might not be saved!");
            }

            // 3. Shutdown Storage (closes DB pool, stops scheduler)
            storage.shutdown();
        } else {
            transactionLogger.warn("Storage was not initialized, skipping data saving and shutdown procedures.");
        }

        transactionLogger.info("{} disabled.", getName());
    }

    /**
     * Loads data asynchronously for players who are already online when the plugin enables.
     * Attempts data recovery after loading. Logs successes and failures.
     */
    private void loadDataForOnlinePlayers() {
        Collection<? extends Player> onlinePlayers = Bukkit.getOnlinePlayers();
        if (onlinePlayers.isEmpty()) {
            transactionLogger.info("No players online, skipping initial data load for online players.");
            return;
        }
        transactionLogger.info("Loading data for {} online players...", onlinePlayers.size());
        onlinePlayers.forEach(player -> {
            UUID playerUUID = player.getUniqueId();
            String playerName = player.getName();

            storage.loadPlayerSeasonData(playerUUID)
                    .thenAcceptAsync(success -> { // Handle load result async
                        // Switch to main thread for Bukkit API interactions
                        Bukkit.getScheduler().runTask(this, () -> {
                            if (success) {
                                transactionLogger.info("Successfully loaded/initialized data for online player {}", playerName);
                                // Attempt status recovery after successful load/init
                                api.recoverPlayerDataStatus(playerUUID).thenAcceptAsync(recoverSuccess -> {
                                    // Handle recovery result on main thread
                                    Bukkit.getScheduler().runTask(this, () -> {
                                        if (!recoverSuccess) {
                                            // Log failure, potential kick might be too harsh here if recovery fails
                                            transactionLogger.error("Failed to recover status for online player {}", playerName);
                                        }
                                    });
                                });
                            } else {
                                // Load failed critically (exception occurred)
                                transactionLogger.error("Failed to load data for online player {}. They might need to reconnect.", playerName);
                                // Consider kicking the player if data is essential for gameplay
                                // Player onlineP = Bukkit.getPlayer(playerUUID);
                                // if (onlineP != null) onlineP.kickPlayer("Failed to load essential player data.");
                            }
                        });
                    })
                    .exceptionally(e -> { // Catch exceptions during load process
                        transactionLogger.error("Error loading data for online player {}: {}", playerName, e.getMessage(), e);
                        // Ensure any Bukkit interaction (like kick) is on the main thread
                        Bukkit.getScheduler().runTask(this, () -> {
                            Player onlineP = Bukkit.getPlayer(playerUUID);
                            if (onlineP != null && onlineP.isOnline()) {
                                // onlineP.kickPlayer("Error loading your data during startup. Please reconnect.");
                            }
                        });
                        return null;
                    });
        });
    }

    /**
     * Logs basic plugin information (Name, Version, Server Name, Redis Status)
     * during enabling/disabling stages.
     * @param stage Current plugin lifecycle stage (e.g., "Enabling", "Disabling").
     */
    private void logPluginInfo(String stage) {
        transactionLogger.info("========================================");
        transactionLogger.info(" {} {} v{} ", stage, getName(), getDescription().getVersion());
        transactionLogger.info(" Server Name: {}", configManager.getServerName());
        if (configManager.isRedisEnabled()) {
            transactionLogger.info(" Redis Enabled: true (Host: {}:{}, DB: {}, Channel: {})",
                    configManager.getRedisHost(), configManager.getRedisPort(), configManager.getRedisDatabase(), configManager.getRedisChannel());
        } else {
            transactionLogger.info(" Redis Enabled: false");
        }
        transactionLogger.info("========================================");
    }

    /**
     * Gets the RedisManager instance if Redis is enabled and initialized.
     * @return An Optional containing the RedisManager, or empty if Redis is disabled/failed.
     */
    public Optional<RedisManager> getRedisManager() {
        return Optional.ofNullable(redisManager);
    }
}