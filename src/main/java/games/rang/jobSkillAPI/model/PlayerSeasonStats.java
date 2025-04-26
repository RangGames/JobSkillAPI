package games.rang.jobSkillAPI.model;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Represents basic seasonal statistics for a player, specifically their
 * chosen job ID and total skill points for the season. This object is immutable.
 */
public class PlayerSeasonStats {
    private final UUID playerUuid;
    private final String seasonId;
    private final Integer chosenJobId; // Nullable if no job is chosen
    private final int skillPoints;
    private final int classValue;
    /**
     * Constructor for PlayerSeasonStats.
     * @param playerUuid The player's UUID (non-null).
     * @param seasonId The season ID this stats object belongs to (non-null).
     * @param chosenJobId The ID of the job chosen by the player (nullable).
     * @param skillPoints The total skill points the player has in this season (should be >= 0).
     */
    public PlayerSeasonStats(UUID playerUuid, String seasonId, Integer chosenJobId, int skillPoints, int classValue) {
        if (skillPoints < 0) throw new IllegalArgumentException("Skill points cannot be negative.");
        this.playerUuid = Objects.requireNonNull(playerUuid);
        this.seasonId = Objects.requireNonNull(seasonId);
        this.chosenJobId = chosenJobId;
        this.skillPoints = skillPoints;
        this.classValue = classValue;
    }

    /** Gets the player's UUID. */
    public UUID getPlayerUuid() { return playerUuid; }
    /** Gets the season ID this stats object belongs to. */
    public String getSeasonId() { return seasonId; }
    /** Gets the ID of the chosen job, if any. */
    public Optional<Integer> getChosenJobId() { return Optional.ofNullable(chosenJobId); }
    /** Gets the total skill points the player has in this season. */
    public int getSkillPoints() { return skillPoints; }
    /** Gets the class of skill of player */
    public int getClassValue() { return classValue; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PlayerSeasonStats that = (PlayerSeasonStats) o;
        return skillPoints == that.skillPoints &&
                classValue == that.classValue &&
                playerUuid.equals(that.playerUuid) &&
                seasonId.equals(that.seasonId) &&
                Objects.equals(chosenJobId, that.chosenJobId); // Handles null comparison for jobId
    }

    @Override
    public int hashCode() {
        return Objects.hash(playerUuid, seasonId, chosenJobId, skillPoints, classValue);
    }

    @Override
    public String toString() {
        return "SeasonStats{" + "uuid=" + playerUuid + ", season='" + seasonId + '\'' + ", job=" + chosenJobId + ", sp=" + skillPoints + ", class=" + classValue + '}';
    }
}