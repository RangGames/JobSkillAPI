package games.rang.jobSkillAPI.data;

import games.rang.jobSkillAPI.config.ConfigManager;
import games.rang.jobSkillAPI.log.TransactionLogger;
import games.rang.jobSkillAPI.model.ContentType;
import games.rang.jobSkillAPI.model.Job;
import games.rang.jobSkillAPI.model.PlayerSeasonData;
import games.rang.jobSkillAPI.model.Skill;
import games.rang.jobSkillAPI.storage.DatabaseHandler;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages static game data definitions (like ContentTypes, Jobs, Skills)
 * which are typically loaded once at startup from the database or configuration.
 * Provides methods to access this data and helper methods for game logic.
 */
public class StaticDataManager {
    private final Map<Integer, ContentType> contentTypes = new ConcurrentHashMap<>();
    private final Map<Integer, Job> jobs = new ConcurrentHashMap<>();
    private final Map<Integer, Skill> skills = new ConcurrentHashMap<>();
    private final TransactionLogger logger;
    private final DatabaseHandler databaseHandler;
    private final ConfigManager configManager; // Used for level requirement calculations
    private volatile boolean staticDataLoaded = false; // Flag indicating successful load

    /**
     * Constructor for StaticDataManager.
     * @param logger The transaction logger instance.
     * @param databaseHandler The database handler instance for loading data.
     * @param configManager The configuration manager instance.
     */
    public StaticDataManager(TransactionLogger logger, DatabaseHandler databaseHandler, ConfigManager configManager) {
        this.logger = logger;
        this.databaseHandler = databaseHandler;
        this.configManager = configManager;
    }

    /**
     * Loads static data (ContentTypes, Jobs, Skills) from the database asynchronously.
     * This is typically called during plugin startup. Sets the `staticDataLoaded` flag upon completion.
     * @return A CompletableFuture indicating whether all static data types were loaded successfully (true) or if any failed (false).
     */
    public CompletableFuture<Boolean> loadStaticData() {
        staticDataLoaded = false; // Reset flag on load attempt
        logger.info("Loading static game data (ContentTypes, Jobs, Skills)...");

        CompletableFuture<Boolean> contentFuture = databaseHandler.loadContentTypesFromDB()
                .thenApply(loadedTypes -> {
                    contentTypes.clear();
                    loadedTypes.forEach(ct -> contentTypes.put(ct.getId(), ct));
                    logger.info("Loaded {} content types.", contentTypes.size());
                    return true;
                }).exceptionally(e -> {
                    logger.error("Failed to load content types", e); return false;
                });

        CompletableFuture<Boolean> jobFuture = databaseHandler.loadJobsFromDB()
                .thenApply(loadedJobs -> {
                    jobs.clear();
                    loadedJobs.forEach(job -> jobs.put(job.getId(), job));
                    logger.info("Loaded {} jobs.", jobs.size());
                    return true;
                }).exceptionally(e -> {
                    logger.error("Failed to load jobs", e); return false;
                });

        CompletableFuture<Boolean> skillFuture = databaseHandler.loadSkillsFromDB()
                .thenApply(loadedSkills -> {
                    skills.clear();
                    loadedSkills.forEach(skill -> skills.put(skill.getId(), skill));
                    logger.info("Loaded {} skills.", skills.size());
                    return true;
                }).exceptionally(e -> {
                    logger.error("Failed to load skills", e); return false;
                });

        return CompletableFuture.allOf(contentFuture, jobFuture, skillFuture)
                .thenApply(v -> {
                    boolean success = contentFuture.join() && jobFuture.join() && skillFuture.join();
                    staticDataLoaded = success; // Set flag based on overall success
                    return success;
                })
                .whenComplete((success, throwable) -> {
                    if (Boolean.TRUE.equals(success)) {
                        logger.info("Static game data loaded successfully.");
                    } else {
                        logger.error("Failed to load some or all static game data!");
                        if (throwable != null) {
                            logger.error("Static data loading exception:", throwable);
                        }
                    }
                });
    }

