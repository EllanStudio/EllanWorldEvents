package com.ellanstudio.worldevents;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class RewardAllocator {
    private RewardAllocator() {
    }

    static Map<UUID, Integer> allocate(int pool, int perPlayerCap, Map<UUID, Double> damage) {
        Map<UUID, Integer> result = new LinkedHashMap<>();
        if (pool <= 0 || damage.isEmpty()) {
            return result;
        }

        double totalDamage = damage.values().stream().mapToDouble(value -> Math.max(0.0, value)).sum();
        int equalPool = pool / 2;
        int contributionPool = pool - equalPool;
        int equalShare = equalPool / damage.size();

        for (Map.Entry<UUID, Double> entry : damage.entrySet()) {
            int contributionShare = totalDamage <= 0.0
                    ? contributionPool / damage.size()
                    : (int) Math.floor(contributionPool * Math.max(0.0, entry.getValue()) / totalDamage);
            int amount = Math.min(perPlayerCap, equalShare + contributionShare);
            result.put(entry.getKey(), Math.max(0, amount));
        }
        return result;
    }
}
