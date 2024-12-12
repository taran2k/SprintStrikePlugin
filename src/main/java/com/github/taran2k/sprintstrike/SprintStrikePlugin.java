package com.github.taran2k.sprintstrike;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

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
                sender.sendMessage("Usage: /sprintstrike settier LEVEL [PLAYER]");
                return true;
            }

            if (!sender.hasPermission("canSetSprintStrikeLevel")) {
                sender.sendMessage("You don't have permission to use this command.");
                return true;
            }

            try {
                int level = Integer.parseInt(args[1]);
                Player target = args.length > 2 ? Bukkit.getPlayer(args[2]) : (Player) sender;

                if (level < 1) {
                    sender.sendMessage("Level must be greater than 0.");
                    return true;
                }

                if (level > tiers.getKeys(false).size()) {
                    sender.sendMessage("There is no such tier!");
                    return true;
                }

                if (target != null) {
                    setPlayerTier(target.getUniqueId(), level);
                    sender.sendMessage("Set sprint strike tier to " + level + " for " + target.getName() + ".");
                } else {
                    sender.sendMessage("Player not found.");
                }
            } catch (NumberFormatException e) {
                sender.sendMessage("Level must be a number.");
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

        // Check cooldown
        long currentTime = System.currentTimeMillis();
        long cooldown = getTierCooldown(tier) * 1000L;
        if (cooldowns.containsKey(playerId) && (currentTime - cooldowns.get(playerId)) < cooldown) {
            long remainingTime = (cooldown - (currentTime - cooldowns.get(playerId))) / 1000;
            sendMessage(player, "ErrorCooldown", remainingTime);
            return;
        }

        Entity target = getTargetEntity(player, 20);
        if (target == null) return;
        double distance = player.getLocation().distance(target.getLocation());

        List<String> permissions = getTierPermissions(tier);
        if ((distance <= 5 && permissions.contains("canSprintStrikeFiveBlocks")) ||
            (distance <= 10 && permissions.contains("canSprintStrikeTenBlocks")) ||
            (distance <= 15 && permissions.contains("canSprintStrikeFifteenBlocks")) ||
            (distance <= 20 && permissions.contains("canSprintStrikeTwentyBlocks"))) {
            if (sprintStrike(player, target, tier)) {
                cooldowns.put(playerId, currentTime); // Set cooldown only if sprintStrike was succesful
            };
        }
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

        Location safeLocation = findSafeLocationNear(mobLocation, player);
        if (safeLocation == null) {
            sendMessage(player, "ErrorNoSpace");
            return false;
        }

        player.teleport(safeLocation);
        player.setRotation(mobLocation.getYaw(), mobLocation.getPitch());

        applyEffects(player, tier);
        return true;
    }

    private Location findSafeLocationNear(Location mobLocation, Player player) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Location potentialLocation = mobLocation.clone().add(dx, 0, dz);
                while (potentialLocation.getBlockY() > 0 && potentialLocation.getBlock().getType() == Material.AIR) {
                    potentialLocation.subtract(0, 1, 0);
                }
                potentialLocation.add(0, 1, 0);

                if (potentialLocation.getBlock().getType() == Material.AIR && !player.getWorld().getNearbyEntities(potentialLocation, 0.5, 0.5, 0.5).isEmpty()) {
                    return potentialLocation;
                }
            }
        }
        return null;
    }

    private void sendMessage(Player player, String key) {
        sendMessage(player, key, -1);
    }

    private void sendMessage(Player player, String key, long remainingTime) {
        String message = lang.getString(key, "Error: message not found");
        if (remainingTime >= 0) {
            message = message.replace("{X}", String.valueOf(remainingTime));
        }

        String messageType = config.getString("messageType", "chat");

        switch (messageType.toLowerCase()) {
            case "hotbar":
                player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, new net.md_5.bungee.api.chat.TextComponent(message));
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
        if (!langFile.exists()) {
            try {
                langFile.createNewFile();
                FileConfiguration langConfig = YamlConfiguration.loadConfiguration(langFile);
                langConfig.set("ErrorMobInAir", "The mob is in the air and cannot be reached!");
                langConfig.set("ErrorNoSpace", "No safe location to teleport to near the mob!");
                langConfig.set("ErrorCooldown", "You can use the sprint strike ability again in {X} seconds");
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
