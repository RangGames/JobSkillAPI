package games.rang.jobSkillAPI.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import games.rang.jobSkillAPI.config.ConfigManager;
import games.rang.jobSkillAPI.log.TransactionLogger;
import games.rang.jobSkillAPI.model.*;

import java.sql.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Handles database interactions, connection pooling using HikariCP, and executing SQL queries
 * for the JobSkillAPI plugin. Implements AutoCloseable for resource management.
 */
public class DatabaseHandler implements AutoCloseable {
    private final HikariDataSource dataSource;
    private final TransactionLogger logger;
    private final ConfigManager config;

    // --- SQL Table Creation Queries ---
    private static final String CREATE_CONTENT_TYPES_TABLE = """
        CREATE TABLE IF NOT EXISTS `Content_Types` (
            `content_id` INT AUTO_INCREMENT PRIMARY KEY,
            `content_name` VARCHAR(100) NOT NULL UNIQUE,
            `description` TEXT NULL
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
        """;
    private static final String CREATE_JOBS_TABLE = """
        CREATE TABLE IF NOT EXISTS `Jobs` (
            `job_id` INT AUTO_INCREMENT PRIMARY KEY,
            `job_name` VARCHAR(100) NOT NULL UNIQUE,
            `description` TEXT NULL,
            `required_content_id` INT NULL,
            `required_content_level` INT NOT NULL DEFAULT 1,
            FOREIGN KEY (`required_content_id`) REFERENCES `Content_Types`(`content_id`) ON DELETE SET NULL ON UPDATE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
        """;
    private static final String CREATE_SKILLS_TABLE = """
        CREATE TABLE IF NOT EXISTS `Skills` (
            `skill_id` INT AUTO_INCREMENT PRIMARY KEY,
            `skill_name` VARCHAR(100) NOT NULL UNIQUE,
            `description` TEXT NULL,
            `max_level` INT NOT NULL DEFAULT 10,
            `associated_job_id` INT NOT NULL,
            FOREIGN KEY (`associated_job_id`) REFERENCES `Jobs`(`job_id`) ON DELETE CASCADE ON UPDATE CASCADE
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
        """;
    private static final String CREATE_PLAYER_CONTENT_PROGRESS_TABLE = """
        CREATE TABLE IF NOT EXISTS `Player_Content_Progress` (
            `player_uuid` VARCHAR(36) NOT NULL,
            `content_id` INT NOT NULL,
            `season_id` VARCHAR(10) NOT NULL,
            `experience` BIGINT NOT NULL DEFAULT 0,
            `level` INT NOT NULL DEFAULT 1,
            PRIMARY KEY (`player_uuid`, `content_id`, `season_id`),
            FOREIGN KEY (`content_id`) REFERENCES `Content_Types`(`content_id`) ON DELETE CASCADE ON UPDATE CASCADE
            -- Optional FK to a Players table if needed
            -- FOREIGN KEY (player_uuid) REFERENCES Players(player_uuid)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
        """;
    private static final String CREATE_PLAYER_SEASON_STATS_TABLE = """
        CREATE TABLE IF NOT EXISTS `Player_Season_Stats` (
            `player_uuid` VARCHAR(36) NOT NULL,
            `season_id` VARCHAR(10) NOT NULL,
            `chosen_job_id` INT NULL,
            `skill_points` INT NOT NULL DEFAULT 0,
            `class` INT NOT NULL DEFAULT 1,
            PRIMARY KEY (`player_uuid`, `season_id`),
            FOREIGN KEY (`chosen_job_id`) REFERENCES `Jobs`(`job_id`) ON DELETE SET NULL ON UPDATE CASCADE
            -- Optional FK to a Players table if needed
            -- FOREIGN KEY (player_uuid) REFERENCES Players(player_uuid)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
        """;
    private static final String CREATE_PLAYER_SKILL_LEVELS_TABLE = """
        CREATE TABLE IF NOT EXISTS `Player_Skill_Levels` (
            `player_uuid` VARCHAR(36) NOT NULL,
            `skill_id` INT NOT NULL,
            `season_id` VARCHAR(10) NOT NULL,
            `level` INT NOT NULL DEFAULT 0,
            PRIMARY KEY (`player_uuid`, `skill_id`, `season_id`),
            FOREIGN KEY (`skill_id`) REFERENCES `Skills`(`skill_id`) ON DELETE CASCADE ON UPDATE CASCADE
            -- Optional FK to a Players table if needed
            -- FOREIGN KEY (player_uuid) REFERENCES Players(player_uuid)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
        """;
    private static final String CREATE_SERVER_TRACKING_TABLE = """
        CREATE TABLE IF NOT EXISTS `server_tracking` (
            `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
            `player_uuid` VARCHAR(36) NOT NULL,
            `from_server` VARCHAR(64) NOT NULL,
            `to_server` VARCHAR(64) NOT NULL,
            `timestamp` BIGINT NOT NULL,
            `status` VARCHAR(30) NOT NULL COMMENT 'e.g., TRANSFER_STARTED, TRANSFER_COMPLETED, FAILED, NEW_PLAYER, NON_API_TRANSFER',
            INDEX `idx_player_uuid_time` (`player_uuid`, `timestamp`)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
        """;
    private static final String CREATE_SERVER_STATUS_TABLE = """
        CREATE TABLE IF NOT EXISTS `server_status` (
            `player_uuid` VARCHAR(36) PRIMARY KEY,
            `current_server` VARCHAR(64) NOT NULL,
            `last_server` VARCHAR(64) NULL,
            `last_update` BIGINT NOT NULL,
            `data_status` ENUM('ACTIVE', 'SUSPENDED', 'READONLY') NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE: R/W, READONLY: Transferring, SUSPENDED: Non-API Server',
             INDEX `idx_current_server` (`current_server`)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
        """;

