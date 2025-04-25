package games.rang.jobSkillAPI.storage;

import games.rang.jobSkillAPI.JobSkillPlugin;
import games.rang.jobSkillAPI.config.ConfigManager;
import games.rang.jobSkillAPI.data.StaticDataManager;
import games.rang.jobSkillAPI.event.*;
import games.rang.jobSkillAPI.log.TransactionLogger;
import games.rang.jobSkillAPI.model.*;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Manages player data caching in memory and synchronizes it with the DatabaseHandler.
 * Handles loading, saving, modification operations, auto-saving, event dispatching,
 * season management, and server transfer coordination.
 */
public class Storage {
    private final Map<UUID, PlayerSeasonData> playerSeasonCache = new ConcurrentHashMap<>();
    private final Set<UUID> loadingPlayers = ConcurrentHashMap.newKeySet(); // Tracks players currently being loaded
    private final DatabaseHandler databaseHandler;
    private final TransactionLogger logger;
    private final ConfigManager config;
    private final StaticDataManager staticDataManager;
    private final ScheduledExecutorService scheduler;
    private final JobSkillPlugin plugin; // Needed for scheduling Bukkit tasks (event calls)
    private final AtomicReference<String> currentSeasonOverride = new AtomicReference<>(null); // For manual season override during reset

