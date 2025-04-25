package games.rang.jobSkillAPI.event;

import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Event called when a player's skill points are changed (added or spent).
 */
public class PlayerSkillPointsChangeEvent extends Event {
    private static final HandlerList handlers = new HandlerList();
    private final Player player;
    private final int newPoints;
    private final int oldPoints;
    private final String reason;

    /**
     * Constructor for PlayerSkillPointsChangeEvent.
     * @param player The player whose skill points changed.
     * @param newPoints The new total skill points.
     * @param oldPoints The previous total skill points.
     * @param reason The reason for the change (e.g., "Level Up", "Skill Cost").
     */
    public PlayerSkillPointsChangeEvent(Player player, int newPoints, int oldPoints, String reason) {
        this.player = player;
        this.newPoints = newPoints;
        this.oldPoints = oldPoints;
        this.reason = reason;
    }

    /** Gets the player involved in this event. */
    public Player getPlayer() { return player; }
    /** Gets the new total skill points after the change. */
    public int getNewPoints() { return newPoints; }
    /** Gets the total skill points before the change occurred. */
    public int getOldPoints() { return oldPoints; }
    /** Gets the change in skill points (positive for gain, negative for cost). */
    public int getChange() { return newPoints - oldPoints; }
    /** Gets the reason provided for the skill point change. */
    public String getReason() { return reason; }

    @Override
    public HandlerList getHandlers() { return handlers; }
    public static HandlerList getHandlerList() { return handlers; }
}