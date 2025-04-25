package games.rang.jobSkillAPI.event;

import games.rang.jobSkillAPI.model.ContentType;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Event called when a player's level in a specific content type increases.
 */
public class PlayerContentLevelUpEvent extends Event {
    private static final HandlerList handlers = new HandlerList();
    private final Player player;
    private final ContentType contentType;
    private final int newLevel;
    private final int oldLevel;
    private final String reason;

    /**
     * Constructor for PlayerContentLevelUpEvent.
     * @param player The player whose content level increased.
     * @param contentType The content type that leveled up.
     * @param newLevel The new level achieved.
     * @param oldLevel The previous level.
     * @param reason The reason for the level up (e.g., "Experience Gain").
     */
    public PlayerContentLevelUpEvent(Player player, ContentType contentType, int newLevel, int oldLevel, String reason) {
        this.player = player;
        this.contentType = contentType;
        this.newLevel = newLevel;
        this.oldLevel = oldLevel;
        this.reason = reason;
    }

    /** Gets the player involved in this event. */
    public Player getPlayer() { return player; }
    /** Gets the content type that leveled up. */
    public ContentType getContentType() { return contentType; }
    /** Gets the new level achieved. */
    public int getNewLevel() { return newLevel; }
    /** Gets the level before the level up occurred. */
    public int getOldLevel() { return oldLevel; }
    /** Gets the reason provided for the level up. */
    public String getReason() { return reason; }

    @Override
    public HandlerList getHandlers() { return handlers; }
    public static HandlerList getHandlerList() { return handlers; }
}