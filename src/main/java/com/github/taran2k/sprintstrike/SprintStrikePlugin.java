package com.github.taran2k.sprintstrike;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
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

        List<String> permissions = getTierPermissions(tier);
        boolean hasComboPermission = permissions.contains("canTeleportCombo");

        // Check cooldown or combo countdown
        long currentTime = System.currentTimeMillis();
        
        if (hasComboPermission) {
            // Handle combo countdown
            if (handleComboCountdown(player)) {
                if (sprintStrike(player, target, tier)) {
                    // Reset combo countdown
                    startComboCountdown(player);
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

        if ((distance <= 5 && permissions.contains("canSprintStrikeFiveBlocks")) ||
            (distance <= 10 && permissions.contains("canSprintStrikeTenBlocks")) ||
            (distance <= 15 && permissions.contains("canSprintStrikeFifteenBlocks")) ||
            (distance <= 20 && permissions.contains("canSprintStrikeTwentyBlocks"))) {
            
            if (sprintStrike(player, target, tier)) {
                if (!hasComboPermission) {
                    cooldowns.put(playerId, currentTime);
                } else {
                    startComboCountdown(player);
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

            if (comboCountdowns.containsKey(playerId)) {
                cancelComboCountdown(player);
                sendMessage(player, "ComboInterrupted");
            }
        }
    }

    private boolean handleComboCountdown(Player player) {
        UUID playerId = player.getUniqueId();
        Long comboStart = comboCountdowns.get(playerId);

        if (comboStart == null) {
            return false;
        }

        long currentTime = System.currentTimeMillis();
        long elapsedTime = currentTime - comboStart;

        if (elapsedTime > 5000) {
            // Combo expired
            cancelComboCountdown(player);
            sendMessage(player, "ComboExpired");
            return false;
        }

        return true;
    }

    private void startComboCountdown(Player player) {
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
                long remainingTime = 5000 - (currentTime - comboStart);

                if (remainingTime <= 0) {
                    cancelComboCountdown(player);
                    sendMessage(player, "ComboExpired");
                    cancel();
                    return;
                }

                // Format the remaining time with seconds and milliseconds
                long seconds = remainingTime / 1000;
                long milliseconds = (remainingTime % 1000) / 10;
                String countdownMessage = String.format("Combo: %d.%02d", seconds, milliseconds);

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
        // Possible offset directions
        int[][] offsets = {{1,0,0}, {-1,0,0}, {0,0,1}, {0,0,-1}, {0,1,0}}; // also checking for y+1 f.e. for mobs on slabs or spiders

        for (int[] offset : offsets) {
            Location potentialLocation = mobLocation.clone().add(offset[0], offset[1], offset[2]);
            
            // Find safe y-level
            while (potentialLocation.getBlockY() > 0 && potentialLocation.getBlock().getType() == Material.AIR) {
                potentialLocation.subtract(0, 1, 0);
            }
            potentialLocation.add(0, 1, 0);

            // Check if location is safe (air block, no entities)
            if (potentialLocation.getBlock().getType() == Material.AIR && 
                player.getWorld().getNearbyEntities(potentialLocation, 0.5, 0.5, 0.5).isEmpty()) {
                return potentialLocation;
            }
        }
        return null;
    }

    private String formatMessage(String key, String... replacements) {
        String message = lang.getString(key, "Error: message not found");
        
        for (int i = 0; i < replacements.length; i += 2) {
            if (i + 1 < replacements.length) {
                message = message.replace(replacements[i], 
                    ChatColor.YELLOW + "" + ChatColor.BOLD + replacements[i + 1] + ChatColor.RESET);
            }
        }

        return colorize(message);
    }

    private String colorize(String message) {
        // Add color to different types of messages
        if (message.contains("Error:")) {
            return ChatColor.RED + message + ChatColor.RESET;
        } else if (message.contains("seconds")) {
            return message.replace("seconds", ChatColor.GOLD + "seconds" + ChatColor.RESET);
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

    private List<String> getTierPermissions(int tier) {
        List<String> permissions = new ArrayList<>();
        for (int i = 1; i <= tier; i++) {
            permissions.addAll(tiers.getStringList("tier " + i + ".permissions"));
        }
        return permissions;
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

    private void createLangFile() {
        File langFile = new File(getDataFolder(), "lang.yml");
        if (!langFile.exists()) { // Future issue: if a version update includes new messages, add them here without completely overriding custom lang files
            try {
                langFile.createNewFile();
                FileConfiguration langConfig = YamlConfiguration.loadConfiguration(langFile);
                
                // Comprehensive language configuration with placeholders
                langConfig.set("ErrorNoSpace", "No safe location to teleport to near the mob!");
                langConfig.set("ErrorCooldown", "You can use the sprint strike ability again in {X} seconds");
                langConfig.set("WrongWeapon", "You must hold a sword, axe, or stick to use Sprint Strike!");
                langConfig.set("CommandUsage", "Usage: /sprintstrike settier LEVEL [PLAYER]");
                langConfig.set("NoPermission", "You don't have permission to use this command.");
                langConfig.set("LevelTooLow", "Level must be greater than 0.");
                langConfig.set("NoSuchTier", "There is no such tier!");
                langConfig.set("TierSet", "Set sprint strike tier to {LEVEL} for {PLAYER}.");
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
                tiersConfig.set("tier 1.permissions", Arrays.asList("canSprintStrikeFiveBlocks"));
                tiersConfig.set("tier 1.effects", Arrays.asList("strength,2,2"));
                tiersConfig.set("tier 1.cooldown", 10);
                tiersConfig.set("tier 2.permissions", Arrays.asList("canSprintStrikeFiveBlocks"));
                tiersConfig.set("tier 2.effects", Arrays.asList("strength,4,2"));
                tiersConfig.set("tier 2.cooldown", 8);
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