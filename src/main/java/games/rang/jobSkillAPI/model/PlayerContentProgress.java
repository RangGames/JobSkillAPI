package games.rang.jobSkillAPI.model;

import java.util.Objects;

/**
 * Represents a player's progress (experience and level) in a specific
 * content type for a given season. This object is immutable.
 */
public class PlayerContentProgress {
    private final int contentId;
    private final String seasonId;
    private final long experience;
    private final int level;

    /**
     * Constructor for PlayerContentProgress.
     * @param contentId The ID of the content type.
     * @param seasonId The season ID this progress belongs to (non-null).
     * @param experience The total experience gained in this content for the season.
     * @param level The calculated level based on the experience for the season.
     */
    public PlayerContentProgress(int contentId, String seasonId, long experience, int level) {
        this.contentId = contentId;
        this.seasonId = Objects.requireNonNull(seasonId);
        this.experience = experience;
        this.level = level;
    }

    /** Gets the ID of the content type. */
    public int getContentId() { return contentId; }
    /** Gets the season ID this progress belongs to. */
    public String getSeasonId() { return seasonId; }
    /** Gets the total experience gained in this content for the season. */
    public long getExperience() { return experience; }
    /** Gets the calculated level for this content in the season. */
    public int getLevel() { return level; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PlayerContentProgress that = (PlayerContentProgress) o;
        return contentId == that.contentId &&
                experience == that.experience &&
                level == that.level &&
                seasonId.equals(that.seasonId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(contentId, seasonId, experience, level);
    }

    @Override
    public String toString() {
        return "ContentProgress{" + "contentId=" + contentId + ", season='" + seasonId + '\'' + ", exp=" + experience + ", lvl=" + level + '}';
    }
}