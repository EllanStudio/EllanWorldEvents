package com.ellanstudio.worldevents;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class DragonManager implements Listener {
    private final EllanWorldEventsPlugin plugin;
    private final NamespacedKey crystalKey;
    private final Map<String, DragonType> types = new HashMap<>();
    private final Map<UUID, DragonFight> fights = new HashMap<>();
    private BukkitTask ticker;
    private boolean enabled;
    private double participantRadius;
    private double scalePerPlayer;
    private int maxScalingPlayers;
    private double shieldMultiplier;
    private int shieldTimeoutSeconds;
    private double enrageMultiplier;

    DragonManager(EllanWorldEventsPlugin plugin) {
        this.plugin = plugin;
        crystalKey = new NamespacedKey(plugin, "dragon_phase_crystal");
    }

    void enable() {
        reload();
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 100L, 10L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> Bukkit.getWorlds().stream()
                .flatMap(world -> world.getEntitiesByClass(EnderDragon.class).stream())
                .forEach(this::registerDragon), 80L);
    }

    void disable() {
        if (ticker != null) {
            ticker.cancel();
        }
        fights.values().forEach(this::removeCrystals);
        fights.clear();
    }

    void reload() {
        enabled = plugin.getConfig().getBoolean("dragons.enabled", true);
        participantRadius = plugin.getConfig().getDouble("dragons.participant-radius", 256.0);
        scalePerPlayer = plugin.getConfig().getDouble("dragons.participant-scale-per-extra-player", 0.30);
        maxScalingPlayers = plugin.getConfig().getInt("dragons.max-scaling-players", 8);
        shieldMultiplier = plugin.getConfig().getDouble("dragons.crystal-shield-damage-multiplier", 0.30);
        shieldTimeoutSeconds = plugin.getConfig().getInt("dragons.crystal-shield-timeout-seconds", 30);
        enrageMultiplier = plugin.getConfig().getDouble("dragons.enrage-damage-multiplier", 1.25);
        types.clear();
        var root = plugin.getConfig().getConfigurationSection("dragons.types");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            var section = root.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            types.put(key, new DragonType(
                    key,
                    section.getString("display-name-contains", key),
                    section.getDouble("base-health", 4000.0),
                    section.getDouble("per-hit-cap-percent", 0.04)
            ));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(EntitySpawnEvent event) {
        if (enabled && event.getEntity() instanceof EnderDragon dragon) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> registerDragon(dragon), 60L);
        }
    }

    private void registerDragon(EnderDragon dragon) {
        if (!enabled || !dragon.isValid() || fights.containsKey(dragon.getUniqueId())) {
            return;
        }
        DragonType type = identify(dragon);
        if (type == null) {
            return;
        }
        AttributeInstance maxHealth = dragon.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealth == null) {
            return;
        }
        double ratio = maxHealth.getValue() <= 0.0 ? 1.0 : dragon.getHealth() / maxHealth.getValue();
        maxHealth.setBaseValue(type.baseHealth);
        dragon.setHealth(Math.max(1.0, Math.min(type.baseHealth, type.baseHealth * ratio)));
        fights.put(dragon.getUniqueId(), new DragonFight(dragon.getUniqueId(), type));
        plugin.getLogger().info("Registered enhanced dragon " + type.key + " with base health " + type.baseHealth + ".");
    }

    private DragonType identify(EnderDragon dragon) {
        String name = ChatColor.stripColor(dragon.getCustomName());
        if (name == null) {
            return null;
        }
        return types.values().stream()
                .filter(type -> name.contains(plugin.plain(type.displayNameContains)))
                .findFirst().orElse(null);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDragonDamage(EntityDamageByEntityEvent event) {
        if (!enabled) {
            return;
        }
        if (event.getEntity() instanceof EnderDragon dragon) {
            DragonFight fight = fights.get(dragon.getUniqueId());
            if (fight == null) {
                registerDragon(dragon);
                fight = fights.get(dragon.getUniqueId());
            }
            if (fight == null) {
                return;
            }
            if (!fight.scaled && responsiblePlayer(event.getDamager()) != null) {
                applyParticipantScaling(dragon, fight);
            }
            double damage = event.getDamage();
            if (hasLivingCrystals(fight)) {
                damage *= shieldMultiplier;
            }
            double cap = dragon.getAttribute(Attribute.MAX_HEALTH).getValue() * fight.type.perHitCapPercent;
            event.setDamage(Math.min(damage, cap));
            return;
        }
        if (event.getDamager() instanceof EnderDragon dragon) {
            DragonFight fight = fights.get(dragon.getUniqueId());
            if (fight != null && fight.enraged) {
                event.setDamage(event.getDamage() * enrageMultiplier);
            }
        }
    }

    private Player responsiblePlayer(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof org.bukkit.entity.Projectile projectile
                && projectile.getShooter() instanceof Player player) {
            return player;
        }
        return null;
    }

    private void applyParticipantScaling(EnderDragon dragon, DragonFight fight) {
        double radiusSquared = participantRadius * participantRadius;
        int players = (int) dragon.getWorld().getPlayers().stream()
                .filter(player -> player.getGameMode() != GameMode.SPECTATOR)
                .filter(player -> !player.isDead())
                .filter(player -> player.getLocation().distanceSquared(dragon.getLocation()) <= radiusSquared)
                .count();
        players = Math.max(1, Math.min(maxScalingPlayers, players));
        double scaledHealth = fight.type.baseHealth * (1.0 + scalePerPlayer * (players - 1));
        AttributeInstance attribute = dragon.getAttribute(Attribute.MAX_HEALTH);
        if (attribute == null) {
            return;
        }
        double additional = Math.max(0.0, scaledHealth - attribute.getValue());
        attribute.setBaseValue(scaledHealth);
        dragon.setHealth(Math.min(scaledHealth, dragon.getHealth() + additional));
        fight.scaled = true;
        fight.scalingPlayers = players;
        broadcastToWorld(dragon.getWorld(), plugin.message("dragon-scaled", Map.of(
                "name", displayName(dragon, fight),
                "players", players,
                "health", String.format(Locale.ROOT, "%.0f", scaledHealth)
        )));
    }

    private void tick() {
        if (!enabled) {
            return;
        }
        for (DragonFight fight : new ArrayList<>(fights.values())) {
            Entity raw = Bukkit.getEntity(fight.dragonUuid);
            if (!(raw instanceof EnderDragon dragon) || !dragon.isValid() || dragon.isDead()) {
                removeCrystals(fight);
                fights.remove(fight.dragonUuid);
                continue;
            }
            AttributeInstance max = dragon.getAttribute(Attribute.MAX_HEALTH);
            if (max == null || max.getValue() <= 0.0) {
                continue;
            }
            double percent = dragon.getHealth() / max.getValue();
            if (!fight.phase75 && percent <= 0.75) {
                fight.phase75 = true;
                knockBackPlayers(dragon);
                broadcastToWorld(dragon.getWorld(), plugin.message("dragon-phase-75", Map.of(
                        "name", displayName(dragon, fight)
                )));
            }
            if (!fight.phase50 && percent <= 0.50) {
                fight.phase50 = true;
                spawnCrystals(dragon, fight);
                broadcastToWorld(dragon.getWorld(), plugin.message("dragon-phase-50", Map.of(
                        "name", displayName(dragon, fight)
                )));
            }
            if (!fight.phase25 && percent <= 0.25) {
                fight.phase25 = true;
                fight.enraged = true;
                broadcastToWorld(dragon.getWorld(), plugin.message("dragon-phase-25", Map.of(
                        "name", displayName(dragon, fight)
                )));
            }
            if (fight.crystalExpiresAt > 0 && System.currentTimeMillis() >= fight.crystalExpiresAt) {
                removeCrystals(fight);
                fight.crystalExpiresAt = 0;
            }
        }
    }

    private void knockBackPlayers(EnderDragon dragon) {
        Location center = dragon.getLocation();
        for (Player player : dragon.getWorld().getPlayers()) {
            if (player.getGameMode() == GameMode.SPECTATOR || player.getLocation().distanceSquared(center) > 80 * 80) {
                continue;
            }
            Vector direction = player.getLocation().toVector().subtract(center.toVector());
            if (direction.lengthSquared() < 0.01) {
                direction = new Vector(1, 0, 0);
            }
            player.setVelocity(direction.normalize().multiply(1.1).setY(0.45));
        }
    }

    private void spawnCrystals(EnderDragon dragon, DragonFight fight) {
        Location center = dragon.getLocation();
        double y = Math.max(55.0, center.getY() - 6.0);
        int[][] offsets = {{14, 0}, {-14, 0}, {0, 14}, {0, -14}};
        for (int[] offset : offsets) {
            Location location = new Location(dragon.getWorld(), center.getX() + offset[0], y, center.getZ() + offset[1]);
            EnderCrystal crystal = dragon.getWorld().spawn(location, EnderCrystal.class, spawned -> {
                spawned.setShowingBottom(true);
                spawned.setBeamTarget(center);
                spawned.getPersistentDataContainer().set(crystalKey, PersistentDataType.BYTE, (byte) 1);
            });
            fight.crystals.add(crystal.getUniqueId());
        }
        fight.crystalExpiresAt = System.currentTimeMillis() + shieldTimeoutSeconds * 1000L;
    }

    private boolean hasLivingCrystals(DragonFight fight) {
        fight.crystals.removeIf(uuid -> {
            Entity entity = Bukkit.getEntity(uuid);
            return entity == null || !entity.isValid() || entity.isDead();
        });
        return !fight.crystals.isEmpty();
    }

    private void removeCrystals(DragonFight fight) {
        for (UUID uuid : new HashSet<>(fight.crystals)) {
            Entity entity = Bukkit.getEntity(uuid);
            if (entity != null) {
                entity.remove();
            }
        }
        fight.crystals.clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCrystalExplode(EntityExplodeEvent event) {
        if (event.getEntity() instanceof EnderCrystal crystal
                && crystal.getPersistentDataContainer().has(crystalKey, PersistentDataType.BYTE)) {
            event.blockList().clear();
            event.setYield(0.0f);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDragonDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof EnderDragon dragon) {
            DragonFight fight = fights.remove(dragon.getUniqueId());
            if (fight != null) {
                removeCrystals(fight);
            }
        }
    }

    private String displayName(EnderDragon dragon, DragonFight fight) {
        return dragon.getCustomName() == null ? fight.type.displayNameContains : dragon.getCustomName();
    }

    private void broadcastToWorld(World world, String message) {
        world.getPlayers().forEach(player -> player.sendMessage(message));
        Bukkit.getConsoleSender().sendMessage(message);
    }

    private record DragonType(String key, String displayNameContains, double baseHealth, double perHitCapPercent) {
    }

    private static final class DragonFight {
        private final UUID dragonUuid;
        private final DragonType type;
        private final Set<UUID> crystals = new HashSet<>();
        private boolean scaled;
        private int scalingPlayers;
        private boolean phase75;
        private boolean phase50;
        private boolean phase25;
        private boolean enraged;
        private long crystalExpiresAt;

        private DragonFight(UUID dragonUuid, DragonType type) {
            this.dragonUuid = dragonUuid;
            this.type = type;
        }
    }
}
