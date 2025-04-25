package games.rang.jobSkillAPI.event;

import games.rang.jobSkillAPI.model.Job;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import javax.annotation.Nullable;

/**
 * Event called when a player's job and related data (skill points, skill levels)
 * for the current season are reset via the API.
 */
public class PlayerJobResetEvent extends Event {
    private static final HandlerList handlers = new HandlerList();
    private final Player player;
    private final Job oldJob; // Nullable, as the player might not have had a job.
    private final String reason;

    /**
     * Constructor for PlayerJobResetEvent.
     * @param player The player whose job data was reset.
     * @param oldJob The job the player had before the reset (nullable).
     * @param reason The reason for the reset.
     */
    public PlayerJobResetEvent(Player player, @Nullable Job oldJob, String reason) {
        this.player = player;
        this.oldJob = oldJob;
        this.reason = reason;
    }

    /** Gets the player involved in this event. */
    public Player getPlayer() { return player; }
    /** Gets the job the player had before the reset, or null if none. */
    @Nullable public Job getOldJob() { return oldJob; }
    /** Gets the reason provided for the job reset. */
    public String getReason() { return reason; }

    @Override
    public HandlerList getHandlers() { return handlers; }
    public static HandlerList getHandlerList() { return handlers; }
}