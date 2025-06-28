# JobSkillAPI

## Overview

JobSkillAPI is a comprehensive Bukkit plugin designed to provide a robust framework for managing jobs, skills, and content-based progression systems in a Minecraft server environment. It supports multi-server setups through Redis and offers a rich API for developers to integrate and extend its features. The system is built around the concepts of "Content Types" (like mining, combat), "Jobs" that players can select, and "Skills" associated with those jobs.

## Features

  * **Content Progression:** Create various content types (e.g., Mining, Fishing) where players can gain experience and level up. Experience requirements for each level are fully configurable.
  * **Job System:** Define jobs with unique names, descriptions, and prerequisites. Players can select jobs, and access job-specific skills.
  * **Skill System:** Associate skills with specific jobs. Skills have maximum levels, and players can level them up using skill points.
  * **Seasonal Data:** All player progression (levels, jobs, skills) is tracked on a seasonal basis, allowing for periodic resets. The current season is determined automatically by date (e.g., "2025-Q3") but can be manually overridden.
  * **Database Integration:** Uses a MySQL database with HikariCP for efficient and reliable data persistence.
  * **Multi-Server Support:** Leverages Redis Pub/Sub to synchronize data and manage events like global season resets across multiple servers, ensuring data consistency.
  * **Developer API:** A rich and thread-safe API (`JobSkillAPI`) allows other plugins to easily access player data, modify progression, and listen to custom events.
  * **Comprehensive Logging:** Detailed transaction logging for all significant player data changes (experience gain, job selection, skill level-ups) for easy auditing and debugging.
  * **Custom Events:** Dispatches a variety of custom Bukkit events (e.g., `PlayerContentLevelUpEvent`, `PlayerJobSelectEvent`, `PlayerSkillLevelUpEvent`) for deep integration with other plugins.

## Database Schema

The plugin sets up and uses the following tables in a MySQL database:

  * `Content_Types`: Defines the different types of content available (e.g., mining, combat).
  * `Jobs`: Defines the available jobs, their names, descriptions, and any content level requirements.
  * `Skills`: Defines the skills, their max levels, and associates them with a job.
  * `Player_Content_Progress`: Tracks each player's experience and level for each content type per season.
  * `Player_Season_Stats`: Stores a player's chosen job, skill points, and class value for each season.
  * `Player_Skill_Levels`: Stores the level of each skill a player has learned per season.
  * `server_status`: Tracks the current server and data status (e.g., ACTIVE, READONLY) for each player to manage server transfers.
  * `server_tracking`: Logs player movements between servers for administrative purposes.

## Configuration (`config.yml`)

The `config.yml` file allows for detailed configuration of the plugin:

  * **`server-name`**: The unique name of the current server, as recognized by BungeeCord.
  * **`lobby-server-name`**: The name of the lobby server.
  * **`api-enabled-servers`**: A list of servers where the API is active and data should be managed.
  * **`database`**: Connection details for your MySQL database (host, port, name, user, password).
  * **`redis`**: Configuration for Redis, including host, port, password, and channel for pub/sub messaging. Can be enabled or disabled.
  * **`content-level-requirements`**: Define the experience needed for each level. You can set default requirements and then override them for specific content IDs.

**Example Level Configuration:**

```yaml
content-level-requirements:
  # Default experience requirements
  default:
    - { level: 2, experience: 100 }
    - { level: 3, experience: 250 }
  # Specific requirements for content ID 1 (e.g., Mining)
  '1':
    - { level: 2, experience: 120 }
    - { level: 3, experience: 300 }
```

## API Usage

To use the API, get the singleton instance and call its methods. Always check if a player's data is loaded before accessing it, especially during the player join process.

**Getting the API Instance:**

```java
JobSkillAPI api = JobSkillAPI.getInstance();
```

**Example: Adding Content Experience**

```java
UUID playerUUID = player.getUniqueId();
int contentId = 1; // Example: Mining
long experienceToAdd = 50;
String reason = "Mined a diamond ore";

api.addContentExperience(playerUUID, contentId, experienceToAdd, reason)
   .thenAccept(success -> {
       if (success) {
           player.sendMessage("You gained " + experienceToAdd + " mining experience!");
       }
   });
```

**Example: Getting a Player's Content Level**

```java
int level = api.getContentLevel(playerUUID, contentId);
player.sendMessage("Your mining level is: " + level);
```

**Example: Leveling Up a Skill**

This requires a pre-calculated cost.

```java
int skillId = 101; // Example: Power Strike
int cost = 5; // The cost in skill points to level up
String reason = "Player purchased level up";

api.levelUpSkill(playerUUID, skillId, reason, cost)
   .thenAccept(success -> {
       if (success) {
           player.sendMessage("You have leveled up Power Strike!");
       } else {
           player.sendMessage("Failed to level up skill. Not enough skill points or max level reached.");
       }
   });
```

## Events

You can listen for these custom events to integrate your own plugin logic:

  * **`PlayerContentLevelUpEvent`**: Fired when a player's level increases in a content type.
  * **`PlayerJobSelectEvent`**: Fired when a player selects a new job.
  * **`PlayerSkillLevelUpEvent`**: Fired when a player successfully levels up a skill.
  * **`PlayerSkillPointsChangeEvent`**: Fired when a player's skill points are changed.
  * **`PlayerJobResetEvent`**: Fired when a player's job-related data is reset.
  * **`PlayerSeasonResetEvent`**: Fired when all of a player's data for a season is cleared.

**Example: Listening to an Event**

```java
@EventHandler
public void onContentLevelUp(PlayerContentLevelUpEvent event) {
    Player player = event.getPlayer();
    ContentType type = event.getContentType();
    int newLevel = event.getNewLevel();

    player.sendMessage("Congratulations! Your " + type.getName() + " level is now " + newLevel + "!");
}
```

## Installation and Setup

1.  **Database Setup**: Create a MySQL database and a user with appropriate permissions.
2.  **Configuration**: Edit the `config.yml` file with your database credentials and server names. If using a multi-server setup, configure Redis details as well.
3.  **Dependencies**: This project depends on `Spigot-API`, `HikariCP`, and `Jedis`. It also has a dependency `allplayersutil`. Make sure these are correctly set up in your build environment.
4.  **Build**: Build the plugin using Apache Maven with the command `mvn clean package`.
5.  **Installation**: Place the resulting JAR file into the `plugins` folder of your Bukkit/Spigot server.
6.  **Restart**: Restart your server. The plugin will create the necessary database tables on its first run.
