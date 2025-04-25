package games.rang.jobSkillAPI.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Event called when a player's data for a specific season is completely reset (deleted)
 * via the API. Note: This is distinct from a global season change/rollover.
 */
public class PlayerSeasonResetEvent extends Event {
    private static final HandlerList handlers = new HandlerList();
    private final Player player;
    private final String seasonId; // The ID of the season that was reset
    private final String reason;

    /**
     * Constructor for PlayerSeasonResetEvent.
     * @param player The player whose seasonal data was reset.
     * @param seasonId The ID of the season for which data was reset.
     * @param reason The reason provided for the reset.
     */
    public PlayerSeasonResetEvent(Player player, String seasonId, String reason) {
        this.player = player;
        this.seasonId = seasonId;
        this.reason = reason;
    }

    /** Gets the player involved in this event. */
    public Player getPlayer() { return player; }
    /** Gets the ID of the season that was reset. */
    public String getSeasonId() { return seasonId; }
    /** Gets the reason provided for the season data reset. */
    public String getReason() { return reason; }

    @Override
    public HandlerList getHandlers() { return handlers; }
    public static HandlerList getHandlerList() { return handlers; }
}