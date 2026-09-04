package com.ellanstudio.worldevents.model;

import java.util.List;

public record SeaTier(
        String key,
        String name,
        String mythicMob,
        double health,
        int coinPool,
        int perPlayerCoinCap,
        int reputation,
        List<RewardItem> itemRewards,
        RewardItem topReward
) {
}
