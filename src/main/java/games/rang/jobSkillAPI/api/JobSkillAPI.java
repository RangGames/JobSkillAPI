package games.rang.jobSkillAPI.api;

import games.rang.jobSkillAPI.JobSkillPlugin;
import games.rang.jobSkillAPI.data.StaticDataManager;
import games.rang.jobSkillAPI.log.TransactionLogger;
import games.rang.jobSkillAPI.model.*;
import games.rang.jobSkillAPI.redis.RedisManager;
import games.rang.jobSkillAPI.storage.Storage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Provides a public API for external plugins or commands to interact
 * with the JobSkillPlugin's features, such as accessing player data,
 * modifying progress, selecting jobs, leveling skills, managing server transfers,
 * and handling global season resets.
 */
public class JobSkillAPI {
    private static JobSkillAPI instance;
    private final Storage storage;
    private final TransactionLogger logger;
    private final StaticDataManager staticDataManager;
    private final JobSkillPlugin plugin; // Needed for Bukkit scheduler tasks

    /**
     * Private constructor for the singleton JobSkillAPI.
     * @param storage The Storage instance handling data persistence and caching.
     * @param plugin The main plugin instance.
     */
    private JobSkillAPI(Storage storage, JobSkillPlugin plugin) {
        this.storage = Objects.requireNonNull(storage, "Storage cannot be null for API");
        this.plugin = Objects.requireNonNull(plugin, "Plugin instance cannot be null for API");
        this.logger = storage.getLogger();
        this.staticDataManager = storage.getStaticDataManager();
    }

    /**
     * Initializes the API singleton instance. Should be called once during plugin enable.
     * @param storage The initialized Storage instance.
     * @param plugin The main plugin instance.
     */
    public static void init(Storage storage, JobSkillPlugin plugin) {
        if (instance == null) {
            instance = new JobSkillAPI(storage, plugin);
            instance.logger.info("JobSkillAPI initialized.");
        } else {
            instance.logger.warn("JobSkillAPI already initialized!");
        }
    }

    /**
     * Gets the singleton instance of the API.
     * @return The JobSkillAPI instance.
     * @throws IllegalStateException if the API has not been initialized via {@link #init(Storage, JobSkillPlugin)}.
     */
    public static JobSkillAPI getInstance() {
        if (instance == null) {
            throw new IllegalStateException("JobSkillAPI has not been initialized! Call init() first.");
        }
        return instance;
    }

    // --- Player Data Access ---

    /**
     * Gets the player's level for a specific content type in the current season.
     * Returns 1 if data is not loaded or the player hasn't progressed in this content.
     * @param playerUUID The player's UUID.
     * @param contentId The ID of the content type.
     * @return The player's level (default 1).
     */
    public int getContentLevel(UUID playerUUID, int contentId) {
        return storage.getLoadedPlayerData(playerUUID).map(d -> d.getContentLevel(contentId)).orElse(1);
    }

    /**
     * Gets the player's experience for a specific content type in the current season.
     * Returns 0 if data is not loaded or the player hasn't progressed in this content.
     * @param playerUUID The player's UUID.
     * @param contentId The ID of the content type.
     * @return The player's experience (default 0).
     */
    public long getContentExperience(UUID playerUUID, int contentId) {
        return storage.getLoadedPlayerData(playerUUID).map(d -> d.getContentExperience(contentId)).orElse(0L);
    }

    /**
     * Gets the player's level for a specific skill in the current season.
     * Returns 0 if data is not loaded or the skill is not learned.
     * @param playerUUID The player's UUID.
     * @param skillId The ID of the skill.
     * @return The player's skill level (default 0).
     */
    public int getSkillLevel(UUID playerUUID, int skillId) {
        return storage.getLoadedPlayerData(playerUUID).map(d -> d.getSkillLevel(skillId)).orElse(0);
    }

    /**
     * Gets the ID of the job chosen by the player in the current season.
     * @param playerUUID The player's UUID.
     * @return An Optional containing the job ID, or empty if no job is chosen or data is not loaded.
     */
    public Optional<Integer> getChosenJobId(UUID playerUUID) {
        return storage.getLoadedPlayerData(playerUUID).flatMap(PlayerSeasonData::getChosenJobId);
    }