    // --- Player Data Loading Queries ---
    private static final String LOAD_PLAYER_SEASON_STATS = "SELECT chosen_job_id, skill_points, class FROM Player_Season_Stats WHERE player_uuid = ? AND season_id = ?";
    private static final String LOAD_PLAYER_CONTENT_PROGRESS = "SELECT content_id, experience, level FROM Player_Content_Progress WHERE player_uuid = ? AND season_id = ?";
    private static final String LOAD_PLAYER_SKILL_LEVELS = "SELECT skill_id, level FROM Player_Skill_Levels WHERE player_uuid = ? AND season_id = ?";
    private static final String GET_CONTENT_RANKING_UUIDS = """
        SELECT player_uuid
        FROM Player_Content_Progress
        WHERE season_id = ? AND content_id = ?
        ORDER BY level DESC, experience DESC
        LIMIT ?
        """;

    // --- Player Data Saving Queries (UPSERT/DELETE) ---
    private static final String UPSERT_PLAYER_SEASON_STATS = """
        INSERT INTO Player_Season_Stats (player_uuid, season_id, chosen_job_id, skill_points, class)
        VALUES (?, ?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE chosen_job_id = VALUES(chosen_job_id), skill_points = VALUES(skill_points), class = VALUES(class);
        """;
    private static final String UPSERT_PLAYER_CONTENT_PROGRESS = """
        INSERT INTO Player_Content_Progress (player_uuid, content_id, season_id, experience, level)
        VALUES (?, ?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE experience = VALUES(experience), level = VALUES(level);
        """;
    private static final String UPSERT_PLAYER_SKILL_LEVEL = """
        INSERT INTO Player_Skill_Levels (player_uuid, skill_id, season_id, level)
        VALUES (?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE level = VALUES(level);
        """;
    private static final String DELETE_PLAYER_SKILL_LEVEL = "DELETE FROM Player_Skill_Levels WHERE player_uuid = ? AND skill_id = ? AND season_id = ?";

    // --- Server Status & Tracking Queries ---
    private static final String GET_DATA_STATUS = "SELECT data_status FROM server_status WHERE player_uuid = ?";
    private static final String UPDATE_DATA_STATUS = "UPDATE server_status SET data_status = ? WHERE player_uuid = ?";
    private static final String VALIDATE_READONLY_STATUS = "SELECT 1 FROM server_status WHERE player_uuid = ? AND data_status = 'READONLY' LIMIT 1";
    private static final String INITIALIZE_PLAYER_STATUS = """
        INSERT INTO server_status (player_uuid, current_server, last_update, data_status)
        VALUES (?, ?, ?, 'ACTIVE')
        ON DUPLICATE KEY UPDATE current_server = VALUES(current_server), last_update = VALUES(last_update), data_status = 'ACTIVE';
        """; // Ensure status becomes ACTIVE on init/re-init
    private static final String INSERT_SERVER_TRACKING = """
        INSERT INTO server_tracking (player_uuid, from_server, to_server, timestamp, status) VALUES (?, ?, ?, ?, ?);
        """;
    private static final String UPDATE_SERVER_INFO_ON_TRANSFER = """
        INSERT INTO server_status (player_uuid, current_server, last_server, last_update, data_status)
        VALUES (?, ?, ?, ?, ?) -- targetServer, fromServer, time, targetStatus (READONLY or SUSPENDED)
        ON DUPLICATE KEY UPDATE
            last_server = VALUES(last_server),
            current_server = VALUES(current_server),
            last_update = VALUES(last_update),
            data_status = VALUES(data_status);
        """;

    // --- Static Data Loading Queries ---
    private static final String LOAD_CONTENT_TYPES = "SELECT * FROM Content_Types";
    private static final String LOAD_JOBS = "SELECT * FROM Jobs";
    private static final String LOAD_SKILLS = "SELECT * FROM Skills";

    // --- Ranking ---
    private static final String GET_OVERALL_LEVEL_RANKING_UUIDS = """
        SELECT player_uuid
        FROM Player_Content_Progress
        WHERE season_id = ?
        GROUP BY player_uuid
        ORDER BY SUM(level) DESC, SUM(experience) DESC 
        LIMIT ?
        """;

