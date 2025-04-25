package games.rang.jobSkillAPI.model;

import java.util.Objects;

/**
 * Represents a Skill definition, including its ID, name, description,
 * maximum level, and the ID of the Job it is associated with.
 * This object is immutable.
 */
public class Skill {
    private final int id;
    private final String name;
    private final String description;
    private final int maxLevel;
    private final int associatedJobId;

    /**
     * Constructor for Skill.
     * @param id The unique ID of the skill.
     * @param name The name of the skill (non-null).
     * @param description A description of the skill (nullable).
     * @param maxLevel The maximum level this skill can reach (should be > 0).
     * @param associatedJobId The ID of the job this skill belongs to.
     */
    public Skill(int id, String name, String description, int maxLevel, int associatedJobId) {
        if (maxLevel <= 0) throw new IllegalArgumentException("Max level must be positive.");
        this.id = id;
        this.name = Objects.requireNonNull(name, "Skill name cannot be null");
        this.description = description;
        this.maxLevel = maxLevel;
        this.associatedJobId = associatedJobId;
    }

    /** Gets the unique ID of the skill. */
    public int getId() { return id; }
    /** Gets the name of the skill. */
    public String getName() { return name; }
    /** Gets the description of the skill. */
    public String getDescription() { return description; }
    /** Gets the maximum level attainable for this skill. */
    public int getMaxLevel() { return maxLevel; }
    /** Gets the ID of the job this skill is associated with. */
    public int getAssociatedJobId() { return associatedJobId; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Skill skill = (Skill) o;
        return id == skill.id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Skill{" + "id=" + id + ", name='" + name + '\'' + ", jobId=" + associatedJobId + '}';
    }
}