    /**
     * Gets the Job object chosen by the player in the current season.
     * @param playerUUID The player's UUID.
     * @return An Optional containing the Job object, or empty if no job is chosen, data is not loaded, or static data is missing.
     */
    public Optional<Job> getChosenJob(UUID playerUUID) {
        return getChosenJobId(playerUUID).flatMap(staticDataManager::getJob);
    }

    /**
     * Gets the Job class for the player in the current season.
     * @param playerUUID The player's UUID.
     * @return Default value is 1
     */
    public int getClassValue(UUID playerUUID) {
        return storage.getLoadedPlayerData(playerUUID)
                .flatMap(PlayerSeasonData::getSeasonStats)
                .map(PlayerSeasonStats::getClassValue)
                .orElse(1);
    }

    /**
     * Gets the player's current skill points for the current season.
     * Returns 0 if data is not loaded.
     * @param playerUUID The player's UUID.
     * @return The player's skill points (default 0).
     */
    public int getSkillPoints(UUID playerUUID) {
        return storage.getLoadedPlayerData(playerUUID).map(PlayerSeasonData::getSkillPoints).orElse(0);
    }

    /**
     * Gets an unmodifiable map of all content progress (Content ID -> Progress DTO) for the player in the current season.
     * Returns an empty map if data is not loaded.
     * @param playerUUID The player's UUID.
     * @return An unmodifiable map of content progress.
     */
    public Map<Integer, PlayerContentProgress> getAllContentProgress(UUID playerUUID) {
        return storage.getLoadedPlayerData(playerUUID).map(PlayerSeasonData::getContentProgressMap).orElse(Collections.emptyMap());
    }

    /**
     * Gets an unmodifiable map of all learned skill levels (Skill ID -> SkillLevel DTO) for the player in the current season.
     * Returns an empty map if data is not loaded.
     * @param playerUUID The player's UUID.
     * @return An unmodifiable map of skill levels.
     */
    public Map<Integer, PlayerSkillLevel> getAllSkillLevels(UUID playerUUID) {
        return storage.getLoadedPlayerData(playerUUID).map(PlayerSeasonData::getSkillLevelMap).orElse(Collections.emptyMap());
    }

    /**
     * Gets the total cumulative experience required to reach the specified target level for a specific content type.
     * Calculation is based on the settings in config.yml.
     *
     * @param contentId The ID of the content type.
     * @param targetLevel The target level (must be 1 or greater).
     * @return The total cumulative experience required, or -1 if the requirement for the target level is not defined.
     */
    public long getTotalExperienceRequired(int contentId, int targetLevel) {
        if (targetLevel < 1) return 0;
        return staticDataManager.getTotalExperienceRequired(contentId, targetLevel);
    }

    /**
     * Gets the additional experience needed to level up from the current level to the next level for a specific content type.
     * Calculation is based on the settings in config.yml.
     *
     * @param contentId The ID of the content type.
     * @param currentLevel The current level (must be 1 or greater).
     * @return The additional experience required for the next level, or -1 if the next level is not defined or a configuration error occurs.
     */
    public long getExperienceForNextLevel(int contentId, int currentLevel) {
        if (currentLevel < 1) return -1;
        return staticDataManager.getExperienceRequiredForNextLevel(contentId, currentLevel);
    }

    /**
     * Calculates the remaining experience required for the player to reach the next level in a specific content type.
     *
     * @param playerUUID The UUID of the player.
     * @param contentId The ID of the content type.
     * @return The remaining experience needed for the next level, or -1 if the next level is not defined or player data could not be loaded.
     */
    public long getExperienceRemainingForNextLevel(UUID playerUUID, int contentId) {
        Optional<PlayerSeasonData> dataOpt = storage.getLoadedPlayerData(playerUUID);
        if (dataOpt.isEmpty()) {
            return -1;
        }
        PlayerSeasonData data = dataOpt.get();
        int currentLevel = data.getContentLevel(contentId);
        long currentExperience = data.getContentExperience(contentId);

        long requiredForNext = getExperienceForNextLevel(contentId, currentLevel);
        if (requiredForNext == -1) {
            return -1;
        }

        long currentLevelStartExp = getTotalExperienceRequired(contentId, currentLevel);
        if (currentLevelStartExp == -1 && currentLevel != 1) {
            logger.warn("Could not determine start experience for level {} (contentId {}) for player {}. Remaining calculation might be inaccurate.", currentLevel, contentId, playerUUID);
            return -1;
        }

        long progressInCurrentLevel = currentExperience - currentLevelStartExp;
        long remaining = requiredForNext - progressInCurrentLevel;

        return Math.max(0, remaining);
    }

