package games.rang.jobSkillAPI.model;

import java.util.Objects;

/**
 * Represents a type of content within the game (e.g., Mining, Fishing, Combat)
 * that players can gain experience and levels in. This object is immutable.
 */
public class ContentType {
    private final int id;
    private final String name;
    private final String description;

    /**
     * Constructor for ContentType.
     * @param id The unique ID for this content type.
     * @param name The name of the content type (non-null).
     * @param description A description of the content type (nullable).
     */
    public ContentType(int id, String name, String description) {
        this.id = id;
        this.name = Objects.requireNonNull(name, "Content name cannot be null");
        this.description = description;
    }

    /** Gets the unique ID of this content type. */
    public int getId() { return id; }
    /** Gets the name of this content type. */
    public String getName() { return name; }
    /** Gets the description of this content type. */
    public String getDescription() { return description; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ContentType that = (ContentType) o;
        return id == that.id; // Equality based solely on ID
    }

    @Override
    public int hashCode() {
        return Objects.hash(id); // Hash code based solely on ID
    }

    @Override
    public String toString() {
        return "ContentType{" + "id=" + id + ", name='" + name + '\'' + '}';
    }
}