# EllanWorldEvents

艾尔岚服务器的世界事件扩展，包含两套相互独立的玩法：

- 依据在线参战人数动态强化 EnderDragon 的四种末影龙，并加入 75%、50%、25% 三个战斗阶段。
- 调度 VoyageOfTheSeas 海防事件，广播目标坐标，记录伤害与在场时间，并按固定奖池发放金币、赤铁原声望和航海物资。

## 设计边界

本项目不包含或再分发 VoyageOfTheSeas 的付费资源。`EllanSeaEvents` MythicMobs 包只通过 `Template` 继承服务器已合法安装的实体，因此可以独立安装、更新和卸载。

## 构建

需要 JDK 21：

```bash
./gradlew clean test build
```

输出位于 `build/libs/EllanWorldEvents-1.0.0.jar`。

## 部署

1. 安装 MythicMobs、ModelEngine、MythicCrucible 和 VoyageOfTheSeas。
2. 将插件 JAR 放入每一台需要接收世界事件公告的后端服 `plugins/`。三服必须使用相同的 Redis 地址和频道。
3. 将 `src/main/resources/mythic-pack/EllanSeaEvents` 放入 `plugins/MythicMobs/Packs/`。
4. 将 Spawn 的 `spigot.yml` 中 `settings.attribute.maxHealth.max` 提升到至少 `65536.0`。
5. 启动服务器，在安全海面执行 `/ewe anchor add <名称>` 保存至少一个刷新点。

事件公告默认通过本机 Redis `127.0.0.1:6379` 的 `ellan:world-events` 频道同步，不依赖 CMI 的 `bbroadcast`。若三台后端不在同一台主机，请将 `config.yml` 中的 Redis 地址改为三台都能访问的内网地址，并设置密码。

## 管理命令

- `/ewe status`
- `/ewe start sea <galley|frigate|battleship|ocean_curse|random> [刷新点|random]`
- `/ewe stop`
- `/ewe anchor add <名称>`
- `/ewe anchor remove <名称>`
- `/ewe reload`

权限：`ellanworldevents.admin`

## PlaceholderAPI

- `%ellanworldevents_active%`
- `%ellanworldevents_name%`
- `%ellanworldevents_time_left%`
- `%ellanworldevents_coordinates%`

## 默认时段

- 每天 18:30：桨帆船或护卫舰。
- 每天 22:30：护卫舰、战列舰或海洋诅咒号。
- 每周六 22:30：固定海洋诅咒号，覆盖当晚的普通随机规则。

所有时间均按 `Asia/Shanghai` 计算，可以在 `config.yml` 修改。