    // --- Data Modification Methods ---

    /**
     * Increases the player's experience for a specific content type asynchronously.
     * Handles level calculation and triggers relevant events.
     * @param playerUUID The player's UUID.
     * @param contentId The ID of the content type.
     * @param amount The amount of experience to add (must be positive).
     * @param reason A description of why the experience was added (for logging/events).
     * @return A CompletableFuture indicating if the data was modified (true) or not (false).
     */
    public CompletableFuture<Boolean> addContentExperience(UUID playerUUID, int contentId, long amount, String reason) {
        if (amount <= 0) return CompletableFuture.completedFuture(false);
        return storage.addContentExperience(playerUUID, contentId, amount, reason);
    }

    /**
     * Decreases the player's experience for a specific content asynchronously.
     * Level down may occur automatically.
     * @param playerUUID The UUID of the target player.
     * @param contentId The content ID.
     * @param amount The amount of experience to decrease (must be positive).
     * @param reason The reason for the change (for logging/events).
     * @return A CompletableFuture containing true if the experience was successfully decreased, false otherwise.
     */
    public CompletableFuture<Boolean> removeContentExperience(UUID playerUUID, int contentId, long amount, String reason) {
        if (amount <= 0) { logger.warn("API: Amount must be positive for removeContentExperience."); return CompletableFuture.completedFuture(false); }
        return storage.removeContentExperience(playerUUID, contentId, amount, reason);
    }

    /**
     * Sets the player's experience for a specific content to a given value asynchronously.
     * The level is automatically recalculated.
     * @param playerUUID The UUID of the target player.
     * @param contentId The content ID.
     * @param amount The experience amount to set (must be zero or positive).
     * @param reason The reason for the change (for logging/events).
     * @return A CompletableFuture containing true if the operation was successful, false otherwise.
     */
    public CompletableFuture<Boolean> setContentExperience(UUID playerUUID, int contentId, long amount, String reason) {
        if (amount < 0) { logger.warn("API: Amount cannot be negative for setContentExperience."); return CompletableFuture.completedFuture(false); }
        return storage.setContentExperience(playerUUID, contentId, amount, reason);
    }

    /**
     * Sets the Job class for the player in the current season.
     * @param playerUUID The player's UUID.
     * @param classValue The job's class.
     * @param reason A description of why the class was changed.
     */
    public CompletableFuture<Boolean> setClassValue(UUID playerUUID, int classValue, String reason) {
        if (classValue < 0) {
            logger.error("Attempted to set invalid class value {} for player {}", classValue, playerUUID);
            return CompletableFuture.completedFuture(false);
        }
        return storage.setClassValue(playerUUID, classValue, reason);
    }


    /**
     * Sets the player's chosen job for the current season asynchronously.
     * Checks requirements and triggers relevant events.
     * @param playerUUID The player's UUID.
     * @param jobId The ID of the job to select.
     * @param reason A description of why the job was selected (for logging/events).
     * @return A CompletableFuture indicating success (true) or failure (false).
     */
    public CompletableFuture<Boolean> selectJob(UUID playerUUID, int jobId, String reason) {
        return storage.selectJob(playerUUID, jobId, reason);
    }

    /**
     * Attempts to level up a player's skill asynchronously, using an externally provided cost.
     * Checks job requirements, max level, and skill point availability. Triggers relevant events.
     * @param playerUUID The player's UUID.
     * @param skillId The ID of the skill to level up.
     * @param reason A description of why the skill was leveled up (for logging/events).
     * @param cost The skill point cost required for this level up (must be pre-calculated and non-negative).
     * @return A CompletableFuture indicating success (true) or failure (false).
     */
    public CompletableFuture<Boolean> levelUpSkill(UUID playerUUID, int skillId, String reason, int cost) {
        if (cost < 0) {
            logger.error("Skill level up cost cannot be negative! (Skill: {}, Cost: {})", skillId, cost);
            return CompletableFuture.completedFuture(false);
        }
        return storage.levelUpSkill(playerUUID, skillId, reason, cost);
    }

