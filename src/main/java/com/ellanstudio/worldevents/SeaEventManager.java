package com.ellanstudio.worldevents;

import com.ellanstudio.worldevents.model.RewardItem;
import com.ellanstudio.worldevents.model.SeaAnchor;
import com.ellanstudio.worldevents.model.SeaTier;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.bukkit.events.MythicMobDeathEvent;
import io.lumine.mythic.core.items.MythicItem;
import io.lumine.mythic.core.mobs.ActiveMob;
import io.lumine.mythic.core.mobs.tracker.DamageSnapshotBundle;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.entity.Projectile;
import org.bukkit.scheduler.BukkitTask;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public final class SeaEventManager implements Listener {
    private final EllanWorldEventsPlugin plugin;
    private final NamespacedKey eventKey;
    private final Map<String, SeaTier> tiers = new LinkedHashMap<>();
    private final Map<String, SeaAnchor> anchors = new LinkedHashMap<>();
    private final List<ScheduleRule> schedules = new ArrayList<>();
    private ActiveSeaEvent active;
    private BukkitTask ticker;
    private BukkitTask stateSaver;
    private ZoneId zoneId = ZoneId.of("Asia/Shanghai");
    private int durationMinutes;
    private int nearbyRadius;
    private int nearbyQualifySeconds;
    private double minimumDamageShare;
    private int chunkTicketRadius;

    SeaEventManager(EllanWorldEventsPlugin plugin) {
        this.plugin = plugin;
        this.eventKey = new NamespacedKey(plugin, "sea_event");
    }

    void enable() {
        reload();
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 100L, 100L);
        stateSaver = Bukkit.getScheduler().runTaskTimer(plugin, this::saveActiveState, 1200L, 1200L);
        Bukkit.getScheduler().runTaskLater(plugin, this::restoreActiveEvent, 100L);
    }

    void disable() {
        if (ticker != null) {
            ticker.cancel();
        }
        if (stateSaver != null) {
            stateSaver.cancel();
        }
        saveActiveState();
        releaseChunkTickets();
    }

    void reload() {
        zoneId = ZoneId.of(plugin.getConfig().getString("timezone", "Asia/Shanghai"));
        durationMinutes = plugin.getConfig().getInt("sea-events.duration-minutes", 40);
        nearbyRadius = plugin.getConfig().getInt("sea-events.nearby-radius", 120);
        nearbyQualifySeconds = plugin.getConfig().getInt("sea-events.nearby-qualify-seconds", 90);
        minimumDamageShare = plugin.getConfig().getDouble("sea-events.minimum-damage-share", 0.02);
        chunkTicketRadius = Math.max(0, plugin.getConfig().getInt("sea-events.chunk-ticket-radius", 1));
        loadTiers();
        loadAnchors();
        loadSchedules();
    }

    private void loadTiers() {
        tiers.clear();
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("sea-events.tiers");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            List<RewardItem> rewards = new ArrayList<>();
            for (String value : section.getStringList("item-rewards")) {
                try {
                    rewards.add(RewardItem.parse(value));
                } catch (RuntimeException exception) {
                    plugin.getLogger().warning("Invalid item reward for " + key + ": " + exception.getMessage());
                }
            }
            RewardItem topReward = null;
            String topValue = section.getString("top-reward");
            if (topValue != null && !topValue.isBlank()) {
                try {
                    topReward = RewardItem.parse(topValue);
                } catch (RuntimeException exception) {
                    plugin.getLogger().warning("Invalid top reward for " + key + ": " + exception.getMessage());
                }
            }
            tiers.put(key.toLowerCase(Locale.ROOT), new SeaTier(
                    key.toLowerCase(Locale.ROOT),
                    section.getString("name", key),
                    section.getString("mythic-mob", key),
                    section.getDouble("health", 1000.0),
                    section.getInt("coin-pool", 0),
                    section.getInt("per-player-coin-cap", Integer.MAX_VALUE),
                    section.getInt("reputation", 0),
                    List.copyOf(rewards),
                    topReward
            ));
        }
    }

    @SuppressWarnings("unchecked")
    private void loadAnchors() {
        anchors.clear();
        for (Map<?, ?> raw : plugin.getConfig().getMapList("sea-events.anchors")) {
            try {
                String id = String.valueOf(raw.get("id"));
                Object worldValue = raw.containsKey("world")
                        ? raw.get("world")
                        : plugin.getConfig().getString("sea-events.world", "world");
                String world = String.valueOf(worldValue);
                double x = number(raw.get("x"), 0.0);
                double y = number(raw.get("y"), -1.0);
                double z = number(raw.get("z"), 0.0);
                float yaw = (float) number(raw.get("yaw"), 0.0);
                anchors.put(id.toLowerCase(Locale.ROOT), new SeaAnchor(id, world, x, y, z, yaw));
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Ignoring invalid sea anchor: " + raw);
            }
        }
    }

    private void loadSchedules() {
        schedules.clear();
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("sea-events.schedules");
        if (root == null) {
            return;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(id);
            if (section == null) {
                continue;
            }
            try {
                Set<DayOfWeek> days = section.getStringList("days").stream()
                        .map(value -> DayOfWeek.valueOf(value.toUpperCase(Locale.ROOT)))
                        .collect(Collectors.toCollection(() -> EnumSet.noneOf(DayOfWeek.class)));
                LocalTime time = LocalTime.parse(section.getString("time", "00:00"));
                List<String> ruleTiers = section.getStringList("tiers").stream()
                        .map(value -> value.toLowerCase(Locale.ROOT)).toList();
                schedules.add(new ScheduleRule(id, days, time, ruleTiers, section.getInt("priority", 0)));
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Ignoring invalid schedule " + id + ": " + exception.getMessage());
            }
        }
        schedules.sort(Comparator.comparingInt(ScheduleRule::priority).reversed());
    }

    public boolean startRandom(String requestedTier, String requestedAnchor, CommandSender sender) {
        if (active != null) {
            sender.sendMessage(plugin.message("sea-already-active"));
            return false;
        }
        if (tiers.isEmpty() || anchors.isEmpty()) {
            sender.sendMessage(plugin.message("sea-schedule-skipped", Map.of("reason", "未配置事件等级或海域坐标")));
            return false;
        }

        SeaTier tier;
        if (requestedTier == null || requestedTier.equalsIgnoreCase("random")) {
            tier = randomOf(tiers.values());
        } else {
            tier = tiers.get(requestedTier.toLowerCase(Locale.ROOT));
        }
        SeaAnchor anchor;
        if (requestedAnchor == null || requestedAnchor.equalsIgnoreCase("random")) {
            anchor = randomOf(anchors.values());
        } else {
            anchor = anchors.get(requestedAnchor.toLowerCase(Locale.ROOT));
        }
        if (tier == null || anchor == null) {
            sender.sendMessage(plugin.color("&c未找到指定的事件等级或海域坐标。"));
            return false;
        }
        return beginLoading(tier, anchor, sender);
    }

    private boolean beginLoading(SeaTier tier, SeaAnchor anchor, CommandSender sender) {
        World world = Bukkit.getWorld(anchor.world());
        if (world == null) {
            sender.sendMessage(plugin.color("&c世界未加载：" + anchor.world()));
            return false;
        }
        if (MythicBukkit.inst().getMobManager().getMythicMob(tier.mythicMob()).isEmpty()) {
            sender.sendMessage(plugin.color("&cMythicMobs 中不存在：" + tier.mythicMob()));
            return false;
        }

        Location target = anchor.toLocation(world);
        List<CompletableFuture<Chunk>> futures = new ArrayList<>();
        int centerX = target.getBlockX() >> 4;
        int centerZ = target.getBlockZ() >> 4;
        for (int x = centerX - chunkTicketRadius; x <= centerX + chunkTicketRadius; x++) {
            for (int z = centerZ - chunkTicketRadius; z <= centerZ + chunkTicketRadius; z++) {
                futures.add(world.getChunkAtAsync(x, z, true));
            }
        }
        sender.sendMessage(plugin.color("&7正在准备海防事件区块：&f" + plugin.plain(tier.name())));
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((unused, throwable) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (throwable != null) {
                        sender.sendMessage(plugin.color("&c海域区块加载失败：" + throwable.getMessage()));
                        return;
                    }
                    if (active != null) {
                        sender.sendMessage(plugin.message("sea-already-active"));
                        return;
                    }
                    spawn(tier, anchor, target);
                }));
        return true;
    }

    private void spawn(SeaTier tier, SeaAnchor anchor, Location target) {
        ActiveMob mob;
        try {
            mob = MythicBukkit.inst().getMobManager().spawnMob(tier.mythicMob(), target);
        } catch (RuntimeException exception) {
            releaseChunkTickets();
            plugin.getLogger().severe("Could not spawn " + tier.mythicMob() + ": " + exception.getMessage());
            return;
        }
        if (mob == null || mob.getEntity() == null) {
            releaseChunkTickets();
            plugin.getLogger().severe("MythicMobs returned no entity for " + tier.mythicMob());
            return;
        }
        Entity entity = mob.getEntity().getBukkitEntity();
        if (entity instanceof Mob bukkitMob) {
            bukkitMob.setPersistent(true);
            bukkitMob.setRemoveWhenFarAway(false);
        }
        mob.getEntity().setHealthAndMax(tier.health());
        entity.getPersistentDataContainer().set(eventKey, PersistentDataType.STRING, tier.key());

        long now = System.currentTimeMillis();
        active = new ActiveSeaEvent(mob.getUniqueId(), tier.key(), anchor.id(), now,
                now + durationMinutes * 60_000L, target.clone());
        updateChunkTickets(target, active.chunkTickets);
        saveActiveState();
        plugin.networkBroadcast(plugin.message("sea-started", Map.of(
                "name", tier.name(),
                "x", target.getBlockX(),
                "z", target.getBlockZ(),
                "minutes", durationMinutes
        )));
    }

    public boolean stop(boolean announce) {
        if (active == null) {
            return false;
        }
        SeaTier tier = tiers.get(active.tierKey);
        removeActiveMob();
        if (announce && tier != null) {
            plugin.networkBroadcast(plugin.message("sea-expired", Map.of("name", tier.name())));
        }
        clearActiveState();
        return true;
    }

    private void tick() {
        checkSchedule();
        if (active == null) {
            return;
        }
        Entity entity = Bukkit.getEntity(active.mobUuid);
        if (entity == null || !entity.isValid() || entity.isDead()) {
            active.missingTicks++;
            if (active.missingTicks >= 6) {
                plugin.getLogger().warning("Active sea boss disappeared; clearing event state.");
                clearActiveState();
            }
            return;
        }
        active.missingTicks = 0;
        active.lastLocation = entity.getLocation();
        updateChunkTickets(active.lastLocation, active.chunkTickets);
        countNearbyPlayers(entity.getLocation());

        long remaining = active.endsAt - System.currentTimeMillis();
        if (remaining <= 0) {
            stop(true);
            return;
        }
        int minutes = (int) Math.ceil(remaining / 60_000.0);
        if ((minutes == 10 || minutes == 5 || minutes == 1) && active.warnedMinutes.add(minutes)) {
            SeaTier tier = tiers.get(active.tierKey);
            if (tier != null) {
                plugin.networkBroadcast(plugin.message("sea-warning", Map.of(
                        "name", tier.name(),
                        "minutes", minutes,
                        "x", entity.getLocation().getBlockX(),
                        "z", entity.getLocation().getBlockZ()
                )));
            }
        }
    }

    private void countNearbyPlayers(Location location) {
        double radiusSquared = nearbyRadius * (double) nearbyRadius;
        for (Player player : location.getWorld().getPlayers()) {
            if (player.getGameMode() == GameMode.SPECTATOR || player.isDead()) {
                continue;
            }
            if (player.getLocation().distanceSquared(location) <= radiusSquared) {
                active.nearbySeconds.merge(player.getUniqueId(), 5, Integer::sum);
                active.names.put(player.getUniqueId(), player.getName());
            }
        }
    }

    private void checkSchedule() {
        if (!plugin.getConfig().getBoolean("sea-events.enabled", true) || active != null || anchors.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(zoneId);
        List<ScheduleRule> due = schedules.stream()
                .filter(rule -> ScheduleMatcher.matches(now, rule.days, rule.time))
                .filter(rule -> !plugin.state().getString("schedule-last-run." + rule.id, "").equals(now.toLocalDate().toString()))
                .toList();
        if (due.isEmpty()) {
            return;
        }
        for (ScheduleRule rule : due) {
            plugin.state().set("schedule-last-run." + rule.id, now.toLocalDate().toString());
        }
        plugin.saveState();

        ScheduleRule selected = due.getFirst();
        List<SeaTier> available = selected.tiers.stream().map(tiers::get).filter(java.util.Objects::nonNull).toList();
        SeaTier tier = randomOf(available);
        SeaAnchor anchor = randomOf(anchors.values());
        if (tier == null || anchor == null) {
            plugin.getLogger().warning("Scheduled sea event skipped because tier or anchor is unavailable.");
            return;
        }
        beginLoading(tier, anchor, Bukkit.getConsoleSender());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (active == null || !event.getEntity().getUniqueId().equals(active.mobUuid)) {
            return;
        }
        Player player = responsiblePlayer(event.getDamager());
        if (player != null) {
            active.damage.merge(player.getUniqueId(), event.getFinalDamage(), Double::sum);
            active.names.put(player.getUniqueId(), player.getName());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMythicDeath(MythicMobDeathEvent event) {
        if (active == null || !event.getMob().getUniqueId().equals(active.mobUuid)) {
            return;
        }
        Map<UUID, Double> damage = new HashMap<>(active.damage);
        var damageRecord = event.getMob().getDamageRecord();
        if (damageRecord != null) {
            for (Map.Entry<UUID, DamageSnapshotBundle> entry : damageRecord.getDamagingPlayers().entrySet()) {
                damage.merge(entry.getKey(), entry.getValue().getTotalDamage(), Math::max);
            }
        } else {
            plugin.getLogger().warning("MythicMobs did not provide a damage record for the sea boss; using tracked damage instead.");
        }
        UUID killer = event.getKiller() == null ? null : event.getKiller().getUniqueId();
        Bukkit.getScheduler().runTask(plugin, () -> completeEvent(damage, killer));
    }

    private void completeEvent(Map<UUID, Double> damage, UUID killer) {
        if (active == null) {
            return;
        }
        SeaTier tier = tiers.get(active.tierKey);
        if (tier == null) {
            clearActiveState();
            return;
        }
        double totalDamage = damage.values().stream().mapToDouble(Double::doubleValue).sum();
        Map<UUID, Double> eligible = new LinkedHashMap<>();
        Set<UUID> candidates = new HashSet<>(damage.keySet());
        candidates.addAll(active.nearbySeconds.keySet());
        for (UUID uuid : candidates) {
            double dealt = damage.getOrDefault(uuid, 0.0);
            double share = totalDamage <= 0.0 ? 0.0 : dealt / totalDamage;
            if (share >= minimumDamageShare || active.nearbySeconds.getOrDefault(uuid, 0) >= nearbyQualifySeconds) {
                eligible.put(uuid, dealt);
            }
        }
        if (eligible.isEmpty() && killer != null) {
            eligible.put(killer, damage.getOrDefault(killer, 0.0));
        }

        Map<UUID, Integer> coins = RewardAllocator.allocate(tier.coinPool(), tier.perPlayerCoinCap(), eligible);
        UUID top = eligible.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        for (Map.Entry<UUID, Double> entry : eligible.entrySet()) {
            UUID uuid = entry.getKey();
            PendingReward reward = new PendingReward(coins.getOrDefault(uuid, 0), tier.reputation());
            for (RewardItem item : tier.itemRewards()) {
                if (ThreadLocalRandom.current().nextDouble() <= item.chance()) {
                    reward.items.add(item);
                }
            }
            if (uuid.equals(top) && tier.topReward() != null
                    && ThreadLocalRandom.current().nextDouble() <= tier.topReward().chance()) {
                reward.items.add(tier.topReward());
            }
            grantOrQueue(uuid, reward);
        }
        plugin.networkBroadcast(plugin.message("sea-defeated", Map.of(
                "name", tier.name(), "count", eligible.size()
        )));
        clearActiveState();
    }

    private void grantOrQueue(UUID uuid, PendingReward reward) {
        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
        String name = Optional.ofNullable(offline.getName()).orElseGet(() -> active.names.get(uuid));
        if (name != null) {
            dispatchEconomy(name, reward.coins, reward.reputation);
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            grantItems(player, reward.items);
            player.sendMessage(plugin.message("sea-reward", Map.of(
                    "coins", reward.coins, "reputation", reward.reputation
            )));
            return;
        }
        if (!reward.items.isEmpty()) {
            writePending(uuid, reward.items);
        }
    }

    private void dispatchEconomy(String playerName, int coins, int reputation) {
        if (coins > 0) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ellan_coin give " + playerName + " " + coins + " -s -sf");
        }
        if (reputation > 0) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "redstone_reputation give " + playerName + " " + reputation + " -s -sf");
        }
    }

    private void grantItems(Player player, Collection<RewardItem> items) {
        for (RewardItem reward : items) {
            Optional<MythicItem> mythicItem = MythicBukkit.inst().getItemManager().getItem(reward.mythicId());
            if (mythicItem.isEmpty()) {
                plugin.getLogger().warning("Unknown MythicItem reward: " + reward.mythicId());
                continue;
            }
            ItemStack stack = io.lumine.mythic.bukkit.BukkitAdapter.adapt(mythicItem.get().generateItemStack(reward.amount()));
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(stack);
            leftovers.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
            player.sendMessage(plugin.message("sea-item-reward", Map.of(
                    "item", reward.displayName(), "amount", reward.amount()
            )));
        }
    }

    private void writePending(UUID uuid, Collection<RewardItem> items) {
        String path = "pending." + uuid + ".items";
        List<String> current = new ArrayList<>(plugin.state().getStringList(path));
        for (RewardItem item : items) {
            current.add(item.mythicId() + " " + item.amount() + " 1.0 " + item.displayName());
        }
        plugin.state().set(path, current);
        plugin.saveState();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        String path = "pending." + event.getPlayer().getUniqueId();
        List<String> values = plugin.state().getStringList(path + ".items");
        if (values.isEmpty()) {
            return;
        }
        List<RewardItem> items = values.stream().map(RewardItem::parse).toList();
        plugin.state().set(path, null);
        plugin.saveState();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            grantItems(event.getPlayer(), items);
            event.getPlayer().sendMessage(plugin.message("sea-pending-reward"));
        }, 40L);
    }

    private Player responsiblePlayer(Entity entity) {
        if (entity instanceof Player player) {
            return player;
        }
        if (entity instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) {
                return player;
            }
        }
        return null;
    }

    private void updateChunkTickets(Location location, Set<Long> existing) {
        World world = location.getWorld();
        int centerX = location.getBlockX() >> 4;
        int centerZ = location.getBlockZ() >> 4;
        Set<Long> desired = new HashSet<>();
        for (int x = centerX - chunkTicketRadius; x <= centerX + chunkTicketRadius; x++) {
            for (int z = centerZ - chunkTicketRadius; z <= centerZ + chunkTicketRadius; z++) {
                long key = chunkKey(x, z);
                desired.add(key);
                if (!existing.contains(key)) {
                    world.addPluginChunkTicket(x, z, plugin);
                }
            }
        }
        for (long key : new HashSet<>(existing)) {
            if (!desired.contains(key)) {
                world.removePluginChunkTicket(chunkX(key), chunkZ(key), plugin);
            }
        }
        existing.clear();
        existing.addAll(desired);
    }

    private void releaseChunkTickets() {
        if (active == null || active.lastLocation == null) {
            return;
        }
        World world = active.lastLocation.getWorld();
        for (long key : active.chunkTickets) {
            world.removePluginChunkTicket(chunkX(key), chunkZ(key), plugin);
        }
        active.chunkTickets.clear();
    }

    private void removeActiveMob() {
        if (active == null) {
            return;
        }
        MythicBukkit.inst().getMobManager().getActiveMob(active.mobUuid).ifPresent(mob -> {
            mob.getChildren().forEach(child -> child.remove());
            mob.remove();
        });
        Entity entity = Bukkit.getEntity(active.mobUuid);
        if (entity != null) {
            entity.remove();
        }
    }

    private void saveActiveState() {
        YamlConfiguration state = plugin.state();
        if (active == null) {
            state.set("active", null);
            plugin.saveState();
            return;
        }
        state.set("active.uuid", active.mobUuid.toString());
        state.set("active.tier", active.tierKey);
        state.set("active.anchor", active.anchorId);
        state.set("active.started-at", active.startedAt);
        state.set("active.ends-at", active.endsAt);
        state.set("active.world", active.lastLocation.getWorld().getName());
        state.set("active.x", active.lastLocation.getX());
        state.set("active.y", active.lastLocation.getY());
        state.set("active.z", active.lastLocation.getZ());
        state.set("active.damage", stringifyKeys(active.damage));
        state.set("active.nearby-seconds", stringifyKeys(active.nearbySeconds));
        state.set("active.names", stringifyKeys(active.names));
        state.set("active.warned-minutes", new ArrayList<>(active.warnedMinutes));
        plugin.saveState();
    }

    private Map<String, Object> stringifyKeys(Map<UUID, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((uuid, value) -> result.put(uuid.toString(), value));
        return result;
    }

    private void restoreActiveEvent() {
        ConfigurationSection section = plugin.state().getConfigurationSection("active");
        if (section == null) {
            return;
        }
        try {
            UUID uuid = UUID.fromString(section.getString("uuid", ""));
            World world = Bukkit.getWorld(section.getString("world", ""));
            if (world == null || System.currentTimeMillis() >= section.getLong("ends-at")) {
                clearActiveState();
                return;
            }
            Location location = new Location(world, section.getDouble("x"), section.getDouble("y"), section.getDouble("z"));
            world.getChunkAtAsync(location).thenRun(() -> Bukkit.getScheduler().runTask(plugin, () -> {
                Entity entity = Bukkit.getEntity(uuid);
                if (entity == null || !entity.isValid()) {
                    plugin.getLogger().warning("Stored sea event entity is no longer present; clearing state.");
                    clearActiveState();
                    return;
                }
                active = new ActiveSeaEvent(uuid, section.getString("tier", ""), section.getString("anchor", ""),
                        section.getLong("started-at"), section.getLong("ends-at"), location);
                restoreMap(section.getConfigurationSection("damage"), active.damage, Double::parseDouble);
                restoreMap(section.getConfigurationSection("nearby-seconds"), active.nearbySeconds, Integer::parseInt);
                restoreMap(section.getConfigurationSection("names"), active.names, value -> value);
                active.warnedMinutes.addAll(section.getIntegerList("warned-minutes"));
                updateChunkTickets(location, active.chunkTickets);
                plugin.getLogger().info("Restored active sea event " + active.tierKey + ".");
            }));
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Could not restore sea event: " + exception.getMessage());
            clearActiveState();
        }
    }

    private <T> void restoreMap(ConfigurationSection section, Map<UUID, T> target, java.util.function.Function<String, T> parser) {
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            target.put(UUID.fromString(key), parser.apply(String.valueOf(section.get(key))));
        }
    }

    private void clearActiveState() {
        releaseChunkTickets();
        active = null;
        plugin.state().set("active", null);
        plugin.saveState();
    }

    public void addAnchor(String id, Location location) {
        List<Map<?, ?>> list = new ArrayList<>(plugin.getConfig().getMapList("sea-events.anchors"));
        list.removeIf(raw -> id.equalsIgnoreCase(String.valueOf(raw.get("id"))));
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", id);
        value.put("world", location.getWorld().getName());
        value.put("x", location.getBlockX());
        value.put("y", location.getY());
        value.put("z", location.getBlockZ());
        value.put("yaw", location.getYaw());
        list.add(value);
        plugin.getConfig().set("sea-events.anchors", list);
        plugin.saveConfig();
        loadAnchors();
    }

    public boolean removeAnchor(String id) {
        List<Map<?, ?>> list = new ArrayList<>(plugin.getConfig().getMapList("sea-events.anchors"));
        boolean changed = list.removeIf(raw -> id.equalsIgnoreCase(String.valueOf(raw.get("id"))));
        if (changed) {
            plugin.getConfig().set("sea-events.anchors", list);
            plugin.saveConfig();
            loadAnchors();
        }
        return changed;
    }

    public String status() {
        if (active == null) {
            return "无进行中的海防事件";
        }
        SeaTier tier = tiers.get(active.tierKey);
        long seconds = Math.max(0, (active.endsAt - System.currentTimeMillis()) / 1000);
        return plugin.plain(tier == null ? active.tierKey : tier.name()) + "，剩余 "
                + seconds / 60 + "分" + seconds % 60 + "秒，坐标 "
                + active.lastLocation.getBlockX() + ", " + active.lastLocation.getBlockZ();
    }

    public boolean isActive() {
        return active != null;
    }

    public String activeName() {
        if (active == null) {
            return "无";
        }
        SeaTier tier = tiers.get(active.tierKey);
        return tier == null ? active.tierKey : plugin.plain(tier.name());
    }

    public String timeLeft() {
        if (active == null) {
            return "00:00";
        }
        long seconds = Math.max(0, (active.endsAt - System.currentTimeMillis()) / 1000);
        return String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    public String coordinates() {
        if (active == null) {
            return "-";
        }
        return active.lastLocation.getBlockX() + ", " + active.lastLocation.getBlockZ();
    }

    public Set<String> tierKeys() {
        return Set.copyOf(tiers.keySet());
    }

    public Set<String> anchorKeys() {
        return Set.copyOf(anchors.keySet());
    }

    private static double number(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private static <T> T randomOf(Collection<T> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        int index = ThreadLocalRandom.current().nextInt(values.size());
        return values.stream().skip(index).findFirst().orElse(null);
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static int chunkX(long key) {
        return (int) (key >> 32);
    }

    private static int chunkZ(long key) {
        return (int) key;
    }

    private record ScheduleRule(String id, Set<DayOfWeek> days, LocalTime time, List<String> tiers, int priority) {
    }

    private static final class ActiveSeaEvent {
        private final UUID mobUuid;
        private final String tierKey;
        private final String anchorId;
        private final long startedAt;
        private final long endsAt;
        private final Map<UUID, Double> damage = new HashMap<>();
        private final Map<UUID, Integer> nearbySeconds = new HashMap<>();
        private final Map<UUID, String> names = new HashMap<>();
        private final Set<Integer> warnedMinutes = new HashSet<>();
        private final Set<Long> chunkTickets = new HashSet<>();
        private Location lastLocation;
        private int missingTicks;

        private ActiveSeaEvent(UUID mobUuid, String tierKey, String anchorId, long startedAt, long endsAt, Location lastLocation) {
            this.mobUuid = mobUuid;
            this.tierKey = tierKey;
            this.anchorId = anchorId;
            this.startedAt = startedAt;
            this.endsAt = endsAt;
            this.lastLocation = lastLocation;
        }
    }

    private static final class PendingReward {
        private final int coins;
        private final int reputation;
        private final List<RewardItem> items = new ArrayList<>();

        private PendingReward(int coins, int reputation) {
            this.coins = coins;
            this.reputation = reputation;
        }
    }
}
