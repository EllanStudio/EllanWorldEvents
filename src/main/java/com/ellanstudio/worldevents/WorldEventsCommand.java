package com.ellanstudio.worldevents;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class WorldEventsCommand implements CommandExecutor, TabCompleter {
    private final EllanWorldEventsPlugin plugin;
    private final SeaEventManager seaEvents;

    WorldEventsCommand(EllanWorldEventsPlugin plugin, SeaEventManager seaEvents) {
        this.plugin = plugin;
        this.seaEvents = seaEvents;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("ellanworldevents.admin")) {
            sender.sendMessage(plugin.message("no-permission"));
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sender.sendMessage(plugin.color("&7[&3&l艾尔岚世界事件&7] &f" + seaEvents.status()));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reloadPlugin();
                sender.sendMessage(plugin.message("reloaded"));
            }
            case "start" -> {
                if (args.length < 2 || !args[1].equalsIgnoreCase("sea")) {
                    sender.sendMessage(plugin.color("&c用法：/" + label + " start sea [等级|random] [坐标点|random]"));
                    return true;
                }
                String tier = args.length >= 3 ? args[2] : "random";
                String anchor = args.length >= 4 ? args[3] : "random";
                seaEvents.startRandom(tier, anchor, sender);
            }
            case "stop" -> {
                if (seaEvents.stop(true)) {
                    sender.sendMessage(plugin.color("&a海防事件已结束并完成清理。"));
                } else {
                    sender.sendMessage(plugin.message("sea-not-active"));
                }
            }
            case "anchor" -> handleAnchor(sender, label, args);
            default -> sender.sendMessage(plugin.color("&7用法：/&f" + label + " <status|start|stop|reload|anchor>"));
        }
        return true;
    }

    private void handleAnchor(CommandSender sender, String label, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(plugin.color("&c用法：/" + label + " anchor <add|remove> <名称>"));
            return;
        }
        if (args[1].equalsIgnoreCase("add")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(plugin.color("&c只有玩家能以当前位置添加海域坐标。"));
                return;
            }
            seaEvents.addAnchor(args[2], player.getLocation());
            sender.sendMessage(plugin.color("&a已保存海域坐标 &f" + args[2] + "&a。"));
            return;
        }
        if (args[1].equalsIgnoreCase("remove")) {
            boolean removed = seaEvents.removeAnchor(args[2]);
            sender.sendMessage(plugin.color(removed ? "&a已删除海域坐标。" : "&c没有找到该海域坐标。"));
            return;
        }
        sender.sendMessage(plugin.color("&c用法：/" + label + " anchor <add|remove> <名称>"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> values = new ArrayList<>();
        if (args.length == 1) {
            values.addAll(List.of("status", "start", "stop", "reload", "anchor"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("start")) {
            values.add("sea");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("start") && args[1].equalsIgnoreCase("sea")) {
            values.add("random");
            values.addAll(seaEvents.tierKeys());
        } else if (args.length == 4 && args[0].equalsIgnoreCase("start") && args[1].equalsIgnoreCase("sea")) {
            values.add("random");
            values.addAll(seaEvents.anchorKeys());
        } else if (args.length == 2 && args[0].equalsIgnoreCase("anchor")) {
            values.addAll(List.of("add", "remove"));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("anchor") && args[1].equalsIgnoreCase("remove")) {
            values.addAll(seaEvents.anchorKeys());
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().toList();
    }
}