    // RANK()는 동점자를 같은 순위로 처리하고 다음 순위를 건너뜁니다. (1, 1, 3)
    // DENSE_RANK()는 동점자를 같은 순위로 처리하고 다음 순위를 건너뛰지 않습니다. (1, 1, 2)
    // ROW_NUMBER()는 동점자 발생 시 임의 순서로 고유 순위를 부여합니다. (1, 2, 3)
    private static final String GET_PLAYER_RANK_IN_CONTENT = """
        SELECT rank
        FROM (
            SELECT
                player_uuid,
                RANK() OVER (ORDER BY level DESC, experience DESC) as rank
            FROM Player_Content_Progress
            WHERE season_id = ? AND content_id = ?
        ) ranked
        WHERE player_uuid = ?
        """;
    private static final String GET_PLAYER_RANK_OVERALL = """
        SELECT rank
        FROM (
            SELECT
                player_uuid,
                RANK() OVER (ORDER BY total_level DESC, total_experience DESC) as rank
            FROM (
                SELECT
                    player_uuid,
                    SUM(level) AS total_level,
                    SUM(experience) AS total_experience
                FROM Player_Content_Progress
                WHERE season_id = ?
                GROUP BY player_uuid
            ) subquery
        ) ranked
        WHERE player_uuid = ?
        """;


    /**
     * Constructor for DatabaseHandler. Initializes the HikariCP data source and ensures database tables are created.
     * @param config The configuration manager instance.
     * @param logger The transaction logger instance.
     * @throws RuntimeException if database initialization fails.
     */
    public DatabaseHandler(ConfigManager config, TransactionLogger logger) {
        this.config = config;
        this.logger = logger;

        HikariConfig hikariConfig = new HikariConfig();
        // Configure HikariCP pool settings
        hikariConfig.setJdbcUrl(String.format("jdbc:mysql://%s:%d/%s?useSSL=false&autoReconnect=true",
                config.getDatabaseHost(), config.getDatabasePort(), config.getDatabaseName()));
        hikariConfig.setUsername(config.getDatabaseUser());
        hikariConfig.setPassword(config.getDatabasePassword());
        hikariConfig.setMaximumPoolSize(15); // Adjust pool size as needed
        hikariConfig.setMinimumIdle(5);
        hikariConfig.setConnectionTimeout(30000); // 30 seconds
        hikariConfig.setIdleTimeout(60000); // 1 minutes
        hikariConfig.setMaxLifetime(180000); // 3 minutes
        // Performance optimizations for MySQL
        hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
        hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
        hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        hikariConfig.setPoolName("JobSkillPool");

        try {
            this.dataSource = new HikariDataSource(hikariConfig);
            logger.info("Database connection pool initialized.");
            initializeTables(); // Ensure tables exist on startup
        } catch (Exception e) {
            logger.error("Failed to initialize HikariDataSource", e);
            throw new RuntimeException("Database initialization failed", e); // Fail fast
        }
    }