    /**
     * Constructor for the Storage class.
     * @param plugin The main plugin instance.
     * @param config The configuration manager instance.
     * @param logger The transaction logger instance.
     */
    public Storage(JobSkillPlugin plugin, ConfigManager config, TransactionLogger logger) {
        this.plugin = plugin;
        this.config = config;
        this.logger = logger;
        this.databaseHandler = new DatabaseHandler(config, logger);
        this.staticDataManager = new StaticDataManager(logger, databaseHandler, config);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "JobSkill-AutoSave-Thread");
            t.setDaemon(true);
            return t;
        });
        // Auto-save is started explicitly in JobSkillPlugin.onEnable()
    }

    /** Gets the ConfigManager instance. */
    public ConfigManager getConfigManager() { return config; }
    /** Gets the DatabaseHandler instance. */
    public DatabaseHandler getDatabaseHandler() { return databaseHandler; }
    /** Gets the TransactionLogger instance. */
    public TransactionLogger getLogger() { return logger; }
    /** Gets the StaticDataManager instance. */
    public StaticDataManager getStaticDataManager() { return staticDataManager; }

    /**
     * Gets the current season ID. Returns the overridden ID if set, otherwise calculates based on the current date.
     * Example format: "YYYY-QN" (e.g., "2024-Q3").
     * @return The current season ID string.
     */
    public String getCurrentSeasonId() {
        String override = currentSeasonOverride.get();
        if (override != null) {
            logger.debug("Using overridden season ID: {}", override);
            return override;
        }
        java.time.LocalDate now = java.time.LocalDate.now();
        int year = now.getYear();
        int month = now.getMonthValue();
        int quarter = (month - 1) / 3 + 1;
        return String.format("%d-Q%d", year, quarter);
    }

    /**
     * [Internal/Admin Use] Manually overrides the current season ID.
     * Used during global season resets to ensure all operations use the new ID.
     * Pass null to revert to date-based calculation.
     * @param newSeasonId The new season ID to enforce, or null to clear the override.
     */
    public void overrideSeasonId(String newSeasonId) {
        String oldOverride = currentSeasonOverride.getAndSet(newSeasonId);
        if (!Objects.equals(oldOverride, newSeasonId)) {
            if (newSeasonId != null) {
                logger.warn("Current season ID has been manually overridden to: {}", newSeasonId);
            } else {
                logger.warn("Manual season ID override has been cleared. Using date-based calculation.");
            }
        }
    }

    /**
     * Starts the periodic auto-save scheduler with a configured interval and initial delay.
     */
    public void startAutoSave() {
        long intervalMinutes = 5; // Configurable?
        long initialDelayMinutes = 1; // Configurable?
        logger.info("Starting auto-save scheduler (initial delay: {} min, interval: {} min)...", initialDelayMinutes, intervalMinutes);
        scheduler.scheduleAtFixedRate(this::runAutoSave, initialDelayMinutes, intervalMinutes, TimeUnit.MINUTES);
    }

    /**
     * Executes the auto-save task. Iterates through the cache and saves data
     * for the current season that is marked as 'dirty' and not currently loading.
     */
    private void runAutoSave() {
        String currentSeason = getCurrentSeasonId();
        logger.debug("Running auto-save task for season {}...", currentSeason);
        long start = System.currentTimeMillis();
        int playersToSave = 0;

        Set<UUID> currentPlayers = Set.copyOf(playerSeasonCache.keySet());

        for (UUID playerUUID : currentPlayers) {
            PlayerSeasonData data = playerSeasonCache.get(playerUUID);
            if (data != null && data.getSeasonId().equals(currentSeason) && data.isDirty() && !isPlayerLoading(playerUUID)) {
                playersToSave++;
                savePlayerSeasonData(playerUUID) // Async save call
                        .thenAccept(success -> {
                            if (!success) {
                                logger.error("Auto-save failed for player {}", playerUUID);
                            }
                        });
            }
        }

        long duration = System.currentTimeMillis() - start;
        if (playersToSave > 0) {
            logger.info("Auto-save task initiated for {} players in {} ms.", playersToSave, duration);
        } else {
            logger.debug("Auto-save task completed in {} ms. No dirty data found for current season.", duration);
        }
    }

    /**
     * Increases the player's experience for a specific content type asynchronously.
     * Checks prerequisites, updates cache, calculates level, logs transaction, and calls events on the main thread.
     * @param playerUUID The player's UUID.
     * @param contentId The ID of the content type.
     * @param amount The amount of experience to add (must be positive).
     * @param reason A description for logging/events.
     * @return A CompletableFuture indicating if data was modified (true) or not (false).
     */
    public CompletableFuture<Boolean> addContentExperience(UUID playerUUID, int contentId, long amount, String reason) {
        if (amount <= 0) return CompletableFuture.completedFuture(false);
        if (isPlayerLoading(playerUUID)) { logger.warn("Attempted to add experience for player {} while loading.", playerUUID); return CompletableFuture.completedFuture(false); }
        if (!databaseHandler.isDataActive(playerUUID)) { logger.warn("Cannot add experience for player {} - data status is not ACTIVE", playerUUID); return CompletableFuture.completedFuture(false); }
        Optional<ContentType> contentTypeOpt = staticDataManager.getContentType(contentId);
        if (contentTypeOpt.isEmpty()) { logger.warn("Attempted to add experience for non-existent contentId: {}", contentId); return CompletableFuture.completedFuture(false); }
        ContentType contentType = contentTypeOpt.get();

        PlayerSeasonData data = getCachedDataForModification(playerUUID);
        if (data == null) return CompletableFuture.completedFuture(false);

        PlayerContentProgress currentProgress = data.getProgress(contentId).orElse(new PlayerContentProgress(contentId, data.getSeasonId(), 0L, 1));
        long oldExp = currentProgress.getExperience();
        int oldLevel = currentProgress.getLevel();
        long newExp = oldExp + amount;
        int newLevel = staticDataManager.calculateLevel(contentId, newExp); // Calculate level based on config

        if (newExp != oldExp || newLevel != oldLevel) {
            PlayerContentProgress updatedProgress = new PlayerContentProgress(contentId, data.getSeasonId(), newExp, newLevel);
            data.setContentProgress(updatedProgress); // Updates cache and marks dirty
            logger.logTransaction(playerUUID, "ContentExp", contentId, oldExp, newExp, reason);
            if (newLevel > oldLevel) {
                logger.logTransaction(playerUUID, "ContentLevel", contentId, oldLevel, newLevel, "Level Up via " + reason);
                Player onlinePlayer = Bukkit.getPlayer(playerUUID);
                if (onlinePlayer != null && onlinePlayer.isOnline()) {
                    // Schedule event call on main thread
                    Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(new PlayerContentLevelUpEvent(onlinePlayer, contentType, newLevel, oldLevel, reason)));
                }
            }
            return CompletableFuture.completedFuture(true); // Data changed
        }
        return CompletableFuture.completedFuture(false); // No change
    }

    /**
     * Sets the player's chosen job for the current season asynchronously.
     * Checks prerequisites, updates cache, logs transaction, and calls events on the main thread.
     * @param playerUUID The player's UUID.
     * @param jobId The ID of the job to select.
     * @param reason A description for logging/events.
     * @return A CompletableFuture indicating if the job was successfully selected (true) or not (false).
     */
    public CompletableFuture<Boolean> selectJob(UUID playerUUID, int jobId, String reason) {
        if (isPlayerLoading(playerUUID)) { logger.warn("Attempted selectJob for {} while loading.", playerUUID); return CompletableFuture.completedFuture(false); }
        if (!databaseHandler.isDataActive(playerUUID)) { logger.warn("Cannot selectJob for {} - data status not ACTIVE.", playerUUID); return CompletableFuture.completedFuture(false); }
        Optional<Job> jobOpt = staticDataManager.getJob(jobId);
        if (jobOpt.isEmpty()) { logger.warn("Attempted to select non-existent job ID: {}", jobId); return CompletableFuture.completedFuture(false); }
        Job newJob = jobOpt.get();

        PlayerSeasonData data = getCachedDataForModification(playerUUID);
        if (data == null) return CompletableFuture.completedFuture(false);

        if (!staticDataManager.canSelectJob(data, jobId)) { logger.warn("Player {} does not meet requirements for job {}", playerUUID, jobId); return CompletableFuture.completedFuture(false); }
        Optional<Integer> oldJobIdOpt = data.getChosenJobId();
        if (oldJobIdOpt.isPresent() && oldJobIdOpt.get() == jobId) { logger.info("Player {} tried to select current job {} again.", playerUUID, jobId); return CompletableFuture.completedFuture(false); }
        Job oldJob = oldJobIdOpt.flatMap(staticDataManager::getJob).orElse(null);

        PlayerSeasonStats currentStats = data.getSeasonStats().orElse(new PlayerSeasonStats(playerUUID, data.getSeasonId(), null, 0));
        PlayerSeasonStats newStats = new PlayerSeasonStats(playerUUID, data.getSeasonId(), jobId, currentStats.getSkillPoints());
        data.setSeasonStats(newStats); // Updates cache and marks dirty

        logger.logTransaction(playerUUID, "JobSelect", "PlayerJob", oldJobIdOpt.map(Object::toString).orElse("None"), jobId, reason);

        Player onlinePlayer = Bukkit.getPlayer(playerUUID);
        if (onlinePlayer != null && onlinePlayer.isOnline()) {
            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(new PlayerJobSelectEvent(onlinePlayer, newJob, oldJob, reason)));
        }
        return CompletableFuture.completedFuture(true);
    }

    /**
     * Attempts to level up a player's skill asynchronously, using an externally provided cost.
     * Checks job, level, and skill point requirements, updates cache (points and level), logs, and calls events on the main thread.
     * @param playerUUID The player's UUID.
     * @param skillId The ID of the skill to level up.
     * @param reason A description for logging/events.
     * @param cost The skill point cost required for this level up (must be pre-calculated).
     * @return A CompletableFuture indicating if the skill was successfully leveled up (true) or not (false).
     */
    public CompletableFuture<Boolean> levelUpSkill(UUID playerUUID, int skillId, String reason, int cost) {
        if (isPlayerLoading(playerUUID)) { logger.warn("Attempted levelUpSkill for {} while loading.", playerUUID); return CompletableFuture.completedFuture(false); }
        if (!databaseHandler.isDataActive(playerUUID)) { logger.warn("Cannot levelUpSkill for {} - data status not ACTIVE.", playerUUID); return CompletableFuture.completedFuture(false); }
        if (cost < 0) { logger.error("Skill level up cost cannot be negative ({}) for skill {}.", cost, skillId); return CompletableFuture.completedFuture(false); }
        Optional<Skill> skillOpt = staticDataManager.getSkill(skillId);
        if (skillOpt.isEmpty()) { logger.warn("Attempted to level up non-existent skill ID: {}", skillId); return CompletableFuture.completedFuture(false); }
        Skill skill = skillOpt.get();

        PlayerSeasonData data = getCachedDataForModification(playerUUID);
        if (data == null) return CompletableFuture.completedFuture(false);

        Optional<Integer> chosenJobIdOpt = data.getChosenJobId();
        if (chosenJobIdOpt.isEmpty() || skill.getAssociatedJobId() != chosenJobIdOpt.get()) { logger.warn("Player {} cannot level up skill {} - wrong job selected.", playerUUID, skillId); return CompletableFuture.completedFuture(false); }
        int currentLevel = data.getSkillLevel(skillId);
        int maxLevel = skill.getMaxLevel();
        if (currentLevel >= maxLevel) { logger.info("Player {} skill {} already at max level {}.", playerUUID, skillId, maxLevel); return CompletableFuture.completedFuture(false); }
        int currentSkillPoints = data.getSkillPoints();
        if (currentSkillPoints < cost) { logger.info("Player {} cannot level up skill {} - needs {} SP, has {}.", playerUUID, skillId, cost, currentSkillPoints); return CompletableFuture.completedFuture(false); }

        // Apply changes to cache
        PlayerSeasonStats currentStats = data.getSeasonStats().orElseThrow(() -> new IllegalStateException("Stats missing during skill level up for " + playerUUID));
        int newSkillPoints = currentSkillPoints - cost;
        PlayerSeasonStats updatedStats = new PlayerSeasonStats(playerUUID, data.getSeasonId(), chosenJobIdOpt.get(), newSkillPoints);
        data.setSeasonStats(updatedStats);

        int newLevel = currentLevel + 1;
        PlayerSkillLevel updatedSkillLevel = new PlayerSkillLevel(skillId, data.getSeasonId(), newLevel);
        data.setSkillLevel(updatedSkillLevel);

        // Log changes
        logger.logTransaction(playerUUID, "SkillPoint", "Cost", currentSkillPoints, newSkillPoints, reason + " Cost");
        logger.logTransaction(playerUUID, "SkillLevel", skillId, currentLevel, newLevel, reason);

        // Call events on main thread
        Player onlinePlayer = Bukkit.getPlayer(playerUUID);
        if (onlinePlayer != null && onlinePlayer.isOnline()) {
            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(new PlayerSkillPointsChangeEvent(onlinePlayer, newSkillPoints, currentSkillPoints, reason + " Cost")));
            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(new PlayerSkillLevelUpEvent(onlinePlayer, skill, newLevel, currentLevel, cost, reason)));
        }
        return CompletableFuture.completedFuture(true);
    }

    /**
     * Adds skill points to the player's current season total asynchronously.
     * Checks prerequisites, updates cache, logs transaction, and calls events on the main thread.
     * @param playerUUID The player's UUID.
     * @param amount The number of skill points to add (must be positive).
     * @param reason A description for logging/events.
     * @return A CompletableFuture indicating if data was modified (true) or not (false).
     */
    public CompletableFuture<Boolean> addSkillPoints(UUID playerUUID, int amount, String reason) {
        if (amount <= 0) return CompletableFuture.completedFuture(false);
        if (isPlayerLoading(playerUUID)) { logger.warn("Attempted addSkillPoints for {} while loading.", playerUUID); return CompletableFuture.completedFuture(false); }
        if (!databaseHandler.isDataActive(playerUUID)) { logger.warn("Cannot addSkillPoints for {} - data status not ACTIVE.", playerUUID); return CompletableFuture.completedFuture(false); }

        PlayerSeasonData data = getCachedDataForModification(playerUUID);
        if (data == null) return CompletableFuture.completedFuture(false);

        PlayerSeasonStats currentStats = data.getSeasonStats().orElse(new PlayerSeasonStats(playerUUID, data.getSeasonId(), null, 0));
        int oldPoints = currentStats.getSkillPoints();
        int newPoints = oldPoints + amount;
        PlayerSeasonStats updatedStats = new PlayerSeasonStats(playerUUID, data.getSeasonId(), currentStats.getChosenJobId().orElse(null), newPoints);
        data.setSeasonStats(updatedStats); // Updates cache and marks dirty

        logger.logTransaction(playerUUID, "SkillPoint", "Gain", oldPoints, newPoints, reason);

        Player onlinePlayer = Bukkit.getPlayer(playerUUID);
        if (onlinePlayer != null && onlinePlayer.isOnline()) {
            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(new PlayerSkillPointsChangeEvent(onlinePlayer, newPoints, oldPoints, reason)));
        }
        return CompletableFuture.completedFuture(true);
    }

    /**
     * Clears (deletes) all data for the current season for a specific player from the database and removes them from the cache asynchronously.
     * Calls the PlayerSeasonResetEvent on success. Use with caution.
     * @param playerUUID The player's UUID.
     * @param reason The reason for clearing the data (for logging/events).
     * @return A CompletableFuture indicating success (true) or failure (false).
     */
    public CompletableFuture<Boolean> clearCurrentSeasonData(UUID playerUUID, String reason) {
        String seasonId = getCurrentSeasonId();
        logger.warn("API call to clear current season ({}) data for player {}. Reason: {}", seasonId, playerUUID, reason);
        Player onlinePlayer = Bukkit.getPlayer(playerUUID); // For event call

        PlayerSeasonData removedData = playerSeasonCache.remove(playerUUID); // Remove from cache first
        if (removedData == null) { logger.info("No cached data found for player {} to clear.", playerUUID); }

        return databaseHandler.deletePlayerSeasonData(playerUUID, seasonId)
                .thenApply(success -> {
                    if (success) {
                        logger.logTransaction(playerUUID, "SeasonReset", seasonId, "ExistingData", "Cleared", reason);
                        if (onlinePlayer != null && onlinePlayer.isOnline()) {
                            Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(new PlayerSeasonResetEvent(onlinePlayer, seasonId, reason)));
                        }
                    } else {
                        logger.error("Failed to clear season data in DB for player {}.", playerUUID);
                        // If DB delete failed, potentially put data back in cache?
                        if (removedData != null) {
                            playerSeasonCache.put(playerUUID, removedData); // Put it back
                            logger.warn("Restored cached data for {} due to DB deletion failure (state might be inconsistent).", playerUUID);
                        }
                    }
                    return success;
                });
    }

    /**
     * Resets job-related data for the current season (sets job to null, skill points to 0, deletes skill levels from DB and cache) asynchronously.
     * Content progress is preserved. Calls the PlayerJobResetEvent on success.
     * @param playerUUID The player's UUID.
     * @param reason The reason for resetting the job data (for logging/events).
     * @return A CompletableFuture indicating success (true) or failure (false).
     */
    public CompletableFuture<Boolean> resetJobData(UUID playerUUID, String reason) {
        logger.info("API call to reset job data for player {}. Reason: {}", playerUUID, reason);
        if (isPlayerLoading(playerUUID)) { logger.warn("Cannot reset job data for {} while loading.", playerUUID); return CompletableFuture.completedFuture(false); }
        if (!databaseHandler.isDataActive(playerUUID)) { logger.warn("Cannot reset job data for {} - data status not ACTIVE.", playerUUID); return CompletableFuture.completedFuture(false); }

        PlayerSeasonData data = getCachedDataForModification(playerUUID);
        if (data == null) return CompletableFuture.completedFuture(false);

        Optional<Integer> oldJobIdOpt = data.getChosenJobId();
        int oldSkillPoints = data.getSkillPoints();
        boolean hadSkills = !data.getSkillLevelMap().isEmpty();
        Job oldJob = oldJobIdOpt.flatMap(staticDataManager::getJob).orElse(null); // For event

        if (oldJobIdOpt.isEmpty() && oldSkillPoints == 0 && !hadSkills) {
            logger.info("Player {} has no job data to reset.", playerUUID);
            return CompletableFuture.completedFuture(true); // Nothing to do
        }

        // Update cache: Reset stats
        PlayerSeasonStats resetStats = new PlayerSeasonStats(playerUUID, data.getSeasonId(), null, 0);
        data.setSeasonStats(resetStats);
        // Update cache: Mark skills for deletion by setting level to 0
        if (hadSkills) {
            Set<Integer> currentSkillIds = new HashSet<>(data.getSkillLevelMap().keySet());
            for(int skillId : currentSkillIds) {
                data.setSkillLevel(new PlayerSkillLevel(skillId, data.getSeasonId(), 0)); // This marks data as dirty
            }
        } else {
            data.markDirty(); // Mark dirty even if only stats changed
        }

        // Now save the data. The save method will handle deleting level 0 skills.
        return savePlayerSeasonData(playerUUID).thenApply(saveSuccess -> {
            if (saveSuccess) {
                // Log changes and call event
                logger.logTransaction(playerUUID, "JobReset", "PlayerJob", oldJobIdOpt.map(Object::toString).orElse("None"), "None", reason);
                if (oldSkillPoints > 0) { logger.logTransaction(playerUUID, "SkillPoint", "Reset", oldSkillPoints, 0, reason); }
                if (hadSkills) { logger.logTransaction(playerUUID, "SkillLevel", "All", "Existing", "Cleared", reason); }
                Player onlinePlayer = Bukkit.getPlayer(playerUUID);
                if (onlinePlayer != null && onlinePlayer.isOnline()) {
                    Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getPluginManager().callEvent(new PlayerJobResetEvent(onlinePlayer, oldJob, reason)));
                }
                return true;
            } else {
                logger.error("Failed to save data during job reset for {}. DB/Cache state might be inconsistent!", playerUUID);
                // Consider attempting to rollback cache changes?
                return false;
            }
        });
    }

    /**
     * Helper method to get modifiable cached data for a player, ensuring it's for the current season.
     * Logs errors if data is not found or season mismatches.
     * @param playerUUID The player's UUID.
     * @return The PlayerSeasonData object, or null if not found or season mismatch.
     */
    private PlayerSeasonData getCachedDataForModification(UUID playerUUID) {
        PlayerSeasonData data = playerSeasonCache.get(playerUUID);
        if (data == null) {
            logger.error("Attempted to modify data for player {} but not found in cache.", playerUUID);
            return null;
        }
        String currentSeason = getCurrentSeasonId();
        if (!data.getSeasonId().equals(currentSeason)) {
            logger.error("Attempted to modify data for player {} with mismatched season ID (Cache: {}, Current: {}). Operation cancelled.",
                    playerUUID, data.getSeasonId(), currentSeason);
            return null;
        }
        return data;
    }

    /**
     * Loads player data for the current season asynchronously from the database.
     * If data is not found in DB, creates a default cache entry. Manages loading state.
     * @param playerUUID The player's UUID.
     * @return A CompletableFuture indicating if the load attempt was processed (true) or failed/skipped (false).
     */
    public CompletableFuture<Boolean> loadPlayerSeasonData(UUID playerUUID) {
        if (playerUUID == null) { logger.error("Attempted to load data with null UUID"); return CompletableFuture.completedFuture(false); }
        if (!loadingPlayers.add(playerUUID)) { // Atomically add and check if already present
            logger.warn("Load already in progress for player {}, skipping.", playerUUID);
            // Return a future that completes successfully, as the load is being handled elsewhere
            return CompletableFuture.completedFuture(true);
        }

        String seasonId = getCurrentSeasonId();
        logger.info("Loading player data for {} (Season: {})...", playerUUID, seasonId);

        return databaseHandler.loadPlayerSeasonData(playerUUID, seasonId)
                .thenApply(optionalData -> {
                    // Put loaded data or default data into cache
                    PlayerSeasonData dataToCache = optionalData.orElseGet(() -> new PlayerSeasonData(playerUUID, seasonId));
                    playerSeasonCache.put(playerUUID, dataToCache);
                    if (optionalData.isPresent()) {
                        logger.info("Successfully loaded data for {} in season {}.", playerUUID, seasonId);
                    } else {
                        logger.info("No existing data found for {} in season {}. Initialized default data.", playerUUID, seasonId);
                    }
                    return true; // Load attempt processed successfully
                })
                .exceptionally(e -> {
                    logger.error("Exception during player data load for {}: {}", playerUUID, e.getMessage(), e);
                    playerSeasonCache.remove(playerUUID); // Remove potentially incomplete data
                    return false; // Load failed
                })
                .whenComplete((success, throwable) -> {
                    loadingPlayers.remove(playerUUID); // Remove from loading set regardless of outcome
                    logger.debug("Finished loading attempt for {} with result: {}", playerUUID, success);
                });
    }

    /**
     * Saves player data for the current season asynchronously if it's marked as dirty.
     * Clears the dirty flag on successful save. The save operation includes deleting skills marked with level 0.
     * @param playerUUID The player's UUID.
     * @return A CompletableFuture indicating success (true) or failure/not needed (false/true).
     */
    public CompletableFuture<Boolean> savePlayerSeasonData(UUID playerUUID) {
        PlayerSeasonData data = playerSeasonCache.get(playerUUID);
        // Check if data exists, belongs to current season, and is dirty
        if (data == null || !data.getSeasonId().equals(getCurrentSeasonId()) || !data.isDirty()) {
            return CompletableFuture.completedFuture(true); // Nothing to save or wrong season
        }

        logger.debug("Saving dirty data for player {}...", playerUUID);
        // Pass the data object to the database handler
        return databaseHandler.savePlayerSeasonData(data)
                .thenApply(success -> {
                    if (success) {
                        data.clearDirty(); // Clear dirty flag only on successful save
                        logger.debug("Successfully saved data for {}", playerUUID);
                    } else {
                        logger.error("Failed DB save for {}", playerUUID);
                        // Optionally re-mark as dirty? Depends on error handling strategy.
                    }
                    return success;
                })
                .exceptionally(e -> {
                    logger.error("Exception during save for {}: {}", playerUUID, e.getMessage(), e);
                    return false;
                });
    }

    /**
     * Saves player data asynchronously (if dirty) and then removes it from the cache.
     * Typically used when a player leaves the server.
     * @param playerUUID The player's UUID.
     * @return A CompletableFuture indicating the outcome of the save attempt (true for success/no-op, false for failure).
     */
    public CompletableFuture<Boolean> saveAndRemovePlayerData(UUID playerUUID) {
        logger.info("Saving and removing player data for {}...", playerUUID);
        if (isPlayerLoading(playerUUID)) {
            logger.warn("Save/remove called for {} while loading. Removing from cache without saving.", playerUUID);
            playerSeasonCache.remove(playerUUID);
            loadingPlayers.remove(playerUUID); // Ensure loading flag is cleared
            return CompletableFuture.completedFuture(true); // Considered handled (removed from cache)
        }

        return savePlayerSeasonData(playerUUID)
                .whenComplete((saveSuccess, throwable) -> {
                    // Always remove from cache after save attempt completes
                    PlayerSeasonData removedData = playerSeasonCache.remove(playerUUID);
                    if (removedData != null) {
                        if (Boolean.TRUE.equals(saveSuccess)) {
                            logger.info("Successfully saved and removed data for {}", playerUUID);
                        } else {
                            logger.error("Failed to save data for {}, but removed from cache anyway.", playerUUID);
                            if (throwable != null) {
                                logger.error("Save exception for {}: {}", playerUUID, throwable.getMessage());
                            }
                        }
                    } else {
                        // This might happen if player leaves very quickly after join before load finishes?
                        logger.warn("Tried to remove player {} data, but it was not found in cache.", playerUUID);
                    }
                })
                .thenApply(Boolean::valueOf); // Return the result of the save operation
    }

    /**
     * Saves all dirty player data for the current season asynchronously.
     * Used during plugin shutdown or potentially periodic mass saves.
     * @return A CompletableFuture that completes when all save operations have been initiated.
     */
    public CompletableFuture<Void> saveAllPlayerData() {
        logger.info("Saving all dirty player data ({} players in cache)...", playerSeasonCache.size());
        String currentSeason = getCurrentSeasonId();
        List<CompletableFuture<Boolean>> futures = playerSeasonCache.entrySet().stream()
                // Filter for current season, dirty data, and not currently loading
                .filter(entry -> entry.getValue().getSeasonId().equals(currentSeason)
                        && entry.getValue().isDirty()
                        && !isPlayerLoading(entry.getKey()))
                .map(entry -> savePlayerSeasonData(entry.getKey()))
                .collect(Collectors.toList());

        if (futures.isEmpty()) {
            logger.info("No dirty data found to save.");
            return CompletableFuture.completedFuture(null);
        }

        logger.info("Attempting to save data for {} players.", futures.size());
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .whenComplete((voidResult, throwable) -> {
                    if (throwable != null) {
                        logger.error("Exception occurred during saveAllPlayerData completion: ", throwable);
                    } else {
                        // Check results of individual save futures
                        long failedCount = futures.stream().filter(f -> !f.join()).count();
                        if (failedCount > 0) {
                            logger.warn("saveAllPlayerData completed, but {} save operations failed.", failedCount);
                        } else {
                            logger.info("saveAllPlayerData completed successfully for {} players.", futures.size());
                        }
                    }
                });
    }

    /**
     * Sets the player's data status in the database asynchronously (e.g., ACTIVE, READONLY, SUSPENDED).
     * @param playerUUID The player's UUID.
     * @param status The new status string.
     * @return A CompletableFuture indicating success (true) or failure (false).
     */
    public CompletableFuture<Boolean> setDataStatus(UUID playerUUID, String status) {
        return databaseHandler.updateDataStatus(playerUUID, status);
    }

    /**
     * Checks if the player's data status is currently READONLY in the database asynchronously.
     * @param playerUUID The player's UUID.
     * @return A CompletableFuture indicating if the status is READONLY (true) or not/error (false).
     */
    public CompletableFuture<Boolean> isDataReadOnly(UUID playerUUID) {
        return databaseHandler.validateReadOnlyStatus(playerUUID);
    }

    /**
     * Handles the process for a player transferring to another server asynchronously.
     * Saves data, updates DB status (READONLY or SUSPENDED based on target server config), logs, and removes from cache.
     * @param playerUUID The player's UUID.
     * @param targetServer The name of the destination server.
     * @return A CompletableFuture indicating success (true) or failure (false).
     */
    public CompletableFuture<Boolean> handleServerTransfer(UUID playerUUID, String targetServer) {
        logger.info("Handling server transfer for {} to {}...", playerUUID, targetServer);
        if (isPlayerLoading(playerUUID)) {
            logger.error("Cannot handle server transfer for {}: Data is loading.", playerUUID);
            return CompletableFuture.completedFuture(false);
        }

        boolean targetIsApiServer = config.isApiEnabledServer(targetServer);
        String targetStatus = targetIsApiServer ? "READONLY" : "SUSPENDED";
        String trackingStatus = targetIsApiServer ? "TRANSFER_TO_API_SERVER" : "TRANSFER_TO_NON_API_SERVER";

        // 1. Save current data
        return savePlayerSeasonData(playerUUID)
                .thenCompose(saved -> {
                    if (!saved) {
                        logger.error("CRITICAL: Failed to save player data for {} before server transfer to {}. Aborting transfer DB update.", playerUUID, targetServer);
                        // Decide if transfer should still proceed or fail completely
                        return CompletableFuture.completedFuture(false); // Abort DB update if save fails
                    }
                    logger.info("Data saved successfully for {} before transfer.", playerUUID);
                    // 2. Update DB status and log
                    return databaseHandler.updateServerInfoAndLog(playerUUID, config.getServerName(), targetServer, targetStatus, trackingStatus);
                })
                .thenApply(updated -> {
                    if (updated) {
                        // 3. Remove from cache ONLY after successful DB update
                        playerSeasonCache.remove(playerUUID);
                        logger.info("Server transfer DB update successful for {}, removed from cache.", playerUUID);
                    } else {
                        logger.error("Failed to update server info in DB for transfer of {}. Data remains in cache.", playerUUID);
                        // This state might require manual intervention or retry logic
                    }
                    return updated; // Return success status of DB update
                })
                .exceptionally(e -> {
                    logger.error("Exception during server transfer handling for {}: {}", playerUUID, e.getMessage(), e);
                    return false;
                });
    }

    /**
     * Marks a player as currently loading data or finished loading.
     * @param uuid The player's UUID.
     * @param loading true to mark as loading, false to mark as finished.
     */
    public void setPlayerLoading(UUID uuid, boolean loading) {
        if (loading) {
            if(loadingPlayers.add(uuid)) { // Only log if state changes
                logger.debug("Marked player {} as loading.", uuid);
            }
        } else {
            if(loadingPlayers.remove(uuid)) { // Only log if state changes
                logger.debug("Marked player {} as finished loading.", uuid);
            }
        }
    }

    /**
     * Checks if a player's data is currently being loaded.
     * @param uuid The player's UUID.
     * @return true if data is loading, false otherwise.
     */
    public boolean isPlayerLoading(UUID uuid) {
        return loadingPlayers.contains(uuid);
    }

    /**
     * Gets the loaded player data for the current season, if available and not currently loading.
     * @param uuid The player's UUID.
     * @return An Optional containing the PlayerSeasonData, or empty if not loaded, loading, or season mismatch.
     */
    public Optional<PlayerSeasonData> getLoadedPlayerData(UUID uuid) {
        if (isPlayerLoading(uuid)) {
            return Optional.empty();
        }
        PlayerSeasonData data = playerSeasonCache.get(uuid);
        // Ensure data exists and matches the current season
        if (data != null && data.getSeasonId().equals(getCurrentSeasonId())) {
            return Optional.of(data);
        }
        return Optional.empty();
    }

    /** Returns an unmodifiable set of UUIDs for players currently in the cache. */
    public Set<UUID> getCachedPlayerUUIDs() {
        return Collections.unmodifiableSet(playerSeasonCache.keySet());
    }

    /** Returns a set of UUIDs for players who are currently online AND whose data is loaded (not in the loading state). */
    public Set<UUID> getOnlineAndLoadedPlayerUUIDs() {
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getUniqueId)
                .filter(uuid -> playerSeasonCache.containsKey(uuid) && !isPlayerLoading(uuid))
                .collect(Collectors.toSet());
    }

    /**
     * Removes player data from the cache directly. Use with caution, prefer saveAndRemovePlayerData.
     * @param playerUUID The player's UUID.
     * @return The removed PlayerSeasonData object, or null if not found.
     */
    public PlayerSeasonData removePlayerDataFromCache(UUID playerUUID) {
        logger.debug("Removing player data from cache for {}", playerUUID);
        return playerSeasonCache.remove(playerUUID);
    }

    /**
     * Shuts down the storage components, including the auto-save scheduler and the database handler.
     * Attempts graceful shutdown of the scheduler with a timeout.
     */
    public void shutdown() {
        logger.info("Shutting down Storage...");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warn("Scheduler did not terminate gracefully, forcing shutdown.");
                scheduler.shutdownNow();
            } else {
                logger.info("Scheduler terminated successfully.");
            }
        } catch (InterruptedException e) {
            logger.error("Interrupted while waiting for scheduler shutdown.", e);
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        if (databaseHandler != null) {
            databaseHandler.close();
        }
        logger.info("Storage shutdown complete.");
    }
}