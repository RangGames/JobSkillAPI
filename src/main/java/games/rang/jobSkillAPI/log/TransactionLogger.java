package games.rang.jobSkillAPI.log;

import org.bukkit.plugin.Plugin;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles logging for the plugin, including general messages (info, warn, error, debug)
 * and specific transaction logs. Outputs to both console (via plugin logger) and
 * a daily rotating file. Ensures thread-safe file writing.
 */
public class TransactionLogger {
    private final Logger pluginLogger; // Plugin's logger for console output
    private final Plugin plugin; // Reference to the plugin instance
    private final File logFile; // Daily log file
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter FILE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private final Object fileLock = new Object(); // Lock for thread-safe file writing

    /**
     * Constructor for TransactionLogger. Initializes the logger and log file.
     * @param plugin The plugin instance.
     */
    public TransactionLogger(Plugin plugin) {
        this.plugin = plugin;
        this.pluginLogger = plugin.getLogger();
        File logsDir = new File(plugin.getDataFolder(), "logs");
        if (!logsDir.exists() && !logsDir.mkdirs()) {
            pluginLogger.warning("Could not create logs directory!");
        }
        this.logFile = new File(logsDir, "jobskill-transactions-" + LocalDateTime.now().format(FILE_FORMATTER) + ".log");
        // Ensure log file exists
        try {
            if (!logFile.exists() && !logFile.createNewFile()) {
                pluginLogger.warning("Could not create log file: " + logFile.getName());
            }
        } catch (IOException e) {
            pluginLogger.log(Level.SEVERE, "Failed to initialize log file", e);
        }
    }

    /**
     * Gets the plugin instance associated with this logger.
     * @return The Plugin instance.
     */
    public Plugin getPlugin() {
        return plugin;
    }

    /**
     * Logs a specific data transaction to console (INFO) and file (TRANSACTION level).
     * @param playerUUID The UUID of the player involved.
     * @param type The type of transaction (e.g., "ContentExp", "JobSelect", "SkillLevel"). Should be concise.
     * @param targetId The identifier of the target (e.g., Content ID, Job ID, Skill ID, or a relevant string like "PlayerJob").
     * @param oldValue The value before the transaction (can be null or descriptive string like "None").
     * @param newValue The value after the transaction.
     * @param reason The reason or context for the transaction.
     */
    public void logTransaction(UUID playerUUID, String type, Object targetId, Object oldValue, Object newValue, String reason) {
        String timestamp = LocalDateTime.now().format(DATE_FORMATTER);
        // Standardized format for easy parsing
        String logMessage = String.format("[%s] [%s] Player: %s | Target: %s | Change: %s -> %s | Reason: %s",
                timestamp, type.toUpperCase(), playerUUID, targetId, oldValue, newValue, reason);

        pluginLogger.info(logMessage); // Log transactions as INFO level to console by default
        logToFile("TRANSACTION", logMessage); // Log specifically marked as TRANSACTION in the file
    }

    /**
     * Logs an informational message to console (INFO) and file (INFO).
     * @param format The message format string (using {} for placeholders).
     * @param args The arguments for the placeholders.
     */
    public void info(String format, Object... args) {
        String message = formatMessage(format, args);
        pluginLogger.info(message);
        logToFile("INFO", message);
    }

    /**
     * Logs a warning message to console (WARNING) and file (WARN).
     * @param format The message format string (using {} for placeholders).
     * @param args The arguments for the placeholders.
     */
    public void warn(String format, Object... args) {
        String message = formatMessage(format, args);
        pluginLogger.warning(message);
        logToFile("WARN", message);
    }

    /**
     * Logs an error message to console (SEVERE) and file (ERROR).
     * @param format The message format string (using {} for placeholders).
     * @param args The arguments for the placeholders.
     */
    public void error(String format, Object... args) {
        String message = formatMessage(format, args);
        pluginLogger.severe(message);
        logToFile("ERROR", message);
    }

    /**
     * Logs an error message with an associated throwable to console (SEVERE) and file (ERROR).
     * Logs the stack trace to the console but only the exception message to the file by default.
     * @param message The error description.
     * @param throwable The associated exception/error.
     */
    public void error(String message, Throwable throwable) {
        pluginLogger.log(Level.SEVERE, message, throwable); // Logs stack trace to console
        logToFile("ERROR", message + " | Exception: " + throwable.getMessage()); // Log basic error message to file
    }

    /**
     * Logs a debug message. Output to console depends on the plugin logger's level (usually requires FINE).
     * Always logs to the file (as DEBUG level) regardless of console level.
     * @param format The message format string (using {} for placeholders).
     * @param args The arguments for the placeholders.
     */
    public void debug(String format, Object... args) {
        String message = formatMessage(format, args);
        // Log to console only if the logger level allows FINE or lower
        if (pluginLogger.isLoggable(Level.FINE)) {
            pluginLogger.fine(message); // Use FINE level for Bukkit console debug
        }
        // Always log DEBUG messages to the file for detailed troubleshooting
        logToFile("DEBUG", message);
    }

    /**
     * Formats a log message using simple {} placeholders, replacing them sequentially with args.
     * @param format The format string.
     * @param args The arguments to insert.
     * @return The formatted message string. Returns original format on error.
     */
    private String formatMessage(String format, Object... args) {
        // Optimized placeholder replacement
        if (args == null || args.length == 0 || format == null || format.indexOf('{') == -1) {
            return format;
        }
        StringBuilder sb = new StringBuilder(format.length() + args.length * 8); // Initial capacity estimate
        int argIndex = 0;
        int currentPos = 0;
        int placeholderPos;
        while (argIndex < args.length && (placeholderPos = format.indexOf("{}", currentPos)) != -1) {
            sb.append(format, currentPos, placeholderPos);
            sb.append(args[argIndex++]);
            currentPos = placeholderPos + 2;
        }
        sb.append(format, currentPos, format.length()); // Append remaining part
        return sb.toString();
    }


    /**
     * Writes a log message to the daily log file in a thread-safe manner.
     * @param level The log level string (e.g., "INFO", "ERROR", "DEBUG", "TRANSACTION").
     * @param message The message to write.
     */
    private void logToFile(String level, String message) {
        synchronized (fileLock) { // Synchronize file access
            try (PrintWriter writer = new PrintWriter(new FileWriter(logFile, true))) { // Append mode
                writer.printf("[%s] [%s] %s%n",
                        LocalDateTime.now().format(DATE_FORMATTER),
                        level,
                        message);
            } catch (IOException e) {
                // Log failure to write to file via console logger
                pluginLogger.log(Level.WARNING, "Failed to write to log file: " + logFile.getName(), e);
            }
        }
    }
}