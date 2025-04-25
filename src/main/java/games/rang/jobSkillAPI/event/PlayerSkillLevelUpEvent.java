package games.rang.jobSkillAPI.event;

import games.rang.jobSkillAPI.model.Skill;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Event called when a player successfully levels up a skill.
 */
public class PlayerSkillLevelUpEvent extends Event {
    private static final HandlerList handlers = new HandlerList();
    private final Player player;
    private final Skill skill;
    private final int newLevel;
    private final int oldLevel;
    private final int cost; // Skill points spent for this level up
    private final String reason;

    /**
     * Constructor for PlayerSkillLevelUpEvent.
     * @param player The player who leveled up the skill.
     * @param skill The skill that was leveled up.
     * @param newLevel The new level achieved.
     * @param oldLevel The previous level.
     * @param cost The skill points spent to achieve this level.
     * @param reason The reason or context for the level up.
     */
    public PlayerSkillLevelUpEvent(Player player, Skill skill, int newLevel, int oldLevel, int cost, String reason) {
        this.player = player;
        this.skill = skill;
        this.newLevel = newLevel;
        this.oldLevel = oldLevel;
        this.cost = cost;
        this.reason = reason;
    }

    /** Gets the player involved in this event. */
    public Player getPlayer() { return player; }
    /** Gets the skill that was leveled up. */
    public Skill getSkill() { return skill; }
    /** Gets the new level achieved. */
    public int getNewLevel() { return newLevel; }
    /** Gets the level before the level up occurred. */
    public int getOldLevel() { return oldLevel; }
    /** Gets the cost in skill points for this level up. */
    public int getCost() { return cost; }
    /** Gets the reason provided for the level up. */
    public String getReason() { return reason; }

    @Override
    public HandlerList getHandlers() { return handlers; }
    public static HandlerList getHandlerList() { return handlers; }
}