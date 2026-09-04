package com.ellanstudio.worldevents.model;

import org.bukkit.Location;
import org.bukkit.World;

public record SeaAnchor(String id, String world, double x, double y, double z, float yaw) {
    public Location toLocation(World targetWorld) {
        double targetY = y >= 0 ? y : targetWorld.getHighestBlockYAt((int) x, (int) z) + 1.0;
        return new Location(targetWorld, x + 0.5, targetY, z + 0.5, yaw, 0.0f);
    }
}
