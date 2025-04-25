package games.rang.jobSkillAPI.redis;

import games.rang.jobSkillAPI.JobSkillPlugin;
import games.rang.jobSkillAPI.api.JobSkillAPI;
import games.rang.jobSkillAPI.config.ConfigManager;
import games.rang.jobSkillAPI.log.TransactionLogger;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.exceptions.JedisConnectionException;

import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Manages Redis connection pooling and Pub/Sub messaging for inter-server communication.
 * Includes automatic reconnection logic using the Bukkit scheduler.
 */
public class RedisManager implements AutoCloseable {

    private final JobSkillPlugin plugin;
    private final ConfigManager config;
    private final TransactionLogger logger;
    private final JobSkillAPI api;
    private JedisPool jedisPool;
    private JedisPubSub subscriber;
    private ExecutorService subscriberExecutor; // Executor for the subscriber thread
    private BukkitTask reconnectTask = null; // Bukkit task for scheduling reconnect attempts
    private volatile boolean shuttingDown = false;

    /**
     * Constructor for RedisManager.
     * @param plugin The main plugin instance.
     * @param config The configuration manager.
     * @param logger The transaction logger.
     * @param api The JobSkillAPI instance.
     */
    public RedisManager(JobSkillPlugin plugin, ConfigManager config, TransactionLogger logger, JobSkillAPI api) {
        this.plugin = plugin;
        this.config = config;
        this.logger = logger;
        this.api = api;
    }

