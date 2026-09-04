package com.ellanstudio.worldevents;

import com.ellanstudio.worldevents.placeholder.WorldEventsExpansion;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Map;

public final class EllanWorldEventsPlugin extends JavaPlugin {
    private File stateFile;
    private YamlConfiguration state;
    private SeaEventManager seaEvents;
    private DragonManager dragons;
    private WorldEventsExpansion expansion;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        stateFile = new File(getDataFolder(), "state.yml");
        state = YamlConfiguration.loadConfiguration(stateFile);

        seaEvents = new SeaEventManager(this);
        dragons = new DragonManager(this);

        Bukkit.getPluginManager().registerEvents(seaEvents, this);
        Bukkit.getPluginManager().registerEvents(dragons, this);

        WorldEventsCommand command = new WorldEventsCommand(this, seaEvents);
        if (getCommand("ellanworldevents") != null) {
            getCommand("ellanworldevents").setExecutor(command);
            getCommand("ellanworldevents").setTabCompleter(command);
        }

        seaEvents.enable();
        dragons.enable();
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            expansion = new WorldEventsExpansion(this, seaEvents);
            expansion.register();
        }
        getLogger().info("EllanWorldEvents enabled.");
    }

    @Override
    public void onDisable() {
        if (expansion != null) {
            expansion.unregister();
        }
        if (seaEvents != null) {
            seaEvents.disable();
        }
        if (dragons != null) {
            dragons.disable();
        }
        saveState();
    }

    public void reloadPlugin() {
        reloadConfig();
        seaEvents.reload();
        dragons.reload();
    }

    public YamlConfiguration state() {
        return state;
    }

    public synchronized void saveState() {
        try {
            state.save(stateFile);
        } catch (IOException exception) {
            getLogger().severe("Could not save state.yml: " + exception.getMessage());
        }
    }

    public String message(String key) {
        String prefix = getConfig().getString("messages.prefix", "");
        String value = getConfig().getString("messages." + key, key);
        return color(prefix + value);
    }

    public String message(String key, Map<String, ?> placeholders) {
        String prefix = getConfig().getString("messages.prefix", "");
        String value = prefix + getConfig().getString("messages." + key, key);
        for (Map.Entry<String, ?> entry : placeholders.entrySet()) {
            value = value.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        return color(value);
    }

    public String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value == null ? "" : value);
    }

    public String plain(String value) {
        return ChatColor.stripColor(color(value));
    }

    public void networkBroadcast(String coloredMessage) {
        String template = getConfig().getString("network-broadcast-command", "");
        if (template == null || template.isBlank()) {
            Bukkit.broadcastMessage(coloredMessage);
            return;
        }
        String command = template.replace("{message}", coloredMessage);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
    }
}