    /**
     * Initializes or verifies the required database tables by executing CREATE TABLE IF NOT EXISTS statements.
     * @throws RuntimeException if table initialization fails.
     */
    private void initializeTables() {
        logger.info("Initializing/Verifying database tables...");
        // Use try-with-resources for automatic connection and statement closing
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_CONTENT_TYPES_TABLE);
            stmt.execute(CREATE_JOBS_TABLE);
            stmt.execute(CREATE_SKILLS_TABLE);
            stmt.execute(CREATE_PLAYER_CONTENT_PROGRESS_TABLE);
            stmt.execute(CREATE_PLAYER_SEASON_STATS_TABLE);
            stmt.execute(CREATE_PLAYER_SKILL_LEVELS_TABLE);
            stmt.execute(CREATE_SERVER_TRACKING_TABLE);
            stmt.execute(CREATE_SERVER_STATUS_TABLE);
            logger.info("Database tables initialized/verified successfully.");
        } catch (SQLException e) {
            logger.error("Failed to initialize database tables", e);
            throw new RuntimeException("Database table initialization failed", e); // Fail fast
        }
    }

    /**
     * Loads all ContentTypes from the database asynchronously.
     * @return A CompletableFuture containing a list of ContentType objects. Wraps SQLException in CompletionException.
     */
    public CompletableFuture<List<ContentType>> loadContentTypesFromDB() {
        return CompletableFuture.supplyAsync(() -> {
            List<ContentType> types = new ArrayList<>();
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(LOAD_CONTENT_TYPES);
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    types.add(new ContentType(rs.getInt("content_id"), rs.getString("content_name"), rs.getString("description")));
                }
            } catch (SQLException e) { throw new CompletionException(e); }
            return types;
        });
    }

    /**
     * Loads all Jobs from the database asynchronously.
     * @return A CompletableFuture containing a list of Job objects. Wraps SQLException in CompletionException.
     */
    public CompletableFuture<List<Job>> loadJobsFromDB() {
        return CompletableFuture.supplyAsync(() -> {
            List<Job> jobs = new ArrayList<>();
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(LOAD_JOBS);
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    // Handle potential null value for required_content_id
                    Integer requiredContentId = (Integer) rs.getObject("required_content_id");
                    jobs.add(new Job(rs.getInt("job_id"), rs.getString("job_name"), rs.getString("description"),
                            requiredContentId, rs.getInt("required_content_level")));
                }
            } catch (SQLException e) { throw new CompletionException(e); }
            return jobs;
        });
    }

    /**
     * Loads all Skills from the database asynchronously.
     * @return A CompletableFuture containing a list of Skill objects. Wraps SQLException in CompletionException.
     */
    public CompletableFuture<List<Skill>> loadSkillsFromDB() {
        return CompletableFuture.supplyAsync(() -> {
            List<Skill> skills = new ArrayList<>();
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(LOAD_SKILLS);
                 ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    skills.add(new Skill(rs.getInt("skill_id"), rs.getString("skill_name"), rs.getString("description"),
                            rs.getInt("max_level"), rs.getInt("associated_job_id")));
                }
            } catch (SQLException e) { throw new CompletionException(e); }
            return skills;
        });
    }

    /**
     * Loads a player's seasonal data (stats, progress, skills) for a specific season asynchronously.
     * Initializes player status if it doesn't exist. Returns default data if player has no records for the season.
     * @param playerUUID The UUID of the player.
     * @param seasonId The ID of the season to load data for.
     * @return A CompletableFuture containing an Optional PlayerSeasonData. Empty only if arguments are invalid or a connection error occurs.
     */
    public CompletableFuture<Optional<PlayerSeasonData>> loadPlayerSeasonData(UUID playerUUID, String seasonId) {
        return CompletableFuture.supplyAsync(() -> {
            if (playerUUID == null || seasonId == null || seasonId.isEmpty()) {
                logger.error("Invalid arguments for loading player data: UUID={}, Season={}", playerUUID, seasonId);
                return Optional.empty(); // Return empty for invalid input
            }
            String playerUUIDString = playerUUID.toString();
            PlayerSeasonData seasonData = new PlayerSeasonData(playerUUID, seasonId); // Create data object first

            try (Connection conn = dataSource.getConnection()) {
                // Ensure player status exists or initialize it
                checkAndInitializeStatus(conn, playerUUID, config.getServerName());

                // Load Season Stats
                try (PreparedStatement pstmt = conn.prepareStatement(LOAD_PLAYER_SEASON_STATS)) {
                    pstmt.setString(1, playerUUIDString);
                    pstmt.setString(2, seasonId);
                    ResultSet rs = pstmt.executeQuery();
                    if (rs.next()) {
                        Integer jobId = (Integer) rs.getObject("chosen_job_id");
                        seasonData.setSeasonStats(new PlayerSeasonStats(playerUUID, seasonId, jobId, rs.getInt("skill_points"), rs.getInt("class")));
                    } // If no row, seasonStats remains null (or default if constructor sets one)
                }

                // Load Content Progress
                try (PreparedStatement pstmt = conn.prepareStatement(LOAD_PLAYER_CONTENT_PROGRESS)) {
                    pstmt.setString(1, playerUUIDString);
                    pstmt.setString(2, seasonId);
                    ResultSet rs = pstmt.executeQuery();
                    while(rs.next()) {
                        seasonData.setContentProgress(new PlayerContentProgress(rs.getInt("content_id"), seasonId, rs.getLong("experience"), rs.getInt("level")));
                    }
                }

                // Load Skill Levels (only load skills with level > 0)
                try (PreparedStatement pstmt = conn.prepareStatement(LOAD_PLAYER_SKILL_LEVELS)) {
                    pstmt.setString(1, playerUUIDString);
                    pstmt.setString(2, seasonId);
                    ResultSet rs = pstmt.executeQuery();
                    while(rs.next()) {
                        int level = rs.getInt("level");
                        if (level > 0) { // Only store skills actually learned
                            seasonData.setSkillLevel(new PlayerSkillLevel(rs.getInt("skill_id"), seasonId, level));
                        }
                    }
                }

                seasonData.clearDirty(); // Mark as clean after loading from DB
                return Optional.of(seasonData); // Return the data (might be default if no DB records found)

            } catch (SQLException e) {
                logger.error("Failed to load player season data for {} in season {}: {}", playerUUIDString, seasonId, e.getMessage(), e);
                return Optional.empty(); // Return empty on SQL error during load
            }
        });
    }

    /**
     * Saves the player's seasonal data to the database using UPSERT/DELETE operations within a transaction.
     * Upserts stats, content progress, and skills with level > 0. Deletes skills with level 0.
     * @param data The PlayerSeasonData object to save. Assumes this data is marked 'dirty'.
     * @return A CompletableFuture indicating success (true) or failure (false).
     */
    public CompletableFuture<Boolean> savePlayerSeasonData(PlayerSeasonData data) {
        return CompletableFuture.supplyAsync(() -> {
            if (data == null) {
                logger.warn("Attempted to save null PlayerSeasonData");
                return false;
            }
            String playerUUID = data.getPlayerUUID().toString();
            String seasonId = data.getSeasonId();

            // Separate skills to be deleted (level 0) and upserted (level > 0)
            Set<Integer> skillsToDelete = new HashSet<>();
            List<PlayerSkillLevel> skillsToUpsert = new ArrayList<>();
            data.getSkillLevelMap().values().forEach(skill -> {
                if (skill.getLevel() > 0) {
                    skillsToUpsert.add(skill);
                } else {
                    // If level is 0, mark for deletion (could be from resetJobData)
                    skillsToDelete.add(skill.getSkillId());
                }
            });

            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false); // Start transaction
                try {
                    // 1. Save Season Stats (UPSERT)
                    Optional<PlayerSeasonStats> statsOpt = data.getSeasonStats();
                    if (statsOpt.isPresent()) {
                        PlayerSeasonStats stats = statsOpt.get();
                        try (PreparedStatement pstmt = conn.prepareStatement(UPSERT_PLAYER_SEASON_STATS)) {
                            pstmt.setString(1, playerUUID);
                            pstmt.setString(2, seasonId);
                            if (stats.getChosenJobId().isPresent()) {
                                pstmt.setInt(3, stats.getChosenJobId().get());
                            } else {
                                pstmt.setNull(3, Types.INTEGER);
                            }
                            pstmt.setInt(4, stats.getSkillPoints());
                            pstmt.setInt(5, stats.getClassValue());
                            pstmt.executeUpdate();
                        }
                    } else {
                        // Handle case where stats might be missing (e.g., delete row? Depends on design)
                        logger.warn("PlayerSeasonStats object was null for {} during save.", playerUUID);
                    }

                    // 2. Save Content Progress (Batch UPSERT)
                    if (!data.getContentProgressMap().isEmpty()) {
                        try (PreparedStatement pstmt = conn.prepareStatement(UPSERT_PLAYER_CONTENT_PROGRESS)) {
                            for (PlayerContentProgress progress : data.getContentProgressMap().values()) {
                                pstmt.setString(1, playerUUID);
                                pstmt.setInt(2, progress.getContentId());
                                pstmt.setString(3, seasonId);
                                pstmt.setLong(4, progress.getExperience());
                                pstmt.setInt(5, progress.getLevel());
                                pstmt.addBatch();
                            }
                            pstmt.executeBatch();
                        }
                    }

                    // 3. Delete skills marked with level 0 (Batch DELETE)
                    if (!skillsToDelete.isEmpty()) {
                        try (PreparedStatement deletePstmt = conn.prepareStatement(DELETE_PLAYER_SKILL_LEVEL)) {
                            for (int skillId : skillsToDelete) {
                                deletePstmt.setString(1, playerUUID);
                                deletePstmt.setInt(2, skillId);
                                deletePstmt.setString(3, seasonId);
                                deletePstmt.addBatch();
                            }
                            deletePstmt.executeBatch();
                            logger.debug("Deleted {} skill entries for player {} season {} due to level 0.", skillsToDelete.size(), playerUUID, seasonId);
                        }
                    }

                    // 4. Upsert skills with level > 0 (Batch UPSERT)
                    if (!skillsToUpsert.isEmpty()) {
                        try (PreparedStatement upsertPstmt = conn.prepareStatement(UPSERT_PLAYER_SKILL_LEVEL)) {
                            for (PlayerSkillLevel skill : skillsToUpsert) {
                                upsertPstmt.setString(1, playerUUID);
                                upsertPstmt.setInt(2, skill.getSkillId());
                                upsertPstmt.setString(3, seasonId);
                                upsertPstmt.setInt(4, skill.getLevel());
                                upsertPstmt.addBatch();
                            }
                            upsertPstmt.executeBatch();
                        }
                    }

                    conn.commit(); // Commit transaction
                    return true; // Save successful
                } catch (SQLException e) {
                    conn.rollback(); // Rollback on any error within the transaction
                    logger.error("Failed transaction saving player season data for {} season {}: {}", playerUUID, seasonId, e.getMessage(), e);
                    return false; // Save failed
                } finally {
                    conn.setAutoCommit(true); // Restore auto-commit mode
                }
            } catch (SQLException e) {
                logger.error("Database connection failed during save for {}: {}", playerUUID, e.getMessage(), e);
                return false; // Save failed due to connection issue
            }
        });
    }

    /**
     * Gets the current data status SYNCHRONOUSLY from within an existing DB connection. Use with caution.
     * @param conn The active database connection.
     * @param playerUUID The player's UUID.
     * @return An Optional containing the data status string ('ACTIVE', 'READONLY', 'SUSPENDED'), or empty if not found.
     * @throws SQLException if a database access error occurs.
     */
    private Optional<String> getDataStatusSync(Connection conn, UUID playerUUID) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(GET_DATA_STATUS)) {
            stmt.setString(1, playerUUID.toString());
            ResultSet rs = stmt.executeQuery();
            return rs.next() ? Optional.ofNullable(rs.getString("data_status")) : Optional.empty();
        }
    }

    /**
     * Checks if a player's status exists in the server_status table, initializes if not using the provided connection.
     * Logs a tracking event for new players. Assumes called within a transaction or where appropriate.
     * @param conn The active database connection.
     * @param playerUUID The player's UUID.
     * @param serverName The current server name.
     * @return true if status exists or was successfully created.
     * @throws SQLException if a database access error occurs.
     */
    private boolean checkAndInitializeStatus(Connection conn, UUID playerUUID, String serverName) throws SQLException {
        Optional<String> status = getDataStatusSync(conn, playerUUID);
        if (status.isEmpty()) {
            logger.info("No server status found for {}, initializing...", playerUUID);
            try (PreparedStatement stmt = conn.prepareStatement(INITIALIZE_PLAYER_STATUS)) {
                stmt.setString(1, playerUUID.toString());
                stmt.setString(2, serverName);
                stmt.setLong(3, System.currentTimeMillis());
                stmt.executeUpdate();
            }
            // Log the initialization event using the same connection
            logServerTracking(conn, playerUUID, "NONE", serverName, "NEW_PLAYER_INIT");
            return true;
        }
        return true; // Status already existed
    }

    /**
     * Asynchronously retrieves a list of UUIDs for the top-ranked players in a specific season and content.
     * Rankings are sorted by level (descending) and then by experience (descending).
     *
     * @param seasonId The ID of the season to query.
     * @param contentId The ID of the content to query.
     * @param limit The maximum number of rankings to retrieve (e.g., 100 for top 100 players).
     * @return A CompletableFuture containing a list of UUID strings. Returns an empty list in case of a database error.
     */
    public CompletableFuture<List<String>> getContentRankingUUIDs(String seasonId, int contentId, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            List<String> rankedUUIDs = new ArrayList<>();
            if (limit <= 0) return rankedUUIDs;

            try (Connection conn = dataSource.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(GET_CONTENT_RANKING_UUIDS)) {

                pstmt.setString(1, seasonId);
                pstmt.setInt(2, contentId);
                pstmt.setInt(3, limit);

                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        rankedUUIDs.add(rs.getString("player_uuid"));
                    }
                }
                logger.debug("Fetched top {} UUIDs for content {} ranking in season {}", rankedUUIDs.size(), contentId, seasonId);
            } catch (SQLException e) {
                logger.error("Failed to fetch content ranking for contentId {} season {}: {}", contentId, seasonId, e.getMessage(), e);
            }
            return rankedUUIDs;
        });
    }

    /**
     * Logs server tracking information using an existing database connection. Assumes called within a transaction context.
     * @param conn The active database connection.
     * @param playerUUID The player's UUID.
     * @param fromServer The server the player came from.
     * @param toServer The server the player is going to.
     * @param status A string describing the tracking event (e.g., "TRANSFER_STARTED", "NEW_PLAYER_INIT").
     * @throws SQLException if a database access error occurs.
     */
    private void logServerTracking(Connection conn, UUID playerUUID, String fromServer, String toServer, String status) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(INSERT_SERVER_TRACKING)) {
            stmt.setString(1, playerUUID.toString());
            stmt.setString(2, fromServer);
            stmt.setString(3, toServer);
            stmt.setLong(4, System.currentTimeMillis());
            stmt.setString(5, status);
            stmt.executeUpdate();
        }
    }

    /**
     * Checks if player data is ACTIVE (read/write enabled) SYNCHRONOUSLY by querying the database.
     * @param playerUUID The player's UUID.
     * @return true if the data status is 'ACTIVE', false otherwise (READONLY, SUSPENDED, error, or not found).
     */
    public boolean isDataActive(UUID playerUUID) {
        if (playerUUID == null) return false;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(GET_DATA_STATUS)) {
            stmt.setString(1, playerUUID.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return "ACTIVE".equals(rs.getString("data_status"));
                }
                // Player status row doesn't exist? Should not happen after join initialization.
                logger.warn("No server status found for {} during active check.", playerUUID);
                return false;
            }
        } catch (SQLException e) {
            logger.error("Failed to check data status for player {}: {}", playerUUID, e.getMessage());
            return false; // Treat as inactive on error
        }
    }

    /**
     * Updates the player's data status (ACTIVE, READONLY, SUSPENDED) in the database asynchronously.
     * @param playerUUID The player's UUID.
     * @param status The new status string ("ACTIVE", "READONLY", or "SUSPENDED"). Must be one of these exact values.
     * @return A CompletableFuture indicating success (true) or failure (false).
     */
    public CompletableFuture<Boolean> updateDataStatus(UUID playerUUID, String status) {
        return CompletableFuture.supplyAsync(() -> {
            // Validate status input
            if (!Set.of("ACTIVE", "READONLY", "SUSPENDED").contains(status)) {
                logger.error("Invalid data status provided to updateDataStatus: {}", status);
                return false;
            }
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(UPDATE_DATA_STATUS)) {
                stmt.setString(1, status);
                stmt.setString(2, playerUUID.toString());
                int updatedRows = stmt.executeUpdate();
                if (updatedRows == 0) {
                    // This might happen if the player record was somehow deleted between checks
                    logger.warn("Failed to update data status for {}: Player status record not found?", playerUUID);
                    // Optionally try to re-initialize status here? Depends on desired robustness.
                    return false;
                }
                logger.info("Updated data status for {} to {}", playerUUID, status);
                return true;
            } catch (SQLException e) {
                logger.error("Failed to update data status for player {}: {}", playerUUID, e.getMessage(), e);
                return false;
            }
        });
    }

    /**
     * Checks if the player is currently in a READONLY state in the database asynchronously.
     * Used primarily during server transfers to verify the locking status.
     * @param playerUUID The player's UUID.
     * @return A CompletableFuture indicating if the status is READONLY (true) or not/error (false).
     */
    public CompletableFuture<Boolean> validateReadOnlyStatus(UUID playerUUID) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(VALIDATE_READONLY_STATUS)) {
                stmt.setString(1, playerUUID.toString());
                try (ResultSet rs = stmt.executeQuery()) {
                    return rs.next(); // Returns true if a row with status READONLY exists
                }
            } catch (SQLException e) {
                logger.error("Failed to validate READONLY status for player {}: {}", playerUUID, e.getMessage(), e);
                return false; // Assume not READONLY or error occurred
            }
        });
    }


    /**
     * Updates server status and logs tracking info for a server transfer within a single database transaction.
     * @param playerUUID Player transferring.
     * @param fromServer Server the player is leaving.
     * @param toServer Server the player is joining.
     * @param targetStatus Data status to set for the player ('READONLY' for API server, 'SUSPENDED' for non-API).
     * @param trackingStatus Status message for the server_tracking log (e.g., "TRANSFER_STARTED").
     * @return A CompletableFuture indicating success (true) or failure (false).
     */
    public CompletableFuture<Boolean> updateServerInfoAndLog(UUID playerUUID, String fromServer, String toServer, String targetStatus, String trackingStatus) {
        return CompletableFuture.supplyAsync(() -> {
            long currentTime = System.currentTimeMillis();
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false); // Start transaction
                try {
                    // 1. Update server_status table (UPSERT logic handles new/existing players)
                    try (PreparedStatement stmt = conn.prepareStatement(UPDATE_SERVER_INFO_ON_TRANSFER)) {
                        stmt.setString(1, playerUUID.toString());
                        stmt.setString(2, toServer);      // current_server
                        stmt.setString(3, fromServer);    // last_server
                        stmt.setLong(4, currentTime);     // last_update
                        stmt.setString(5, targetStatus);  // data_status (READONLY/SUSPENDED)
                        stmt.executeUpdate();
                    }
                    // 2. Log the transfer event in server_tracking table
                    logServerTracking(conn, playerUUID, fromServer, toServer, trackingStatus);

                    conn.commit(); // Commit transaction
                    logger.info("Updated server info for {} ({} -> {}), status set to: {}", playerUUID, fromServer, toServer, targetStatus);
                    return true; // Transaction successful
                } catch (SQLException e) {
                    conn.rollback(); // Rollback transaction on error
                    logger.error("Failed transaction updating server info for {}: {}", playerUUID, e.getMessage(), e);
                    return false; // Transaction failed
                } finally {
                    conn.setAutoCommit(true); // Restore auto-commit mode
                }
            } catch (SQLException e) {
                logger.error("Database connection failed during server info update for {}: {}", playerUUID, e.getMessage(), e);
                return false; // Connection failed
            }
        });
    }

    /**
     * Closes the HikariDataSource connection pool when the plugin is disabled or DB handler is no longer needed.
     */
    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            logger.info("Closing database connection pool...");
            dataSource.close();
            logger.info("Database connection pool closed.");
        }
    }


    /**
     * Deletes all data for a specific player in a specific season across relevant tables
     * (Player_Skill_Levels, Player_Content_Progress, Player_Season_Stats) within a single transaction.
     * @param playerUUID The UUID of the player whose data should be deleted.
     * @param seasonId The ID of the season to delete data for.
     * @return A CompletableFuture indicating success (true) or failure (false).
     */
    public CompletableFuture<Boolean> deletePlayerSeasonData(UUID playerUUID, String seasonId) {
        return CompletableFuture.supplyAsync(() -> {
            String playerUUIDStr = playerUUID.toString();
            logger.warn("Attempting to delete ALL data for player {} in season {}...", playerUUIDStr, seasonId);

            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false); // Start transaction
                try {
                    // Order of deletes doesn't strictly matter here due to how FKs are set up,
                    // but deleting dependent tables first is generally good practice.
                    try (PreparedStatement pstmt = conn.prepareStatement("DELETE FROM Player_Skill_Levels WHERE player_uuid = ? AND season_id = ?")) {
                        pstmt.setString(1, playerUUIDStr);
                        pstmt.setString(2, seasonId);
                        pstmt.executeUpdate();
                    }
                    try (PreparedStatement pstmt = conn.prepareStatement("DELETE FROM Player_Content_Progress WHERE player_uuid = ? AND season_id = ?")) {
                        pstmt.setString(1, playerUUIDStr);
                        pstmt.setString(2, seasonId);
                        pstmt.executeUpdate();
                    }
                    try (PreparedStatement pstmt = conn.prepareStatement("DELETE FROM Player_Season_Stats WHERE player_uuid = ? AND season_id = ?")) {
                        pstmt.setString(1, playerUUIDStr);
                        pstmt.setString(2, seasonId);
                        pstmt.executeUpdate();
                    }
                    // Note: server_status is typically NOT deleted here as it contains current location info.

                    conn.commit(); // Commit transaction
                    logger.info("Successfully deleted data for player {} in season {}", playerUUIDStr, seasonId);
                    return true; // Deletion successful
                } catch (SQLException e) {
                    conn.rollback(); // Rollback on error
                    logger.error("Failed transaction deleting data for player {} season {}: {}", playerUUIDStr, seasonId, e.getMessage(), e);
                    return false; // Deletion failed
                } finally {
                    conn.setAutoCommit(true); // Restore auto-commit mode
                }
            } catch (SQLException e) {
                logger.error("Database connection failed during data deletion for {}: {}", playerUUIDStr, e.getMessage(), e);
                return false; // Connection failed
            }
        });
    }

    /**
     * Asynchronously retrieves a list of UUIDs for the top-ranked players
     * based on the overall sum of content levels in a specific season.
     *
     * @param seasonId The ID of the season to query.
     * @param limit The maximum number of players to retrieve.
     * @return A CompletableFuture containing a list of UUID strings.
     */
    public CompletableFuture<List<String>> getOverallLevelRankingUUIDs(String seasonId, int limit) {
        return CompletableFuture.supplyAsync(() -> {
            List<String> rankedUUIDs = new ArrayList<>();
            if (limit <= 0) return rankedUUIDs;
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(GET_OVERALL_LEVEL_RANKING_UUIDS)) {
                pstmt.setString(1, seasonId);
                pstmt.setInt(2, limit);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        rankedUUIDs.add(rs.getString("player_uuid"));
                    }
                }
                logger.debug("Fetched top {} UUIDs for overall level ranking in season {}", rankedUUIDs.size(), seasonId);
            } catch (SQLException e) {
                logger.error("Failed to fetch overall level ranking for season {}: {}", seasonId, e.getMessage(), e);
            }
            return rankedUUIDs;
        });
    }

    /**
     * Asynchronously retrieves the rank of a specific player within a specific content in a specific season.
     *
     * @param seasonId The ID of the season to query.
     * @param contentId The ID of the content to query.
     * @param playerUUID The UUID of the player to retrieve the rank for.
     * @return A CompletableFuture containing the player's rank (Integer).
     *         Returns -1 if no data is found or an error occurs.
     */
    public CompletableFuture<Integer> getPlayerRankInContent(String seasonId, int contentId, UUID playerUUID) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(GET_PLAYER_RANK_IN_CONTENT)) {
                pstmt.setString(1, seasonId);
                pstmt.setInt(2, contentId);
                pstmt.setString(3, playerUUID.toString());
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt("rank");
                    } else {
                        logger.debug("Player {} not found in ranking for content {} season {}", playerUUID, contentId, seasonId);
                        return -1;
                    }
                }
            } catch (SQLException e) {
                logger.error("Failed to get player rank in content {} season {} for {}: {}", contentId, seasonId, playerUUID, e.getMessage(), e);
                return -1;
            }
        });
    }

    /**
     * Asynchronously retrieves the overall rank of a specific player
     * based on the sum of all content levels in a specific season.
     *
     * @param seasonId The ID of the season to query.
     * @param playerUUID The UUID of the player to retrieve the overall rank for.
     * @return A CompletableFuture containing the player's overall rank (Integer).
     *         Returns -1 if no data is found or an error occurs.
     */
    public CompletableFuture<Integer> getPlayerRankOverall(String seasonId, UUID playerUUID) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(GET_PLAYER_RANK_OVERALL)) {
                pstmt.setString(1, seasonId);
                pstmt.setString(2, playerUUID.toString());
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt("rank");
                    } else {
                        logger.debug("Player {} not found in overall ranking for season {}", playerUUID, seasonId);
                        return -1;
                    }
                }
            } catch (SQLException e) {
                logger.error("Failed to get player overall rank for season {} for {}: {}", seasonId, playerUUID, e.getMessage(), e);
                return -1;
            }
        });
    }

    /**
     * Deletes only the skill level data for a specific player in a specific season asynchronously.
     * Commonly used when resetting a player's job.
     * @param playerUUID The UUID of the player.
     * @param seasonId The ID of the season to delete skill levels for.
     * @return A CompletableFuture indicating success (true) or failure (false). Success is returned even if 0 rows were deleted.
     */
    public CompletableFuture<Boolean> deletePlayerSkillLevels(UUID playerUUID, String seasonId) {
        return CompletableFuture.supplyAsync(() -> {
            String playerUUIDStr = playerUUID.toString();
            logger.info("Deleting skill levels for player {} in season {}...", playerUUIDStr, seasonId);
            String sql = "DELETE FROM Player_Skill_Levels WHERE player_uuid = ? AND season_id = ?";
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, playerUUIDStr);
                pstmt.setString(2, seasonId);
                int deletedRows = pstmt.executeUpdate();
                logger.info("Deleted {} skill level entries for player {} season {}", deletedRows, playerUUIDStr, seasonId);
                return true; // Deletion attempt itself succeeded
            } catch (SQLException e) {
                logger.error("Failed to delete skill levels for player {} season {}: {}", playerUUIDStr, seasonId, e.getMessage(), e);
                return false; // Deletion failed
            }
        });
    }
}