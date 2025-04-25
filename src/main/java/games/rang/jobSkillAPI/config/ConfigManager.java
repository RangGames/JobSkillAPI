package games.rang.jobSkillAPI.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages plugin configuration loaded from config.yml.
 * Ensures default values are present, loads settings including server info,
 * database credentials, Redis settings, and level requirements, and provides getters.
 */
public class ConfigManager {
    private final JavaPlugin plugin;
    private final String serverName;
    private final String lobbyServerName;
    private final Set<String> apiEnabledServers; // Servers where this plugin manages data
    private final Map<String, List<LevelRequirement>> levelRequirements; // Cache for level requirements

    // Redis settings
    private final boolean redisEnabled;
    private final String redisHost;
    private final int redisPort;
    private final String redisPassword;
    private final int redisDatabase;
    private final String redisChannel;

    /**
     * Represents a specific level and the cumulative experience required to reach it.
     * Used for defining content progression.
     * @param level The target level number.
     * @param experience The cumulative experience required to reach this level.
     */
    public record LevelRequirement(int level, long experience) {}

    /**
     * Constructor for ConfigManager. Loads or creates config.yml, sets defaults,
     * parses configuration values, and caches frequently accessed settings like level requirements.
     * @param plugin The JavaPlugin instance.
     */
    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        plugin.saveDefaultConfig(); // Creates config.yml from resources if it doesn't exist
        FileConfiguration config = plugin.getConfig(); // Load the config

        // --- Define Default Values ---
        config.addDefault("server-name", "MyServer"); // MUST be set correctly for this server
        config.addDefault("lobby-server-name", "lobby");
        config.addDefault("api-enabled-servers", List.of("MyServer", "GameServer1")); // Servers using this plugin's API/data
        config.addDefault("database.host", "localhost");
        config.addDefault("database.port", 3306);
        config.addDefault("database.name", "jobskill_db");
        config.addDefault("database.user", "root");
        config.addDefault("database.password", "your_password"); // MUST be set correctly
        config.addDefault("redis.enabled", false);
        config.addDefault("redis.host", "localhost");
        config.addDefault("redis.port", 6379);
        config.addDefault("redis.password", ""); // Empty string for no password
        config.addDefault("redis.database", 0);
        config.addDefault("redis.channel", "jobskill:season_reset");

        // Default level requirements example
        if (!config.contains("content-level-requirements.default")) {
            Map<String, Object> defaultLevelReqSection = new HashMap<>();
            defaultLevelReqSection.put("default", List.of(
                    Map.of("level", 2, "experience", 100L),
                    Map.of("level", 3, "experience", 250L),
                    Map.of("level", 4, "experience", 500L),
                    Map.of("level", 5, "experience", 1000L)
            ));
            // Add specific content ID example if needed:
            // defaultLevelReqSection.put("1", List.of( Map.of("level", 2, "experience", 150L), ... ));
            config.addDefault("content-level-requirements", defaultLevelReqSection);
        }
        // --- End Default Values ---

        config.options().copyDefaults(true); // Add defaults to config if missing
        plugin.saveConfig(); // Save changes (mainly useful on first run)

        // --- Load Values ---
        this.serverName = config.getString("server-name");
        this.lobbyServerName = config.getString("lobby-server-name");
        this.apiEnabledServers = Collections.unmodifiableSet(new HashSet<>(config.getStringList("api-enabled-servers")));

        this.redisEnabled = config.getBoolean("redis.enabled");
        this.redisHost = config.getString("redis.host");
        this.redisPort = config.getInt("redis.port");
        this.redisPassword = config.getString("redis.password", ""); // Default to empty if not set
        this.redisDatabase = config.getInt("redis.database"); // Correct key used here
        this.redisChannel = config.getString("redis.channel");

        // Load and cache level requirements
        this.levelRequirements = loadLevelRequirementsFromConfig(config);

