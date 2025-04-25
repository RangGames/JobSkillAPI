package games.rang.jobSkillAPI.model;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Collections;

/**
 * Represents all seasonal data for a specific player, including their stats (job, skill points),
 * content progress (experience, level per content type), and learned skill levels.
 * This object acts as a cache entry and tracks modification status (dirty flag).
 * Modifications should generally happen through the Storage class methods.
 */
public class PlayerSeasonData {
    private final UUID playerUUID;
    private final String seasonId;
    private volatile PlayerSeasonStats seasonStats; // Job, Skill Points (Mutable reference)
    private final Map<Integer, PlayerContentProgress> contentProgress; // ContentID -> Progress
    private final Map<Integer, PlayerSkillLevel> skillLevels; // SkillID -> Level
    private volatile boolean dirty; // Flag indicating if data needs saving
    private volatile long lastUpdate; // Timestamp of last modification

    /**
     * Constructor for PlayerSeasonData. Initializes maps and sets initial state.
     * @param playerUUID The player's UUID (non-null).
     * @param seasonId The season ID this data belongs to (non-null).
     */
    public PlayerSeasonData(UUID playerUUID, String seasonId) {
        this.playerUUID = Objects.requireNonNull(playerUUID);
        this.seasonId = Objects.requireNonNull(seasonId);
        this.contentProgress = new ConcurrentHashMap<>();
        this.skillLevels = new ConcurrentHashMap<>();
        this.dirty = false; // Initial state is clean (loaded from DB or default)
        this.lastUpdate = System.currentTimeMillis();
        // seasonStats is typically set during the data loading process
    }

    /** Gets the player's UUID. */
    public UUID getPlayerUUID() { return playerUUID; }
    /** Gets the season ID this data pertains to. */
    public String getSeasonId() { return seasonId; }
    /** Gets the player's seasonal stats (job, skill points) if available. */
    public Optional<PlayerSeasonStats> getSeasonStats() { return Optional.ofNullable(seasonStats); }
    /** Returns an unmodifiable view of the content progress map (ContentID -> Progress). */
    public Map<Integer, PlayerContentProgress> getContentProgressMap() { return Collections.unmodifiableMap(contentProgress); }
    /** Returns an unmodifiable view of the skill level map (SkillID -> Level). */
    public Map<Integer, PlayerSkillLevel> getSkillLevelMap() { return Collections.unmodifiableMap(skillLevels); }
    /** Checks if the data has been modified since the last save/load. */
    public boolean isDirty() { return dirty; }
    /** Gets the timestamp of the last update to this data object. */
    public long getLastUpdate() { return lastUpdate; }

    /** Gets the content progress for a specific content ID, if it exists. */
    public Optional<PlayerContentProgress> getProgress(int contentId) {
        return Optional.ofNullable(contentProgress.get(contentId));
    }
    /** Gets the level for a specific content ID. Defaults to 1 if no progress exists. */
    public int getContentLevel(int contentId) {
        return getProgress(contentId).map(PlayerContentProgress::getLevel).orElse(1);
    }
    /** Gets the experience for a specific content ID. Defaults to 0 if no progress exists. */
    public long getContentExperience(int contentId) {
        return getProgress(contentId).map(PlayerContentProgress::getExperience).orElse(0L);
    }
    /** Gets the skill level DTO for a specific skill ID, if learned. */
    public Optional<PlayerSkillLevel> getSkill(int skillId) {
        return Optional.ofNullable(skillLevels.get(skillId));
    }
    /** Gets the level for a specific skill ID. Defaults to 0 if not learned. */
    public int getSkillLevel(int skillId) {
        return getSkill(skillId).map(PlayerSkillLevel::getLevel).orElse(0);
    }
    /** Gets the ID of the chosen job, if any. */
    public Optional<Integer> getChosenJobId() {
        return getSeasonStats().flatMap(PlayerSeasonStats::getChosenJobId);
    }
    /** Gets the current skill points. Defaults to 0 if stats are missing. */
    public int getSkillPoints() {
        return getSeasonStats().map(PlayerSeasonStats::getSkillPoints).orElse(0);
    }

    /**
     * Sets or updates the player's seasonal stats (job, skill points). Marks data as dirty if changed.
     * Should be called internally by Storage.
     * @param newStats The new PlayerSeasonStats object.
     */
    public void setSeasonStats(PlayerSeasonStats newStats) {
        if (!Objects.equals(this.seasonStats, newStats)) {
            this.seasonStats = newStats; // Replace with the new immutable stats object
            markDirty();
        }
    }

    /**
     * Sets or updates the progress for a specific content type. Marks data as dirty if changed.
     * Should be called internally by Storage.
     * @param newProgress The new PlayerContentProgress object (non-null).
     */
    public void setContentProgress(PlayerContentProgress newProgress) {
        Objects.requireNonNull(newProgress, "Content progress cannot be null");
        PlayerContentProgress oldProgress = this.contentProgress.put(newProgress.getContentId(), newProgress);
        if (!Objects.equals(oldProgress, newProgress)) { // Mark dirty only if value actually changed
            markDirty();
        }
    }

    /**
     * Sets or updates the level for a specific skill. If the new level is 0, the skill is removed
     * from the map. Marks data as dirty if the level changes or the skill is removed/added.
     * Should be called internally by Storage.
     * @param newSkillLevel The new PlayerSkillLevel object (non-null).
     */
    public void setSkillLevel(PlayerSkillLevel newSkillLevel) {
        Objects.requireNonNull(newSkillLevel, "Skill level cannot be null");
        int skillId = newSkillLevel.getSkillId();
        PlayerSkillLevel oldSkillLevel = skillLevels.get(skillId);

        boolean changed = false;
        if (newSkillLevel.getLevel() > 0) {
            PlayerSkillLevel previous = skillLevels.put(skillId, newSkillLevel); // Update or add
            changed = !Objects.equals(previous, newSkillLevel);
        } else {
            // Remove the skill from the map if level becomes 0
            PlayerSkillLevel removed = skillLevels.remove(skillId);
            changed = removed != null; // Mark dirty only if a skill was actually removed
        }

        if (changed) {
            markDirty();
        }
    }

    /**
     * Marks this data object as modified (dirty) and updates the last update timestamp.
     */
    public void markDirty() {
        this.dirty = true;
        this.lastUpdate = System.currentTimeMillis();
    }

    /**
     * Clears the dirty flag, typically after successfully saving the data.
     */
    public void clearDirty() {
        this.dirty = false;
    }

    @Override
    public String toString() {
        return "PlayerSeasonData{" +
                "playerUUID=" + playerUUID +
                ", seasonId='" + seasonId + '\'' +
                ", stats=" + seasonStats +
                ", progressCount=" + contentProgress.size() +
                ", skillCount=" + skillLevels.size() +
                ", dirty=" + dirty +
                ", lastUpdate=" + lastUpdate +
                '}';
    }
}