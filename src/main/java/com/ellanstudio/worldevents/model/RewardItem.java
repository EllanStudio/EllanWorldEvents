package com.ellanstudio.worldevents.model;

public record RewardItem(String mythicId, int amount, double chance, String displayName) {
    public static RewardItem parse(String value) {
        String[] parts = value.trim().split("\\s+", 4);
        if (parts.length < 4) {
            throw new IllegalArgumentException("Reward item must be '<id> <amount> <chance> <display name>': " + value);
        }
        return new RewardItem(parts[0], Integer.parseInt(parts[1]), Double.parseDouble(parts[2]), parts[3]);
    }
}