    /**
     * Adds skill points to the player's current season total asynchronously.
     * Triggers relevant events.
     * @param playerUUID The player's UUID.
     * @param amount The number of skill points to add (must be positive).
     * @param reason A description of why the points were added (for logging/events).
     * @return A CompletableFuture indicating if the data was modified (true) or not (false).
     */
    public CompletableFuture<Boolean> addSkillPoints(UUID playerUUID, int amount, String reason) {
        if (amount <= 0) return CompletableFuture.completedFuture(false);
        return storage.addSkillPoints(playerUUID, amount, reason);
    }

    /**
     * Deducts skill points from the player's current total asynchronously.
     * @param playerUUID The UUID of the target player.
     * @param amount The number of skill points to deduct (must be positive).
     * @param reason The reason for the change (for logging/events).
     * @return A CompletableFuture containing true if the deduction was successful, false otherwise.
     */
    public CompletableFuture<Boolean> removeSkillPoints(UUID playerUUID, int amount, String reason) {
        if (amount <= 0) { logger.warn("API: Amount must be positive for removeSkillPoints."); return CompletableFuture.completedFuture(false); }
        return storage.removeSkillPoints(playerUUID, amount, reason);
    }

    /**
     * Sets the player's skill points to a specific value asynchronously.
     * @param playerUUID The UUID of the target player.
     * @param amount The skill points to set (must be zero or positive).
     * @param reason The reason for the change (for logging/events).
     * @return A CompletableFuture containing true if the operation was successful, false otherwise.
     */
    public CompletableFuture<Boolean> setSkillPoints(UUID playerUUID, int amount, String reason) {
        if (amount < 0) { logger.warn("API: Amount cannot be negative for setSkillPoints."); return CompletableFuture.completedFuture(false); }
        return storage.setSkillPoints(playerUUID, amount, reason);
    }

    /**
     * Resets (deletes) all data for a specific player in the current season asynchronously.
     * This includes stats, progress, and skills. Use with extreme caution.
     * @param playerUUID The UUID of the player whose data will be reset.
     * @param reason The reason for the reset (for logging/events).
     * @return A CompletableFuture indicating success (true) or failure (false).
     */
    public CompletableFuture<Boolean> resetCurrentSeasonData(UUID playerUUID, String reason) {
        logger.warn("API request to reset current season data for player {}. Reason: {}", playerUUID, reason);
        // Consider adding permission checks here if needed
        return storage.clearCurrentSeasonData(playerUUID, reason);
    }

    /**
     * Resets job-related data (chosen job, skill levels, skill points) for a player
     * in the current season asynchronously. Content progress (levels/experience) is preserved.
     * @param playerUUID The UUID of the player whose job data will be reset.
     * @param reason The reason for the reset (for logging/events).
     * @return A CompletableFuture indicating success (true) or failure (false).
     */
    public CompletableFuture<Boolean> resetCurrentJobData(UUID playerUUID, String reason) {
        logger.info("API request to reset current job data for player {}. Reason: {}", playerUUID, reason);
        // Consider adding permission checks here if needed
        return storage.resetJobData(playerUUID, reason);
    }

    // --- Static Data Access ---
    /** Gets a ContentType definition by its ID. */
    public Optional<ContentType> getContentType(int id) { return staticDataManager.getContentType(id); }
    /** Gets a Job definition by its ID. */
    public Optional<Job> getJob(int id) { return staticDataManager.getJob(id); }
    /** Gets a Skill definition by its ID. */
    public Optional<Skill> getSkill(int id) { return staticDataManager.getSkill(id); }
    /** Gets an unmodifiable collection of all defined ContentTypes. */
    public Collection<ContentType> getAllContentTypes() { return staticDataManager.getAllContentTypes(); }
    /** Gets an unmodifiable collection of all defined Jobs. */
    public Collection<Job> getAllJobs() { return staticDataManager.getAllJobs(); }
    /** Gets an unmodifiable collection of all defined Skills. */
    public Collection<Skill> getAllSkills() { return staticDataManager.getAllSkills(); }
    /** Gets an unmodifiable list of all Skills associated with a specific Job ID. */
    public List<Skill> getSkillsForJob(int jobId) { return staticDataManager.getSkillsForJob(jobId); }

