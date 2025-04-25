package games.rang.jobSkillAPI.model;

import java.util.Objects;

/**
 * Represents a player's level in a specific skill for a given season.
 * This object is immutable.
 */
public class PlayerSkillLevel {
    private final int skillId;
    private final String seasonId;
    private final int level;

    /**
     * Constructor for PlayerSkillLevel.
     * @param skillId The ID of the skill.
     * @param seasonId The season ID this skill level belongs to (non-null).
     * @param level The player's level in this skill for the season (should be >= 0).
     */
    public PlayerSkillLevel(int skillId, String seasonId, int level) {
        if (level < 0) throw new IllegalArgumentException("Skill level cannot be negative.");
        this.skillId = skillId;
        this.seasonId = Objects.requireNonNull(seasonId);
        this.level = level;
    }

    /** Gets the ID of the skill. */
    public int getSkillId() { return skillId; }
    /** Gets the season ID this skill level belongs to. */
    public String getSeasonId() { return seasonId; }
    /** Gets the player's level in this skill for the season. */
    public int getLevel() { return level; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PlayerSkillLevel that = (PlayerSkillLevel) o;
        return skillId == that.skillId &&
                level == that.level &&
                seasonId.equals(that.seasonId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(skillId, seasonId, level);
    }

    @Override
    public String toString() {
        return "SkillLevel{" + "skillId=" + skillId + ", season='" + seasonId + '\'' + ", lvl=" + level + '}';
    }
}