package com.github.taran2k.sprintstrike;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class SprintStrikePlugin extends JavaPlugin implements Listener {

    private FileConfiguration lang;
    private FileConfiguration config;
    private FileConfiguration tiers;

    private final Map<UUID, Integer> playerTiers = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    @Override
    public void onEnable() {
        // Register events
        Bukkit.getPluginManager().registerEvents(this, this);

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

                if (target != null) {
                    playerTiers.put(target.getUniqueId(), level);
                    sender.sendMessage("Set sprint strike tier to " + level + " for " + target.getName() + ".");
                } else {
                    sender.sendMessage("Player not found.");
                }
            } catch (NumberFormatException e) {
                sender.sendMessage("Level must be a number.");
            }
            return true;
        });

        // Load configuration files
        saveDefaultConfig();
        createLangFile();
        createTiersFile();

        config = getConfig();
        lang = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "lang.yml"));
        tiers = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "tiers.yml"));
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
            sprintStrike(player, target, tier);
            cooldowns.put(playerId, currentTime); // Set cooldown
        }
    }

    private Entity getTargetEntity(Player player, int maxDistance) {
        for (Entity entity : player.getNearbyEntities(maxDistance, maxDistance, maxDistance)) {
            if (entity.getType() != EntityType.PLAYER && player.hasLineOfSight(entity)) {
                return entity;
            }
        }
        return null;
    }

    private void sprintStrike(Player player, Entity target, int tier) {
        Location mobLocation = target.getLocation();

        if (mobLocation.getBlock().getType() == Material.AIR) {
            sendMessage(player, "ErrorMobInAir");
            return;
        }

        Location safeLocation = findSafeLocationNear(mobLocation, player);
        if (safeLocation == null) {
            sendMessage(player, "ErrorNoSpace");
            return;
        }

        player.teleport(safeLocation);
        player.setRotation(mobLocation.getYaw(), mobLocation.getPitch());

        applyEffects(player, tier);
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
        long highestCooldown = 0;
        for (int i = 1; i <= tier; i++) {
            long tierCooldown = tiers.getLong("tier " + i + ".cooldown", 0);
            highestCooldown = Math.max(highestCooldown, tierCooldown);
        }
        return highestCooldown;
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
}
