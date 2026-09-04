package com.ellanstudio.worldevents;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RewardAllocatorTest {
    @Test
    void splitsHalfEquallyAndHalfByContribution() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Map<UUID, Double> damage = new LinkedHashMap<>();
        damage.put(first, 75.0);
        damage.put(second, 25.0);

        Map<UUID, Integer> result = RewardAllocator.allocate(1000, 1000, damage);

        assertEquals(625, result.get(first));
        assertEquals(375, result.get(second));
    }

    @Test
    void respectsPerPlayerCap() {
        UUID player = UUID.randomUUID();
        Map<UUID, Integer> result = RewardAllocator.allocate(5000, 1300, Map.of(player, 100.0));
        assertEquals(1300, result.get(player));
    }

    @Test
    void emptyInputProducesNoRewards() {
        assertTrue(RewardAllocator.allocate(1000, 500, Map.of()).isEmpty());
    }
}