    /** Gets a ContentType definition by its ID. */
    public Optional<ContentType> getContentType(int id) {
        return Optional.ofNullable(contentTypes.get(id));
    }
    /** Checks if the static data (types, jobs, skills) has been successfully loaded. */
    public boolean isStaticDataLoaded() {
        return staticDataLoaded;
    }
    /** Gets a Job definition by its ID. */
    public Optional<Job> getJob(int id) {
        return Optional.ofNullable(jobs.get(id));
    }
    /** Gets a Skill definition by its ID. */
    public Optional<Skill> getSkill(int id) {
        return Optional.ofNullable(skills.get(id));
    }
    /** Gets an unmodifiable collection of all defined ContentTypes. */
    public Collection<ContentType> getAllContentTypes() {
        return Collections.unmodifiableCollection(contentTypes.values());
    }
    /** Gets an unmodifiable collection of all defined Jobs. */
    public Collection<Job> getAllJobs() {
        return Collections.unmodifiableCollection(jobs.values());
    }
    /** Gets an unmodifiable collection of all defined Skills. */
    public Collection<Skill> getAllSkills() {
        return Collections.unmodifiableCollection(skills.values());
    }
    /** Gets an unmodifiable list of all Skills associated with a specific Job ID. */
    public List<Skill> getSkillsForJob(int jobId) {
        return skills.values().stream()
                .filter(skill -> skill.getAssociatedJobId() == jobId)
                .collect(Collectors.toUnmodifiableList());
    }

    /**
     * Calculates the level corresponding to the given experience for a specific content type,
     * based on the level requirements defined in the configuration.
     * @param contentId The ID of the content type.
     * @param experience The total experience.
     * @return The calculated level (starts at 1).
     */
    public int calculateLevel(int contentId, long experience) {
        List<ConfigManager.LevelRequirement> requirements = configManager.getLevelRequirements(contentId);
        int calculatedLevel = 1; // Default level is 1

        // Assumes requirements are sorted by experience ascending
        for (ConfigManager.LevelRequirement req : requirements) {
            if (experience >= req.experience()) {
                calculatedLevel = req.level();
            } else {
                break; // Stop checking once experience is too low for the next level
            }
        }
        return calculatedLevel;
    }

    /**
     * Calculates the cost (e.g., skill points) required to level up a skill to the next level.
     * Placeholder implementation - needs specific game logic.
     * @param skillId The ID of the skill.
     * @param nextLevel The target level (current level + 1).
     * @return The calculated cost.
     * @deprecated Placeholder cost calculation. Implement specific logic or pass cost externally.
     */
    @Deprecated
    public int getSkillLevelUpCost(int skillId, int nextLevel) {
        logger.debug("Placeholder: Calculating skill level up cost for skill {} to level {}. External implementation needed.", skillId, nextLevel);
        return Math.max(1, nextLevel * 5); // Placeholder: Cost increases linearly
    }

    /**
     * Gets the maximum level defined for a specific skill.
     * @param skillId The ID of the skill.
     * @return The maximum level, or 0 if the skill is not found.
     */
    public int getMaxSkillLevel(int skillId) {
        return getSkill(skillId).map(Skill::getMaxLevel).orElse(0);
    }

    /**
     * Checks if a specific skill belongs to the given job ID.
     * @param skillId The ID of the skill.
     * @param jobId The ID of the job.
     * @return true if the skill is associated with the job, false otherwise or if skill not found.
     */
    public boolean isSkillForJob(int skillId, int jobId) {
        return getSkill(skillId).map(s -> s.getAssociatedJobId() == jobId).orElse(false);
    }

    /**
     * Checks if a player meets the requirements (e.g., content level) to select a specific job.
     * @param playerData The player's current seasonal data.
     * @param jobId The ID of the job to check.
     * @return true if the player can select the job, false otherwise or if job not found.
     */
    public boolean canSelectJob(PlayerSeasonData playerData, int jobId) {
        Optional<Job> jobOpt = getJob(jobId);
        if (jobOpt.isEmpty()) return false;

        Job job = jobOpt.get();
        // Check content requirement if it exists
        if (job.getRequiredContentId() != null) {
            int currentLevel = playerData.getContentLevel(job.getRequiredContentId());
            return currentLevel >= job.getRequiredContentLevel();
        }
        return true; // No requirements defined, selectable by default
    }
}