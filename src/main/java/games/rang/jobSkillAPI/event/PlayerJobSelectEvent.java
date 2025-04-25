package games.rang.jobSkillAPI.event;

import games.rang.jobSkillAPI.model.Job;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import javax.annotation.Nullable;

/**
 * Event called when a player successfully selects a job.
 * This event is called after the selection is processed and saved to the cache.
 */
public class PlayerJobSelectEvent extends Event /* implements Cancellable */ {
    private static final HandlerList handlers = new HandlerList();
    private final Player player;
    private final Job newJob;
    private final Job oldJob; // Nullable, if the player didn't have a job previously.
    private final String reason;
    // private boolean cancelled;

    /**
     * Constructor for PlayerJobSelectEvent.
     * @param player The player who selected the job.
     * @param newJob The newly selected job.
     * @param oldJob The job the player had before selecting the new one (nullable).
     * @param reason The reason or context for the job selection.
     */
    public PlayerJobSelectEvent(Player player, Job newJob, @Nullable Job oldJob, String reason) {
        this.player = player;
        this.newJob = newJob;
        this.oldJob = oldJob;
        this.reason = reason;
        // this.cancelled = false;
    }

    /** Gets the player involved in this event. */
    public Player getPlayer() { return player; }
    /** Gets the job that was selected. */
    public Job getNewJob() { return newJob; }
    /** Gets the job the player had before this selection, or null if none. */
    @Nullable public Job getOldJob() { return oldJob; }
    /** Gets the reason provided for the job selection. */
    public String getReason() { return reason; }

    @Override
    public HandlerList getHandlers() { return handlers; }
    public static HandlerList getHandlerList() { return handlers; }

    /* If implementing Cancellable:
    @Override
    public boolean isCancelled() { return cancelled; }
    @Override
    public void setCancelled(boolean cancel) { this.cancelled = cancel; }
    */
}