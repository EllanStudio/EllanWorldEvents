package com.ellanstudio.worldevents.placeholder;

import com.ellanstudio.worldevents.EllanWorldEventsPlugin;
import com.ellanstudio.worldevents.SeaEventManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class WorldEventsExpansion extends PlaceholderExpansion {
    private final EllanWorldEventsPlugin plugin;
    private final SeaEventManager seaEvents;

    public WorldEventsExpansion(EllanWorldEventsPlugin plugin, SeaEventManager seaEvents) {
        this.plugin = plugin;
        this.seaEvents = seaEvents;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "ellanworldevents";
    }

    @Override
    public @NotNull String getAuthor() {
        return "EllanStudio";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        return switch (params.toLowerCase()) {
            case "active" -> seaEvents.isActive() ? "是" : "否";
            case "name" -> seaEvents.activeName();
            case "time_left" -> seaEvents.timeLeft();
            case "coordinates" -> seaEvents.coordinates();
            default -> null;
        };
    }
}
