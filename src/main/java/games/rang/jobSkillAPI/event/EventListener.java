package games.rang.jobSkillAPI.event;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import games.rang.jobSkillAPI.JobSkillPlugin;
import games.rang.jobSkillAPI.api.JobSkillAPI;
import games.rang.jobSkillAPI.config.ConfigManager;
import games.rang.jobSkillAPI.log.TransactionLogger;
import games.rang.jobSkillAPI.storage.Storage;
import rang.games.allPlayersUtil.event.NetworkQuitEvent; // Custom event import

import java.util.UUID;
// Removed unused CompletableFuture import

/**
 * Handles various Bukkit player events (Join, Quit, Move) and a custom
 * NetworkQuitEvent for the JobSkillAPI plugin, managing player data loading,
 * saving, and status synchronization. Ensures thread safety for Bukkit API calls.
 */
public class EventListener implements Listener {
    private final Storage storage;
    private final TransactionLogger logger;
    private final ConfigManager config;
    private final JobSkillAPI api;
    private final JobSkillPlugin plugin;

    /**
     * Constructor for EventListener.
     * @param plugin The main plugin instance.
     * @param storage The storage handler instance.
     * @param logger The transaction logger instance.
     * @param config The configuration manager instance.
     */
    public EventListener(JobSkillPlugin plugin, Storage storage, TransactionLogger logger, ConfigManager config) {
        this.plugin = plugin;
        this.storage = storage;
        this.logger = logger;
        this.config = config;
        this.api = JobSkillAPI.getInstance(); // Assumes API is initialized before listener
    }

    /**
     * Handles player joining the server (LOWEST priority). Initiates data loading asynchronously.
     * On successful load, attempts to recover data status (e.g., if stuck in READONLY).
     * Kicks player on critical load or recovery failures.
     * Ensures Bukkit API calls (kick, scheduler tasks) are run on the main thread.
     * @param event The PlayerJoinEvent.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();
        String playerName = player.getName();

        logger.info("Player {} ({}) joining server {}. Loading data...", playerName, playerUUID, config.getServerName());

        // Start asynchronous data loading
        storage.loadPlayerSeasonData(playerUUID)
                .thenAcceptAsync(loadSuccess -> {
                    // Switch back to main thread for Bukkit API interaction
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (loadSuccess) {
                            logger.info("Data loading process completed for {}.", playerName);
                            // Attempt to recover status if needed (e.g., from previous failed transfer)
                            api.recoverPlayerDataStatus(playerUUID)
                                    .thenAcceptAsync(recoverSuccess -> {
                                        // Handle recovery result on main thread
                                        Bukkit.getScheduler().runTask(plugin, () -> {
                                            if (recoverSuccess) {
                                                logger.info("Player data status checked/recovered for {}.", playerName);
                                            } else {
                                                // Critical recovery failure, kick player
                                                logger.error("Failed to recover player data status for {}. Data might be locked!", playerName);
                                                player.kickPlayer("Data synchronization error. Please reconnect shortly.");
                                            }
                                        });
                                    });
                        } else {
                            // Critical load failure, kick player
                            logger.error("CRITICAL: Failed to load data for {}. Kicking player.", playerName);
                            player.kickPlayer("Failed to load player data. Please contact an administrator.");
                        }
                    });
                })
                .exceptionally(e -> {
                    // Handle exceptions during the loading/recovery process
                    logger.error("Exception during player join data process for {}: {}", playerName, e.getMessage(), e);
                    // Ensure kick happens on the main thread
                    Bukkit.getScheduler().runTask(plugin, () ->
                            player.kickPlayer("Error during data loading. Please reconnect.")
                    );
                    return null; // Required for exceptionally block
                });
    }

    /**
     * Handles player leaving the server (Bukkit event, MONITOR priority).
     * Initiates data saving and cache removal asynchronously. Runs last to allow other plugins to react first.
     * @param event The PlayerQuitEvent.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();
        String playerName = player.getName();

        logger.info("(Bukkit Quit) Player {} ({}) quitting server {}. Initiating save and remove...", playerName, playerUUID, config.getServerName());

        // Asynchronously save and remove data
        storage.saveAndRemovePlayerData(playerUUID)
                .thenAccept(success -> {
                    // Log success/failure if needed (debug level might be appropriate)
                    // logger.debug("(Bukkit Quit) Save/remove completed for {}. Success: {}", playerName, success);
                })
                .exceptionally(e -> {
                    logger.error("(Bukkit Quit) Exception during quit data process for {}: {}", playerName, e.getMessage(), e);
                    return null;
                });
    }

    /**
     * Handles player disconnecting from the BungeeCord network (custom event, HIGH priority).
     * Checks if the player's last server was this server. If so, initiates data saving and cache removal.
     * @param event The custom NetworkQuitEvent containing player UUID, name, and last server.
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onNetworkQuit(NetworkQuitEvent event) {
        UUID playerUUID;
        try {
            playerUUID = UUID.fromString(event.getPlayerUuid());
        } catch (IllegalArgumentException e) {
            logger.error("Invalid UUID received in NetworkQuitEvent: {}", event.getPlayerUuid());
            return;
        }
        String playerName = event.getPlayerName();
        String lastServerName = event.getServerName();

        logger.info("(Network Quit) Player {} disconnecting from network (last server: {})", playerName, lastServerName);

        // Only process if the player's last known server in Bungee was this one
        if (!lastServerName.equalsIgnoreCase(config.getServerName())) {
            logger.debug("(Network Quit) Ignoring event for {}: Player was last on server '{}', not this server ('{}').",
                    playerName, lastServerName, config.getServerName());
            return;
        }

        // Initiate save and remove if the player was last on this server
        logger.info("(Network Quit) Player {} was last on this server. Saving and removing data...", playerName);
        storage.saveAndRemovePlayerData(playerUUID)
                .thenAccept(success -> {
                    if (success) {
                        logger.info("(Network Quit) Successfully saved and removed data for player {} on network quit.", playerName);
                    } else {
                        // Data is removed from cache anyway even if save fails
                        logger.error("(Network Quit) Failed to save data for player {} on network quit (data removed from cache).", playerName);
                    }
                })
                .exceptionally(e -> {
                    logger.error("(Network Quit) Exception during network quit process for {}: {}", playerName, e.getMessage(), e);
                    return null;
                });
    }


    /**
     * Prevents player movement (HIGHEST priority, ignores cancelled) if their data is currently loading
     * or if their data status in the database is not 'ACTIVE' (e.g., READONLY during transfer).
     * Checks only for actual block changes, not just looking around.
     * @param event The PlayerMoveEvent.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        // Check if it's actual block movement, not just head rotation
        if (event.getTo() == null || (
                event.getFrom().getBlockX() == event.getTo().getBlockX() &&
                        event.getFrom().getBlockY() == event.getTo().getBlockY() &&
                        event.getFrom().getBlockZ() == event.getTo().getBlockZ())) {
            return; // Only rotation or no movement
        }

        UUID playerUUID = event.getPlayer().getUniqueId();
        // Check if player data is still being loaded
        boolean isLoading = storage.isPlayerLoading(playerUUID);
        // Check if player data status allows modification (synchronous DB check)
        boolean isActive = storage.getDatabaseHandler().isDataActive(playerUUID);

        // Cancel movement if loading or not active
        if (isLoading || !isActive) {
            event.setCancelled(true);
            // Optionally send a message (consider rate limiting this)
            // if (!isActive && !isLoading) {
            //    event.getPlayer().sendMessage(ChatColor.RED + "Data synchronization in progress, please wait...");
            // }
        }
    }
}