        // Validation checks
        validateConfiguration();
    }

    /**
     * Loads and parses the 'content-level-requirements' section from the configuration.
     * Handles default requirements and specific content ID overrides. Sorts by experience.
     * @param config The FileConfiguration object.
     * @return An unmodifiable map where keys are content IDs (as strings) or "default",
     *         and values are sorted lists of LevelRequirement records.
     */
    private Map<String, List<LevelRequirement>> loadLevelRequirementsFromConfig(FileConfiguration config) {
        Map<String, List<LevelRequirement>> loadedMap = new HashMap<>();
        ConfigurationSection section = config.getConfigurationSection("content-level-requirements");

        if (section != null) {
            for (String key : section.getKeys(false)) { // Iterate through keys like "default", "1", "mining", etc.
                if (section.isList(key)) {
                    List<Map<?, ?>> rawList = section.getMapList(key);
                    List<LevelRequirement> reqList = rawList.stream()
                            .map(map -> {
                                try {
                                    // Parse level and experience carefully
                                    int level = Integer.parseInt(String.valueOf(map.get("level")));
                                    long experience = Long.parseLong(String.valueOf(map.get("experience")));
                                    if (level > 0 && experience >= 0) { // Basic validation
                                        return new LevelRequirement(level, experience);
                                    }
                                    plugin.getLogger().warning("Invalid level requirement entry: " + map + " for key '" + key + "' (level/exp invalid).");
                                } catch (NumberFormatException | NullPointerException e) {
                                    plugin.getLogger().warning("Error parsing level requirement entry for key '" + key + "': " + map);
                                }
                                return null; // Skip invalid entries
                            })
                            .filter(Objects::nonNull) // Remove entries that failed parsing
                            .sorted(Comparator.comparingLong(LevelRequirement::experience)) // Sort by experience ascending
                            .collect(Collectors.toList());

                    if (!reqList.isEmpty()) {
                        // Ensure level 1 requirement isn't explicitly needed (calculated level starts at 1)
                        if (reqList.stream().anyMatch(r -> r.level() <= 1)) {
                            plugin.getLogger().warning("Level requirements for key '" + key + "' contain level 1 or lower, which is implicit. Please start requirements from level 2.");
                        }
                        loadedMap.put(key, reqList);
                    } else if (!rawList.isEmpty()){
                        plugin.getLogger().warning("All level requirement entries were invalid for key '" + key + "'.");
                    }

                } else {
                    plugin.getLogger().warning("Expected a list for level requirements under key '" + key + "', but found type: " + section.get(key).getClass().getSimpleName());
                }
            }
            plugin.getLogger().info("Loaded level requirements for " + loadedMap.size() + " content types/defaults.");
        } else {
            plugin.getLogger().warning("Configuration section 'content-level-requirements' not found!");
        }
        // Ensure 'default' exists, even if empty, to prevent NullPointerExceptions later
        loadedMap.putIfAbsent("default", Collections.emptyList());
        return Collections.unmodifiableMap(loadedMap);
    }

    /**
     * Performs basic validation checks on critical configuration values and logs warnings/errors.
     */
    private void validateConfiguration() {
        if (this.serverName == null || this.serverName.isEmpty() || this.serverName.equalsIgnoreCase("MyServer")) {
            plugin.getLogger().severe("CRITICAL: 'server-name' in config.yml is not set correctly! Please set it to this server's unique BungeeCord name.");
        }
        if (this.getDatabasePassword().equals("your_password")) {
            plugin.getLogger().severe("CRITICAL: 'database.password' in config.yml is still the default value! Please set a secure password.");
        }
        if (this.apiEnabledServers.isEmpty()) {
            plugin.getLogger().warning("'api-enabled-servers' list in config.yml is empty. This plugin might not function as expected in a network environment.");
        } else if (!isApiEnabledServer(this.serverName) && !isLobbyServer()) {
            plugin.getLogger().warning("This server ('" + this.serverName + "') is not listed in 'api-enabled-servers' and is not the lobby server. Data synchronization might be affected.");
        }
        if (!levelRequirements.containsKey("default") || levelRequirements.get("default").isEmpty()){
            plugin.getLogger().warning("Missing or empty 'default' level requirements in config.yml. Level calculation might not work correctly for all content types.");
        }
    }

    /**
     * Gets the list of level requirements for a specific content ID.
     * If requirements for the specific ID are not defined, it falls back to the "default" requirements.
     * The returned list is sorted by experience ascending.
     * @param contentId The ID of the content type.
     * @return An unmodifiable, sorted list of LevelRequirement records, or an empty list if none are defined.
     */
    public List<LevelRequirement> getLevelRequirements(int contentId) {
        // Return specific requirements if present, otherwise fall back to default
        return levelRequirements.getOrDefault(String.valueOf(contentId),
                levelRequirements.getOrDefault("default", Collections.emptyList()));
    }

    /** Checks if Redis integration is enabled in the config. */
    public boolean isRedisEnabled() { return redisEnabled; }
    /** Gets the Redis server hostname or IP address. */
    public String getRedisHost() { return redisHost; }
    /** Gets the Redis server port. */
    public int getRedisPort() { return redisPort; }
    /** Gets the Redis password. Returns null if the password is empty or not set. */
    public String getRedisPassword() { return (redisPassword == null || redisPassword.isEmpty()) ? null : redisPassword; }
    /** Gets the Redis database index to use. */
    public int getRedisDatabase() { return redisDatabase; }
    /** Gets the Redis channel used for Pub/Sub communication (e.g., season resets). */
    public String getRedisChannel() { return redisChannel; }

    /**
     * Checks if the given server name is configured as an API-enabled server
     * (meaning this plugin should manage data for players on that server). Case-insensitive check.
     * @param serverName The name of the server to check.
     * @return true if the server is in the api-enabled-servers list, false otherwise.
     */
    public boolean isApiEnabledServer(String serverName) {
        if (serverName == null) return false;
        // Perform case-insensitive check for robustness
        return apiEnabledServers.stream().anyMatch(s -> s.equalsIgnoreCase(serverName));
    }

    /** Gets the unmodifiable set of all server names configured as API-enabled. */
    public Set<String> getApiEnabledServers() { return apiEnabledServers; }
    /** Gets the configured name of this server instance. */
    public String getServerName() { return serverName; }
    /** Gets the configured name of the lobby server. */
    public String getLobbyServerName() { return lobbyServerName; }
    /** Checks if this server instance is configured as the lobby server (case-insensitive). */
    public boolean isLobbyServer() { return serverName.equalsIgnoreCase(lobbyServerName); }
    /** Gets the database hostname. */
    public String getDatabaseHost() { return plugin.getConfig().getString("database.host"); }
    /** Gets the database port. */
    public int getDatabasePort() { return plugin.getConfig().getInt("database.port"); }
    /** Gets the database name. */
    public String getDatabaseName() { return plugin.getConfig().getString("database.name"); }
    /** Gets the database username. */
    public String getDatabaseUser() { return plugin.getConfig().getString("database.user"); }
    /** Gets the database password. */
    public String getDatabasePassword() { return plugin.getConfig().getString("database.password"); }
}