    /**
     * Initializes the Redis connection pool using settings from the ConfigManager.
     * Performs a basic connection test (PING).
     * @return true if initialization and connection test are successful, false otherwise.
     */
    public boolean initialize() {
        if (!config.isRedisEnabled()) {
            logger.info("Redis integration is disabled in config.yml.");
            return false;
        }
        try {
            logger.info("Initializing Redis connection pool to {}:{}...", config.getRedisHost(), config.getRedisPort());
            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(10);
            poolConfig.setMaxIdle(5);
            poolConfig.setMinIdle(1);
            poolConfig.setTestOnBorrow(true);
            poolConfig.setTestWhileIdle(true);
            poolConfig.setTimeBetweenEvictionRuns(Duration.ofSeconds(30));
            poolConfig.setMinEvictableIdleTime(Duration.ofMinutes(5));

            String password = config.getRedisPassword(); // Returns null if empty/not set
            jedisPool = new JedisPool(poolConfig, config.getRedisHost(), config.getRedisPort(), 3000, password, config.getRedisDatabase());

            // Test connection
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.ping(); // Throws exception on failure
                logger.info("Successfully connected to Redis.");
                return true;
            }
        } catch (JedisConnectionException e) {
            logger.error("Failed to connect to Redis server at {}:{}. Check config and Redis status.", config.getRedisHost(), config.getRedisPort(), e);
            closePool(); // Clean up pool if initialization fails
            return false;
        } catch (Exception e) {
            logger.error("An unexpected error occurred during Redis initialization.", e);
            closePool();
            return false;
        }
    }

    /**
     * Publishes a message to the specified Redis channel asynchronously.
     * Handles connection errors gracefully.
     * @param channel The channel name to publish to.
     * @param message The message content.
     */
    public void publish(String channel, String message) {
        if (jedisPool == null || jedisPool.isClosed()) {
            logger.error("Cannot publish message: Redis pool is not initialized or closed.");
            return;
        }

        CompletableFuture.runAsync(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                long receivers = jedis.publish(channel, message);
                logger.info("Published message to Redis channel '{}': '{}' (Estimated receivers: {})", channel, message, receivers);
            } catch (JedisConnectionException e) {
                logger.error("Failed to publish message to Redis channel '{}': Connection error", channel, e);
            } catch (Exception e) {
                logger.error("Error publishing message to Redis channel '{}'", channel, e);
            }
        });
    }

    /**
     * Starts the Redis subscriber on a dedicated thread.
     * If the subscriber is already running or a reconnect is scheduled, this method does nothing.
     * Handles initial connection and schedules reconnection attempts on failure.
     */
    public void startSubscriber() {
        if (jedisPool == null || jedisPool.isClosed() || !config.isRedisEnabled()) {
            logger.warn("Cannot start Redis subscriber: Redis is disabled or not initialized.");
            return;
        }
        // Prevent duplicate subscriber/reconnect tasks
        if ((subscriberExecutor != null && !subscriberExecutor.isShutdown()) || (reconnectTask != null && !reconnectTask.isCancelled())) {
            logger.warn("Redis subscriber or reconnect task is already running/scheduled.");
            return;
        }

        shuttingDown = false;
        String channel = config.getRedisChannel();
        logger.info("Attempting to start Redis subscriber for channel: {}", channel);

        subscriberExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "JobSkill-Redis-Subscriber");
            t.setDaemon(true);
            return t;
        });

        subscriber = createJedisPubSub(); // Create the handler instance

        subscriberExecutor.submit(() -> subscribeLoop(channel)); // Start the loop
    }

    /**
     * The main loop for the subscriber thread. Attempts to connect and subscribe.
     * If connection fails, schedules a reconnect attempt via Bukkit scheduler and exits the loop.
     * @param channel The channel to subscribe to.
     */
    private void subscribeLoop(String channel) {
        while (!shuttingDown && !Thread.currentThread().isInterrupted()) {
            try (Jedis jedis = jedisPool.getResource()) {
                logger.info("Subscriber thread connected. Subscribing to '{}'...", channel);
                cancelReconnectTask(); // Cancel any pending reconnect tasks if connection succeeds
                // jedis.subscribe() is blocking. It returns when unsubscribed or connection breaks.
                jedis.subscribe(subscriber, channel);
                // If subscribe returns normally (e.g., manual unsubscribe), decide whether to loop again
                if (shuttingDown) break; // Exit if shutting down
                logger.warn("Redis subscription ended unexpectedly. Will attempt to resubscribe.");
                // Optional: Add delay before retrying subscribe immediately?

            } catch (JedisConnectionException e) {
                if (shuttingDown) break; // Ignore connection errors during shutdown
                logger.error("Redis subscriber connection lost! Scheduling reconnect attempt...", e);
                scheduleReconnect(channel); // Schedule reconnect on connection failure
                break; // Exit this loop; reconnect task will restart it
            } catch (Exception e) {
                if (shuttingDown) break; // Ignore other errors during shutdown
                logger.error("Unexpected error in Redis subscriber thread. Stopping subscriber.", e);
                break; // Exit loop on unexpected errors
            }
        }
        logger.info("Redis subscribeLoop finished for channel: {}", channel);
    }

    /**
     * Schedules a task using the Bukkit scheduler to attempt reconnection after a delay.
     * Ensures only one reconnect task is scheduled at a time.
     * @param channel The channel to resubscribe to upon successful reconnection.
     */
    private synchronized void scheduleReconnect(String channel) {
        if (reconnectTask != null && !reconnectTask.isCancelled()) {
            return; // Reconnect already scheduled
        }
        long reconnectDelayTicks = 20L * 15; // 15 seconds delay
        logger.info("Scheduling Redis reconnect attempt in {} seconds...", reconnectDelayTicks / 20);

        reconnectTask = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
            logger.info("Attempting scheduled Redis reconnection...");
            synchronized (RedisManager.this) {
                reconnectTask = null; // Clear the task reference as it's running now
            }
            // If not shutting down, submit the subscribe loop task again
            if (!shuttingDown && subscriberExecutor != null && !subscriberExecutor.isShutdown()) {
                subscriberExecutor.submit(() -> subscribeLoop(channel));
            } else {
                logger.info("Skipping scheduled reconnect because RedisManager is shutting down or executor is unavailable.");
            }
        }, reconnectDelayTicks);
    }

    /**
     * Cancels any pending reconnect task scheduled via the Bukkit scheduler.
     */
    private synchronized void cancelReconnectTask() {
        if (reconnectTask != null && !reconnectTask.isCancelled()) {
            reconnectTask.cancel();
            logger.info("Cancelled scheduled Redis reconnect task.");
        }
        reconnectTask = null;
    }

    /**
     * Creates the JedisPubSub listener instance with message handling logic.
     * Parses messages (expected format "command:triggerServer:payload") and dispatches
     * actions to the JobSkillAPI, ensuring Bukkit API calls run on the main thread.
     * @return A configured JedisPubSub instance.
     */
    private JedisPubSub createJedisPubSub() {
        return new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                logger.debug("Received Redis message on channel '{}': {}", channel, message); // Log as debug
                // Expected format: "command:triggerServer:payload"
                String[] parts = message.split(":", 3);
                if (parts.length < 2) {
                    logger.warn("Received invalid Redis message format: {}", message);
                    return;
                }
                String command = parts[0];
                String triggerServer = parts[1];
                String payload = (parts.length > 2) ? parts[2] : "";

                // Ensure API calls that might interact with Bukkit/Plugin state run on main thread
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        // Handle specific commands related to season reset coordination
                        if ("Prepare".equalsIgnoreCase(command)) {
                            api.prepareGlobalSeasonReset(triggerServer);
                        } else if ("Complete".equalsIgnoreCase(command)) {
                            String newSeasonId = payload;
                            if (newSeasonId.isEmpty()) {
                                logger.error("Received 'Complete' command without new season ID!");
                                return;
                            }
                            api.completeGlobalSeasonReset(newSeasonId, triggerServer);
                        } else {
                            logger.warn("Received unknown Redis command: {}", command);
                        }
                    } catch (Exception e) {
                        logger.error("Error processing Redis message command '{}' from '{}'", command, triggerServer, e);
                    }
                });
            }

            @Override
            public void onSubscribe(String channel, int subscribedChannels) {
                logger.info("Successfully subscribed to Redis channel: {}", channel);
            }

            @Override
            public void onUnsubscribe(String channel, int subscribedChannels) {
                logger.info("Unsubscribed from Redis channel: {}", channel);
            }

            // onPMessage, etc. can be overridden if needed for pattern subscriptions
        };
    }

    /**
     * Closes the RedisManager cleanly. Unsubscribes the listener, shuts down the
     * subscriber executor service, and closes the Jedis connection pool.
     */
    @Override
    public void close() {
        if (shuttingDown) return;
        shuttingDown = true;
        logger.info("Shutting down RedisManager...");

        cancelReconnectTask(); // Cancel any pending reconnect attempts

        // Unsubscribe listener
        if (subscriber != null && subscriber.isSubscribed()) {
            try {
                logger.info("Unsubscribing Redis listener...");
                subscriber.unsubscribe(); // This should interrupt the blocking subscribe() call
            } catch (Exception e) {
                logger.error("Error during Redis unsubscribe", e);
            }
        }

        // Shutdown subscriber thread pool
        if (subscriberExecutor != null && !subscriberExecutor.isShutdown()) {
            logger.info("Shutting down Redis subscriber executor...");
            subscriberExecutor.shutdown();
            try {
                if (!subscriberExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.warn("Redis subscriber executor did not terminate gracefully. Forcing shutdown...");
                    subscriberExecutor.shutdownNow();
                } else {
                    logger.info("Redis subscriber executor terminated.");
                }
            } catch (InterruptedException e) {
                logger.error("Interrupted while waiting for subscriber executor termination.", e);
                subscriberExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        closePool(); // Close the connection pool
        logger.info("RedisManager shutdown complete.");
    }

    /**
     * Safely closes the JedisPool if it's open.
     */
    private void closePool() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            logger.info("Closing Redis connection pool...");
            try {
                jedisPool.close();
                logger.info("Redis connection pool closed.");
            } catch (Exception e) {
                logger.error("Error closing Redis connection pool", e);
            }
            jedisPool = null;
        }
    }
}