    /**
     * Gets a collection of Jobs that the player is currently eligible to select based on requirements (e.g., content levels).
     * @param playerUUID The player's UUID.
     * @return A collection of available Job objects. Empty if data is not loaded or static data missing.
     */
    public Collection<Job> getAvailableJobs(UUID playerUUID) {
        return storage.getLoadedPlayerData(playerUUID)
                .map(data -> staticDataManager.getAllJobs().stream()
                        .filter(job -> staticDataManager.canSelectJob(data, job.getId()))
                        .collect(Collectors.toList()))
                .orElse(Collections.emptyList()); // Return empty list if player data not loaded
    }

    // --- BungeeCord Synchronization Helpers ---

    /**
     * Saves the player's data immediately if it has pending changes (is dirty), asynchronously.
     * Often called before server transfers or shutdowns.
     * @param playerUUID The player's UUID.
     * @return A CompletableFuture indicating success (true) or failure (false). Returns true if no save was needed.
     */
    public CompletableFuture<Boolean> savePlayerData(UUID playerUUID) {
        return storage.savePlayerSeasonData(playerUUID);
    }

    /**
     * Sets the player's data modifiability status in the database asynchronously.
     * Used for server transfer locking.
     * @param playerUUID The player's UUID.
     * @param modifiable true sets status to 'ACTIVE', false sets to 'READONLY'.
     * @return A CompletableFuture indicating success (true) or failure (false).
     */
    public CompletableFuture<Boolean> setPlayerDataModifiable(UUID playerUUID, boolean modifiable) {
        return storage.setDataStatus(playerUUID, modifiable ? "ACTIVE" : "READONLY");
    }

    /**
     * Prepares for a player's server transfer asynchronously. Saves current data,
     * updates the database status ('READONLY' for API servers, 'SUSPENDED' for others),
     * logs the transfer, and removes the player from the local cache upon success.
     * @param playerUUID The UUID of the player transferring.
     * @param targetServer The name of the server the player is transferring to.
     * @return A CompletableFuture indicating success (true) or failure (false).
     */
    public CompletableFuture<Boolean> prepareServerTransfer(UUID playerUUID, String targetServer) {
        // handleServerTransfer internally checks target server type and sets status
        return storage.handleServerTransfer(playerUUID, targetServer);
    }

    /**
     * Attempts to recover a player's data status asynchronously, typically after abnormal disconnections
     * or server issues where the player might be stuck in a non-ACTIVE state (e.g., READONLY).
     * If the status is found to be non-ACTIVE, it attempts to set it back to 'ACTIVE'
     * and reloads the player's data to ensure cache consistency.
     * Uses default asynchronous execution context.
     * @param playerUUID The player's UUID.
     * @return A CompletableFuture indicating if the recovery was successful or deemed unnecessary (true), or if it failed (false).
     */
    public CompletableFuture<Boolean> recoverPlayerDataStatus(UUID playerUUID) {
        logger.info("Attempting to recover player data status for {}...", playerUUID);
        // Asynchronously check if data is currently active
        return CompletableFuture.supplyAsync(() -> storage.getDatabaseHandler().isDataActive(playerUUID))
                .thenComposeAsync(isActive -> { // Process the result asynchronously
                    if (!isActive) {
                        // Data is not active (could be READONLY, SUSPENDED, or missing status)
                        logger.warn("Player {} data status is not ACTIVE. Attempting to set ACTIVE.", playerUUID);
                        // Attempt to set status to ACTIVE
                        return setPlayerDataModifiable(playerUUID, true)
                                .thenComposeAsync(setActiveSuccess -> { // Process set status result asynchronously
                                    if (setActiveSuccess) {
                                        logger.info("Successfully set data status to ACTIVE for {}. Reloading data...", playerUUID);
                                        // Reload data to ensure cache consistency after status change
                                        return storage.loadPlayerSeasonData(playerUUID);
                                    } else {
                                        logger.error("Failed to set data status to ACTIVE for {}. Recovery failed.", playerUUID);
                                        return CompletableFuture.completedFuture(false); // Status change failed
                                    }
                                });
                    } else {
                        // Already ACTIVE, no recovery needed
                        logger.info("Player {} data status is already ACTIVE. Recovery not needed.", playerUUID);
                        return CompletableFuture.completedFuture(true); // Recovery successful (no action needed)
                    }
                })
                .exceptionally(e -> {
                    // Handle any exceptions during the recovery process
                    logger.error("Exception during player data recovery for {}: {}", playerUUID, e.getMessage(), e);
                    return false; // Recovery failed due to exception
                });
    }


