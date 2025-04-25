package games.rang.jobSkillAPI.model;

import java.util.Objects;

/**
 * Represents a Job definition, including its ID, name, description,
 * and optional requirements (content type and level). This object is immutable.
 */
public class Job {
    private final int id;
    private final String name;
    private final String description;
    private final Integer requiredContentId; // Nullable ID of the required content type
    private final int requiredContentLevel; // Minimum level required in the content type

    /**
     * Constructor for Job.
     * @param id The unique ID of the job.
     * @param name The name of the job (non-null).
     * @param description A description of the job (nullable).
     * @param requiredContentId The ID of the content type required to select this job (nullable).
     * @param requiredContentLevel The minimum level required in the specified content type (defaults to 1 if not specified in DB).
     */
    public Job(int id, String name, String description, Integer requiredContentId, int requiredContentLevel) {
        if (requiredContentLevel < 1) throw new IllegalArgumentException("Required content level cannot be less than 1.");
        this.id = id;
        this.name = Objects.requireNonNull(name, "Job name cannot be null");
        this.description = description;
        this.requiredContentId = requiredContentId;
        this.requiredContentLevel = requiredContentLevel;
    }

    /** Gets the unique ID of this job. */
    public int getId() { return id; }
    /** Gets the name of this job. */
    public String getName() { return name; }
    /** Gets the description of this job. */
    public String getDescription() { return description; }
    /** Gets the ID of the content type required to select this job, if any. */
    public Integer getRequiredContentId() { return requiredContentId; }
    /** Gets the minimum level required in the prerequisite content type. */
    public int getRequiredContentLevel() { return requiredContentLevel; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Job job = (Job) o;
        return id == job.id; // Equality based solely on ID
    }

    @Override
    public int hashCode() {
        return Objects.hash(id); // Hash code based solely on ID
    }

    @Override
    public String toString() {
        return "Job{" + "id=" + id + ", name='" + name + '\'' + '}';
    }
}