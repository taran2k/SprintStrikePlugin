package com.github.taran2k.sprintstrike;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.bukkit.Particle;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import net.md_5.bungee.api.ChatColor;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class SprintStrikePlugin extends JavaPlugin implements Listener {

    private FileConfiguration lang;
    private FileConfiguration config;
    private FileConfiguration tiers;
    private FileConfiguration playerData;
    private File playerDataFile;

    private final Map<UUID, Integer> playerTiers = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, Long> comboCountdowns = new HashMap<>();
    private final Map<UUID, Integer> comboTasks = new HashMap<>();

    @Override
    public void onEnable() {
        // Register events
        Bukkit.getPluginManager().registerEvents(this, this);

        // Load configuration files
        saveDefaultConfig();
        createLangFile();
        createTiersFile();
        createPlayerDataFile();

        config = getConfig();
        lang = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "lang.yml"));
        tiers = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "tiers.yml"));
        
        // Load existing player tiers from playerdata.yml
        loadPlayerTiers();

        // Register commands
        this.getCommand("sprintstrike").setExecutor((sender, command, label, args) -> {
            if (args.length < 2 || !(sender instanceof Player)) {
                sender.sendMessage(formatMessage("CommandUsage"));
                return true;
            }

            if (!sender.hasPermission("canSetSprintStrikeLevel")) {
                sender.sendMessage(formatMessage("NoPermission"));
                return true;
            }

            try {
                int level = Integer.parseInt(args[1]);
                Player target = args.length > 2 ? Bukkit.getPlayer(args[2]) : (Player) sender;

                if (level < 1) {
                    sender.sendMessage(formatMessage("LevelTooLow"));
                    return true;
                }

                if (level > tiers.getKeys(false).size()) {
                    sender.sendMessage(formatMessage("NoSuchTier"));
                    return true;
                }

                if (target != null) {
                    setPlayerTier(target.getUniqueId(), level);
                    sender.sendMessage(formatMessage("TierSet", 
                        "{LEVEL}", String.valueOf(level), 
                        "{PLAYER}", target.getName()));
                } else {
                    sender.sendMessage(formatMessage("PlayerNotFound"));
                }
            } catch (NumberFormatException e) {
                sender.sendMessage(formatMessage("LevelNotNumber"));
            }
            return true;
        });
    }

    @EventHandler
    public void onPlayerSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) return;

        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        int tier = playerTiers.getOrDefault(playerId, 0);

        // Check if player is holding correct weapon
        Material handItem = player.getInventory().getItemInMainHand().getType();
        if (!isValidWeapon(handItem)) {
            return;
        }

        Entity target = getTargetEntity(player, 20);
        if (target == null) return;
        double distance = player.getLocation().distance(target.getLocation());

        boolean hasComboPermission = getTierComboDuration(tier) > 0;

        // Check cooldown or combo countdown
        long currentTime = System.currentTimeMillis();
        
        if (hasComboPermission) {
            // Handle combo countdown
            if (handleComboCountdown(player, tier)) {
                if (sprintStrike(player, target, tier)) {
                    // Reset combo countdown
                    startComboCountdown(player, tier);
                }
                return;
            }
        }
        
        // Original cooldown logic for non-combo tiers
        long cooldown = getTierCooldown(tier) * 1000L;
        if (cooldowns.containsKey(playerId) && (currentTime - cooldowns.get(playerId)) < cooldown) {
            long remainingTime = (cooldown - (currentTime - cooldowns.get(playerId))) / 1000;
            sendMessage(player, "ErrorCooldown", remainingTime);
            
            // Play sound when cooldown is active
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.5f);
            
            return;
        }

        if (distance <= getTierMaxTeleportDistance(tier)) {
            if (sprintStrike(player, target, tier)) {
                if (!hasComboPermission) {
                    cooldowns.put(playerId, currentTime);
                } else {
                    startComboCountdown(player, tier);
                }
            }
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        // Interrupt combo on player damage
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            UUID playerId = player.getUniqueId();

            if (comboCountdowns.containsKey(playerId) && getTierDamageBreaksCombo(playerTiers.get(playerId))) {
                cancelComboCountdown(player);
                sendMessage(player, "ComboInterrupted");
            }
        }
    }

    private boolean handleComboCountdown(Player player, int tier) {
        UUID playerId = player.getUniqueId();
        Long comboStart = comboCountdowns.get(playerId);

        if (comboStart == null) {
            return false;
        }

        long currentTime = System.currentTimeMillis();
        long elapsedTime = currentTime - comboStart;

        if (elapsedTime > getTierComboDuration(tier)*1000L) {
            // Combo expired
            cancelComboCountdown(player);
            sendMessage(player, "ComboExpired");
            return false;
        }

        return true;
    }

    private void startComboCountdown(Player player, int tier) {
        UUID playerId = player.getUniqueId();
        
        // Cancel any existing countdown task
        cancelComboCountdown(player);

        // Set the combo start time
        comboCountdowns.put(playerId, System.currentTimeMillis());

        // Start a new countdown task
        BukkitTask taskId = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancel();
                    return;
                }

                long currentTime = System.currentTimeMillis();
                long comboStart = comboCountdowns.get(playerId);
                long remainingTime = getTierComboDuration(tier)*1000L - (currentTime - comboStart);

                if (remainingTime <= 0) {
                    cancelComboCountdown(player);
                    sendMessage(player, "ComboExpired");
                    cancel();
                    return;
                }

                // Format the remaining time with seconds and milliseconds
                long seconds = remainingTime / 1000;
                long milliseconds = (remainingTime % 1000) / 10;
                String countdownMessage = applyColorTags(String.format("<DARK_BLUE><BOLD>COMBO!<RESET><GREY> %d.%02d<RESET>", seconds, milliseconds));

                // Send the countdown to the hotbar
                player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, 
                    new net.md_5.bungee.api.chat.TextComponent(countdownMessage));
            }
        }.runTaskTimer(this, 0L, 1L); // Run every tick (1/20th of a second)

        // Store the task ID
        comboTasks.put(playerId, taskId.getTaskId());
    }

    private void cancelComboCountdown(Player player) {
        UUID playerId = player.getUniqueId();
        
        // Remove combo start time
        comboCountdowns.remove(playerId);

        // Cancel the countdown task if it exists
        Integer taskId = comboTasks.remove(playerId);
        if (taskId != null) {
            Bukkit.getScheduler().cancelTask(taskId);
        }

        cooldowns.put(playerId, System.currentTimeMillis());
    }

    private boolean isValidWeapon(Material material) {
        return material.name().contains("SWORD") || // Hackish solution, but seems to be the least intensive one
               material.name().contains("AXE") ||
               material == Material.STICK;
    }

    private Entity getTargetEntity(Player player, int maxDistance) {
        // Get the player's eye location and looking direction
        Location eyeLocation = player.getEyeLocation();
        Vector eyeDirection = eyeLocation.getDirection().normalize();
    
        Entity closestEntity = null;
        double closestDistance = Double.MAX_VALUE;
        
    
        // Iterate through nearby entities
        for (Entity entity : player.getNearbyEntities(maxDistance, maxDistance, maxDistance)) {
            // Filter for only living entities that are not players
            if (!(entity instanceof Animals) && !(entity instanceof Monster)) {
                continue;
            }
    
            // Get the entity's center location
            Location entityLocation = entity.getLocation().add(0, entity.getHeight() / 2, 0);
    
            // Calculate the vector from eye to entity
            Vector toEntity = entityLocation.toVector().subtract(eyeLocation.toVector());
    
            // Calculate the distance of the entity from the line of sight
            double distanceFromSight = toEntity.getCrossProduct(eyeDirection).length() / eyeDirection.length();
    
            // Check if the entity is close to the line of sight (within 1 block)
            if (distanceFromSight <= 1.0 && player.hasLineOfSight(entity)) {
                double distance = eyeLocation.distance(entityLocation);
                
                // Find the closest entity to the line of sight
                if (distance < closestDistance) {
                    closestEntity = entity;
                    closestDistance = distance;
                }
            }
        }
    
        return closestEntity;
    }

    private boolean sprintStrike(Player player, Entity target, int tier) {
        Location mobLocation = target.getLocation();
        Location playerLocation = findSafeLocationNextToEntity(mobLocation, player);
        if (playerLocation == null) {
            sendMessage(player, "ErrorNoSpace");
            return false;
        }
    
        // Teleport player
        player.teleport(playerLocation);
    
        // Calculate direction vector from player to mob
        Vector directionVector = mobLocation.toVector().subtract(playerLocation.toVector()).normalize();
    
        // Calculate pitch (vertical angle)
        double distanceXZ = Math.sqrt(directionVector.getX() * directionVector.getX() + directionVector.getZ() * directionVector.getZ());
        double pitch = Math.toDegrees(Math.atan2(-directionVector.getY()+0.3, distanceXZ));
    
        // Calculate yaw (horizontal angle)
        double yaw = Math.toDegrees(Math.atan2(-directionVector.getX(), directionVector.getZ()));
    
        // Set player's view
        Location viewLocation = player.getLocation().clone();
        viewLocation.setYaw((float) yaw);
        viewLocation.setPitch((float) pitch);
        player.teleport(viewLocation);
    
        // Play teleport sound
        player.playSound(playerLocation, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
    
        // Spawn teleport particles
        player.getWorld().spawnParticle(Particle.PORTAL, playerLocation, 50, 0.5, 1, 0.5, 0.5);
    
        applyEffects(player, tier);
        return true;
    }

    private Location findSafeLocationNextToEntity(Location mobLocation, Player player) {
        int[][] offsets = {
            {1,0,0}, {-1,0,0}, {0,0,1}, {0,0,-1}, 
            {1,0,1}, {-1,0,1}, {1,0,-1}, {-1,0,-1},
            // Additional offsets for larger mobs like spiders and horses
            {2,0,0}, {-2,0,0}, {0,0,2}, {0,0,-2},
        };

        for (int[] offset : offsets) {
            Location potentialLocation = mobLocation.clone().add(offset[0], offset[1], offset[2]);
            
            // Check if the location is safe
            if (isSafeLocation(potentialLocation, player)) {
                return potentialLocation;
            }
        }
        return null;
    }

    private boolean isSafeLocation(Location location, Player player) {
        // Comprehensive safety checks
        Block block = location.getBlock();
        Block blockBelow = location.clone().subtract(0, 1, 0).getBlock();
        Block blockAbove = location.clone().add(0, 1, 0).getBlock();

        // Check if the location provides enough space for different mob types
        // This handles variations like spiders (wider than 1 block), horses, and other entities
        return (blockBelow.getType().isSolid() && // Solid ground
                block.getType() == Material.AIR && // Current block is air
                blockAbove.getType() == Material.AIR && // Block above is air
                player.getWorld().getNearbyEntities(location, 0.5, 0.5, 0.5).isEmpty() && // Should be some space between location and entities
                !block.isLiquid() && // Not in liquid
                location.getBlockY() > 0 && location.getBlockY() < location.getWorld().getMaxHeight()); // Within world bounds
    }

    private String formatMessage(String key, String... replacements) {
        String message = lang.getString(key, "Error: message not found");
        
        for (int i = 0; i < replacements.length; i += 2) {
            if (i + 1 < replacements.length) {
                message = message.replace(replacements[i], 
                    ChatColor.YELLOW + "" + ChatColor.BOLD + replacements[i + 1] + ChatColor.RESET);
            }
        }

        // Apply color tags in the message (e.g., <BLUE>, <RED>, etc.)
        message = applyColorTags(message);

        return colorize(message);
    }
    
    private String applyColorTags(String message) {
        // Regular expression to match color tags in the form of <COLOR> anywhere in the message
        String[] colorTags = {
            "<BLUE>", ChatColor.BLUE.toString(),
            "<RED>", ChatColor.RED.toString(),
            "<YELLOW>", ChatColor.YELLOW.toString(),
            "<GREEN>", ChatColor.GREEN.toString(),
            "<AQUA>", ChatColor.AQUA.toString(),
            "<WHITE>", ChatColor.WHITE.toString(),
            "<BLACK>", ChatColor.BLACK.toString(),
            "<GRAY>", ChatColor.GRAY.toString(),
            "<GREY>", ChatColor.GRAY.toString(),
            "<DARK_BLUE>", ChatColor.DARK_BLUE.toString(),
            "<DARK_RED>", ChatColor.DARK_RED.toString(),
            "<DARK_GREEN>", ChatColor.DARK_GREEN.toString(),
            "<DARK_AQUA>", ChatColor.DARK_AQUA.toString(),
            "<DARK_GRAY>", ChatColor.DARK_GRAY.toString(),
            "<LIGHT_PURPLE>", ChatColor.LIGHT_PURPLE.toString(),
            "<DARK_PURPLE>", ChatColor.DARK_PURPLE.toString(),
            "<GOLD>", ChatColor.GOLD.toString(),
            "<STRIKETHROUGH>", ChatColor.STRIKETHROUGH.toString(),
            "<MAGIC>", ChatColor.MAGIC.toString(),
            "<ITALIC>", ChatColor.ITALIC.toString(),
            "<BOLD>", ChatColor.BOLD.toString(), 
            "<RESET>", ChatColor.RESET.toString(),
        };
    
        // Replace all occurrences of color tags in the message with the corresponding ChatColor codes
        for (int i = 0; i < colorTags.length; i += 2) {
            message = message.replaceAll(colorTags[i], colorTags[i + 1]);
        }
    
        return message;
    }
    
    private String colorize(String message) {
        // Basic chat colorization
        if (message.contains("Error:")) {
            return ChatColor.RED + message + ChatColor.RESET;
        } 
        return ChatColor.GREEN + message + ChatColor.RESET;
    }

    private void sendMessage(Player player, String key) {
        sendMessage(player, key, -1);
    }

    private void sendMessage(Player player, String key, long remainingTime) {
        String message = formatMessage(key, "{X}", String.valueOf(remainingTime));
        String messageType = config.getString("messageType", "chat");

        switch (messageType.toLowerCase()) {
            case "hotbar":
                player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, 
                   new net.md_5.bungee.api.chat.TextComponent(message));
                break;
            case "title":
                player.sendTitle("", message, 10, 70, 20);
                break;
            default:
                player.sendMessage(message);
                break;
        }
    }

    private void applyEffects(Player player, int tier) {
        Map<String, PotionEffect> highestEffects = new HashMap<>();

        for (int i = 1; i <= tier; i++) {
            List<String> effects = tiers.getStringList("tier " + i + ".effects");
            for (String effectEntry : effects) {
                String[] parts = effectEntry.split(",");
                if (parts.length == 3) {
                    PotionEffectType type = PotionEffectType.getByName(parts[0].toUpperCase());
                    int level = Integer.parseInt(parts[1]) - 1;
                    int duration = Integer.parseInt(parts[2]) * 20;

                    if (type != null && (!highestEffects.containsKey(type.getName()) || highestEffects.get(type.getName()).getAmplifier() < level)) {
                        highestEffects.put(type.getName(), new PotionEffect(type, duration, level));
                    }
                }
            }
        }

        for (PotionEffect effect : highestEffects.values()) {
            player.addPotionEffect(effect);
        }
    }

    private long getTierCooldown(int tier) {
        long tierCooldown = tiers.getLong("tier " + tier + ".cooldown", 0);

        return tierCooldown;
    }

    private long getTierComboDuration(int tier) {
        long tierComboDuration = tiers.getLong("tier " + tier + ".combo_duration", 0);

        return tierComboDuration;
    }

    private long getTierMaxTeleportDistance(int tier) {
        long tierMaxTeleportDistance = tiers.getLong("tier " + tier + ".max_teleport_distance", 0);

        return tierMaxTeleportDistance;
    }
 
    private boolean getTierDamageBreaksCombo(int tier) {
        boolean damageBreaksCombo = tiers.getBoolean("tier " + tier + ".damage_breaks_combo", true);

        return damageBreaksCombo;
    }

    private void createLangFile() {
        File langFile = new File(getDataFolder(), "lang.yml");
        if (!langFile.exists()) { // Future issue: if a version update includes new messages, add them here without completely overriding custom lang files
            try {
                langFile.createNewFile();
                FileConfiguration langConfig = YamlConfiguration.loadConfiguration(langFile);
                
                // Comprehensive language configuration with placeholders
                langConfig.set("ErrorNoSpace", "No safe location to teleport to near the mob!");
                langConfig.set("ErrorCooldown", "<GREY>You can use the sprint strike ability again in <GOLD><BOLD>{X}<RESET><GOLD> seconds");
                langConfig.set("WrongWeapon", "You must hold a sword, axe, or stick to use Sprint Strike!");
                langConfig.set("CommandUsage", "Usage: /sprintstrike settier LEVEL [PLAYER]");
                langConfig.set("NoPermission", "You don't have permission to use this command.");
                langConfig.set("LevelTooLow", "Level must be greater than 0.");
                langConfig.set("NoSuchTier", "There is no such tier!");
                langConfig.set("TierSet", "Set sprint strike tier to <YELLOW><BOLD>{LEVEL}<GREEN> for <YELLOW><BOLD>{PLAYER}<GREEN>.");
                langConfig.set("PlayerNotFound", "Player not found.");
                langConfig.set("LevelNotNumber", "Level must be a number.");
                langConfig.set("ComboInterrupted", "Combo interrupted by damage!");
                langConfig.set("ComboExpired", "Combo countdown expired!");
                
                langConfig.save(langFile);
            } catch (IOException e) {
                getLogger().severe("Could not create lang.yml file.");
            }
        }
    }

    private void createTiersFile() {
        File tiersFile = new File(getDataFolder(), "tiers.yml");
        if (!tiersFile.exists()) {
            try {
                tiersFile.createNewFile();
                FileConfiguration tiersConfig = YamlConfiguration.loadConfiguration(tiersFile);
                
                // Tier 1
                tiersConfig.set("tier 1.effects", Arrays.asList(
                    "strength,2,2" // effect: strength, level: 2, duration: 2 seconds
                ));
                tiersConfig.set("tier 1.combo_duration", 3); // Combo duration in seconds
                tiersConfig.set("tier 1.max_teleport_distance", 5); // Max teleport distance in blocks
                tiersConfig.set("tier 1.cooldown", 60); // Cooldown in seconds
                tiersConfig.set("tier 1.damage_breaks_combo", true); // Whether taking damage breaks the combo
    
                // Tier 2
                tiersConfig.set("tier 2.effects", Arrays.asList(
                    "strength,4,2", // effect: strength, level: 4, duration: 2 seconds
                    "speed,1,3"     // effect: speed, level: 1, duration: 3 seconds
                ));
                tiersConfig.set("tier 2.combo_duration", 3.5); // Combo duration in seconds
                tiersConfig.set("tier 2.max_teleport_distance", 10); // Max teleport distance in blocks
                tiersConfig.set("tier 2.cooldown", 45); // Cooldown in seconds
                tiersConfig.set("tier 2.damage_breaks_combo", true); // Whether taking damage breaks the combo
    
                // Tier 3
                tiersConfig.set("tier 3.effects", Arrays.asList(
                    "strength,6,4", // effect: strength, level: 6, duration: 4 seconds
                    "speed,2,4"     // effect: speed, level: 2, duration: 4 seconds
                ));
                tiersConfig.set("tier 3.combo_duration", 4); // Combo duration in seconds
                tiersConfig.set("tier 3.max_teleport_distance", 15); // Max teleport distance in blocks
                tiersConfig.set("tier 3.cooldown", 30); // Cooldown in seconds
                tiersConfig.set("tier 3.damage_breaks_combo", true); // Whether taking damage breaks the combo
    
                // Tier 4
                tiersConfig.set("tier 4.effects", Arrays.asList(
                    "strength,8,4",      // effect: strength, level: 8, duration: 4 seconds
                    "speed,3,4",         // effect: speed, level: 3, duration: 4 seconds
                    "regeneration,2,4"   // effect: regeneration, level: 2, duration: 4 seconds
                ));
                tiersConfig.set("tier 4.combo_duration", 4.5); // Combo duration in seconds
                tiersConfig.set("tier 4.max_teleport_distance", 15); // Max teleport distance in blocks
                tiersConfig.set("tier 4.cooldown", 15); // Cooldown in seconds
                tiersConfig.set("tier 4.damage_breaks_combo", true); // Whether taking damage breaks the combo
    
                // Tier 5
                tiersConfig.set("tier 5.effects", Arrays.asList(
                    "strength,8,6",      // effect: strength, level: 8, duration: 6 seconds
                    "speed,3,5",         // effect: speed, level: 3, duration: 5 seconds
                    "regeneration,2,5",  // effect: regeneration, level: 2, duration: 5 seconds
                    "fire_resistance,1,5"// effect: fire_resistance, level: 1, duration: 5 seconds
                ));
                tiersConfig.set("tier 5.combo_duration", 5); // Combo duration in seconds
                tiersConfig.set("tier 5.max_teleport_distance", 20); // Max teleport distance in blocks
                tiersConfig.set("tier 5.cooldown", 15); // Cooldown in seconds
                tiersConfig.set("tier 5.damage_breaks_combo", true); // Whether taking damage breaks the combo
    
                // Tier 6
                tiersConfig.set("tier 6.effects", Arrays.asList(
                    "strength,8,6",      // effect: strength, level: 8, duration: 6 seconds
                    "speed,3,5",         // effect: speed, level: 3, duration: 5 seconds
                    "regeneration,2,5",  // effect: regeneration, level: 2, duration: 5 seconds
                    "resistance,1,5",    // effect: resistance, level: 1, duration: 5 seconds
                    "fire_resistance,1,5",// effect: fire_resistance, level: 1, duration: 5 seconds
                    "invisibility,1,1"   // effect: invisibility, level: 1, duration: 1 second
                ));
                tiersConfig.set("tier 6.combo_duration", 5); // Combo duration in seconds
                tiersConfig.set("tier 6.max_teleport_distance", 20); // Max teleport distance in blocks
                tiersConfig.set("tier 6.cooldown", 10); // Cooldown in seconds
                tiersConfig.set("tier 6.damage_breaks_combo", true); // Whether taking damage breaks the combo
    
                // Tier 7
                tiersConfig.set("tier 7.effects", Arrays.asList(
                    "strength,9,6",      // effect: strength, level: 9, duration: 6 seconds
                    "speed,3,5",         // effect: speed, level: 3, duration: 5 seconds
                    "regeneration,2,5",  // effect: regeneration, level: 2, duration: 5 seconds
                    "resistance,1,5",    // effect: resistance, level: 1, duration: 5 seconds
                    "fire_resistance,1,5",// effect: fire_resistance, level: 1, duration: 5 seconds
                    "invisibility,1,2"   // effect: invisibility, level: 1, duration: 2 seconds
                ));
                tiersConfig.set("tier 7.combo_duration", 6); // Combo duration in seconds
                tiersConfig.set("tier 7.max_teleport_distance", 20); // Max teleport distance in blocks
                tiersConfig.set("tier 7.cooldown", 5); // Cooldown in seconds
                tiersConfig.set("tier 7.damage_breaks_combo", true); // Whether taking damage breaks the combo
    
                // Save the config file
                tiersConfig.save(tiersFile);
    
            } catch (IOException e) {
                getLogger().severe("Could not create tiers.yml file.");
            }
        }
    }
    

    
    private void createPlayerDataFile() {
        playerDataFile = new File(getDataFolder(), "playerdata.yml");
        if (!playerDataFile.exists()) {
            try {
                playerDataFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("Could not create playerdata.yml file.");
            }
        }
        playerData = YamlConfiguration.loadConfiguration(playerDataFile);
    }

    private void loadPlayerTiers() {
        playerTiers.clear();
        for (String uuidString : playerData.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidString);
                int tier = playerData.getInt(uuidString, 0);
                if (tier > 0) {
                    playerTiers.put(uuid, tier);
                }
            } catch (IllegalArgumentException e) {
                getLogger().warning("Invalid UUID in playerdata.yml: " + uuidString);
            }
        }
    }

    private void setPlayerTier(UUID uuid, int tier) {
        playerTiers.put(uuid, tier);
        
        // Save to playerdata.yml
        playerData.set(uuid.toString(), tier);
        try {
            playerData.save(playerDataFile);
        } catch (IOException e) {
            getLogger().severe("Could not save player tier to playerdata.yml");
        }
    }
}