    // --- Global Season Reset API ---

    /**
     * [Admin Use] Initiates the preparation phase for a global season reset across all API-enabled servers.
     * Publishes a "Prepare" message via Redis. Should be called from one designated server (e.g., admin server).
     * Requires Redis to be enabled and initialized.
     * @return A CompletableFuture that completes when the Redis publish task has been submitted.
     */
    public CompletableFuture<Void> triggerGlobalSeasonResetPreparation() {
        Optional<RedisManager> redisOpt = plugin.getRedisManager();
        if (redisOpt.isEmpty()) {
            logger.error("Cannot trigger global season reset preparation: Redis is disabled or not initialized.");
            return CompletableFuture.failedFuture(new IllegalStateException("Redis is unavailable."));
        }
        String currentServer = storage.getConfigManager().getServerName();
        logger.warn("ADMIN ACTION: Triggering global season reset PREPARATION from server: {}", currentServer);
        String message = "Prepare:" + currentServer + ":"; // Command:TriggerServer:Payload(empty)
        // Wrap the void publish call in runAsync to return CompletableFuture<Void>
        return CompletableFuture.runAsync(() ->
                redisOpt.get().publish(storage.getConfigManager().getRedisChannel(), message)
        );
    }

    /**
     * [Admin Use] Initiates the completion phase for a global season reset across all API-enabled servers.
     * Publishes a "Complete" message via Redis with the new season ID.
     * Should be called after confirming the database reset/migration is complete.
     * Requires Redis to be enabled and initialized.
     * @param newSeasonId The identifier for the new season (e.g., "2024-Q4"). Cannot be null or blank.
     * @return A CompletableFuture that completes when the Redis publish task has been submitted.
     */
    public CompletableFuture<Void> triggerGlobalSeasonResetCompletion(String newSeasonId) {
        if (newSeasonId == null || newSeasonId.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("New season ID cannot be null or blank."));
        }
        Optional<RedisManager> redisOpt = plugin.getRedisManager();
        if (redisOpt.isEmpty()) {
            logger.error("Cannot trigger global season reset completion: Redis is disabled or not initialized.");
            return CompletableFuture.failedFuture(new IllegalStateException("Redis is unavailable."));
        }
        String currentServer = storage.getConfigManager().getServerName();
        logger.warn("ADMIN ACTION: Triggering global season reset COMPLETION from server: {} for new season: {}", currentServer, newSeasonId);
        String message = "Complete:" + currentServer + ":" + newSeasonId; // Command:TriggerServer:Payload(newSeasonId)
        // Wrap the void publish call in runAsync to return CompletableFuture<Void>
        return CompletableFuture.runAsync(() ->
                redisOpt.get().publish(storage.getConfigManager().getRedisChannel(), message)
        );
    }

    /**
     * [Internal Use] Handles the "Prepare" signal received via Redis. Sets all local online player data to READONLY.
     * Called by RedisManager. Ensures static data is loaded before proceeding.
     * @param triggerServer The server that initiated the preparation signal.
     * @return A CompletableFuture indicating the success or failure of preparing local players.
     */
    public CompletableFuture<Boolean> prepareGlobalSeasonReset(String triggerServer) {
        logger.warn("Received prepareGlobalSeasonReset signal from server: {}", triggerServer);
        // Check if static data is loaded before proceeding
        if (!staticDataManager.isStaticDataLoaded()) {
            logger.error("Cannot prepare for season reset: Static data not loaded yet.");
            return CompletableFuture.completedFuture(false);
        }

        Set<UUID> playersToPrepare = storage.getOnlineAndLoadedPlayerUUIDs();
        logger.info("Preparing {} local online players for season reset...", playersToPrepare.size());
        if (playersToPrepare.isEmpty()) {
            // If no players online, preparation is trivially successful locally
            return CompletableFuture.completedFuture(true);
        }

        List<CompletableFuture<Boolean>> futures = playersToPrepare.stream()
                // Set status to READONLY asynchronously
                .map(uuid -> setPlayerDataModifiable(uuid, false))
                .collect(Collectors.toList());

        // Wait for all status updates to complete
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    // Check if any of the individual futures failed
                    long failedCount = futures.stream().filter(f -> !f.join()).count();
                    if (failedCount > 0) {
                        logger.error("{} players failed to prepare for season reset (status update failed)!", failedCount);
                        // Potentially attempt to revert successfully prepared players? Complex.
                        return false; // Preparation failed for some players
                    }
                    logger.info("All local online players successfully prepared for season reset.");
                    // Consider sending an ACK back via Redis if coordination requires it.
                    return true; // Preparation successful for all local players
                })
                .exceptionally(e -> {
                    logger.error("Exception during prepareGlobalSeasonReset handling", e);
                    return false; // Preparation failed due to exception
                });
    }

    /**
     * [Internal Use] Handles the "Complete" signal received via Redis. Overrides the season ID in Storage,
     * clears local player caches for the old season, sets player data status back to ACTIVE, and notifies players.
     * Called by RedisManager.
     * @param newSeasonId The identifier for the new season.
     * @param triggerServer The server that initiated the completion signal.
     * @return A CompletableFuture indicating the success or failure of finalizing the reset locally.
     */
    public CompletableFuture<Boolean> completeGlobalSeasonReset(String newSeasonId, String triggerServer) {
        logger.warn("Received completeGlobalSeasonReset signal from server: {}. Applying new season: {}", triggerServer, newSeasonId);
        // Immediately override the season ID used by Storage for all subsequent operations
        storage.overrideSeasonId(newSeasonId);

        // Get players currently loaded (might still have old season data in cache at this point)
        // Using getCachedPlayerUUIDs is safer than getOnlineAndLoadedPlayerUUIDs as the seasonId might mismatch now
        Set<UUID> playersToFinalize = storage.getCachedPlayerUUIDs();
        logger.info("Finalizing season reset for {} cached players (New Season: {})...", playersToFinalize.size(), newSeasonId);

        List<CompletableFuture<Boolean>> futures = new ArrayList<>();
        for (UUID playerUUID : playersToFinalize) {
            // Remove potentially old season data from cache
            storage.removePlayerDataFromCache(playerUUID);
            // Set DB status back to ACTIVE (player can now load new season data)
            futures.add(setPlayerDataModifiable(playerUUID, true));

            // Notify the player if they are online (use Bukkit scheduler for safety)
            Player onlinePlayer = Bukkit.getPlayer(playerUUID);
            if (onlinePlayer != null && onlinePlayer.isOnline()) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        onlinePlayer.sendMessage("§aA new season [" + newSeasonId + "] has begun! Your progress has been reset.")
                );
                // Optionally trigger a load for the *new* season immediately?
                // Could be done here or rely on PlayerJoin logic / next interaction.
                // Be cautious of triggering too many loads at once.
                // storage.loadPlayerSeasonData(playerUUID); // Load new season data
            }
        }

        // Wait for all status updates to complete
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    // Check if any individual future failed
                    long failedCount = futures.stream().filter(f -> !f.join()).count();
                    if (failedCount > 0) {
                        logger.error("{} players failed to finalize season reset (e.g., setting status to ACTIVE)!", failedCount);
                        return false; // Finalization failed for some
                    }
                    logger.info("Global season reset finalized successfully locally for new season {}.", newSeasonId);
                    return true; // Finalization successful locally
                })
                .exceptionally(e -> {
                    logger.error("Exception during completeGlobalSeasonReset handling", e);
                    return false; // Finalization failed due to exception
                });
    }

    // --- Utility ---
    /** Checks if the player's data is currently in the process of being loaded. */
    public boolean isPlayerDataLoading(UUID playerUUID) { return storage.isPlayerLoading(playerUUID); }
    /** Gets the internal Storage object. Use with caution, mainly for internal plugin access. */
    public Storage getStorage() { return storage; }
}