package com.darkyoddha.farSight.commands;

import com.darkyoddha.farSight.FarSight;
import com.darkyoddha.farSight.tasks.AutoGeneratorTask;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class FarSightCommand implements CommandExecutor, TabCompleter {

    private final FarSight plugin;

    public FarSightCommand(FarSight plugin) {
        this.plugin = plugin;
    }

    private String color(String msg) {
        return ChatColor.translateAlternateColorCodes('&', msg);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("farsight.admin") && !sender.isOp()) {
            sender.sendMessage(color("&cYou don't have permission to use this command."));
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();
        AutoGeneratorTask generatorTask = plugin.getAutoGeneratorTask();
        
        if (generatorTask == null) {
            sender.sendMessage(color("&cAutoGenerator feature is currently disabled or unavailable."));
            return true;
        }
        
        if (sub.equals("radius")) {
            if (args.length < 2) {
                sender.sendMessage(color("&cUsage: /farsight radius <number of blocks>"));
                return true;
            }
            try {
                int blocks = Integer.parseInt(args[1]);
                int radius = blocks / 16;
                int current = generatorTask.getProgressIndex();
                int newMax = (2 * radius + 1) * (2 * radius + 1);
                
                generatorTask.setRadius(radius);
                
                if (current >= newMax) {
                    sender.sendMessage(color("&a[FarSight] &eGeneration radius set to &b" + blocks + " blocks &e(" + radius + " chunks). Note: Those chunks have already been generated!"));
                } else {
                    sender.sendMessage(color("&a[FarSight] &eGeneration radius set to &b" + blocks + " blocks &e(" + radius + " chunks). Background generation is now enabled!"));
                }
            } catch (NumberFormatException e) {
                sender.sendMessage(color("&cPlease provide a valid number."));
            }
            return true;
        }
        
        if (sub.equals("autogenerate")) {
            if (args.length < 2) {
                sender.sendMessage(color("&cUsage: /farsight autogenerate <true|false>"));
                return true;
            }
            boolean val = Boolean.parseBoolean(args[1]);
            generatorTask.setAutoGenerate(val);
            sender.sendMessage(color("&a[FarSight] &eAuto-generation when server is empty is now set to: &b" + val));
            return true;
        }
        
        if (sub.equals("start")) {
            generatorTask.setGenerateForced(true);
            sender.sendMessage(color("&a[FarSight] &eForced generation &astarted&e!"));
            return true;
        }
        
        if (sub.equals("stop") || sub.equals("pause") || sub.equals("cancel")) {
            generatorTask.setGenerateForced(false);
            generatorTask.setAutoGenerate(false);
            sender.sendMessage(color("&a[FarSight] &eGeneration &cstopped&e (auto-generate disabled)."));
            return true;
        }
        
        if (sub.equals("center") || sub.equals("centre")) {
            int cx, cz;
            if (args.length >= 3) {
                try {
                    cx = Integer.parseInt(args[1]);
                    cz = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    sender.sendMessage(color("&cCoordinates must be valid integers (chunk coordinates, not block)."));
                    return true;
                }
            } else if (sender instanceof Player) {
                Player p = (Player) sender;
                cx = p.getLocation().getBlockX() >> 4;
                cz = p.getLocation().getBlockZ() >> 4;
            } else {
                sender.sendMessage(color("&cConsole must provide coordinates: /farsight center <chunkX> <chunkZ>"));
                return true;
            }
            generatorTask.setCenter(cx, cz);
            sender.sendMessage(color("&a[FarSight] &eGeneration center set to chunk: &b(" + cx + ", " + cz + ")"));
            return true;
        }
        
        if (sub.equals("status")) {
            sender.sendMessage(color("&a[FarSight Generator Status]"));
            sender.sendMessage(color("&eConfigured: &b" + generatorTask.isConfigured()));
            sender.sendMessage(color("&eRadius: &b" + generatorTask.getRadius()));
            int total = generatorTask.getMaxIndex();
            int current = generatorTask.getProgressIndex();
            double percent = total > 0 ? ((double) current / total) * 100 : 0;
            sender.sendMessage(String.format(color("&eProgress: &b%d / %d (%.2f%%)"), current, total, percent));
            return true;
        }

        sendHelp(sender);
        return true;
    }
    
    private void sendHelp(CommandSender sender) {
        sender.sendMessage(color("&a--- FarSight Commands ---"));
        sender.sendMessage(color("&e/farsight radius <blocks> &7- Set the generation radius (in blocks)"));
        sender.sendMessage(color("&e/farsight center [<x> <z>] &7- Set the generation center (defaults to your chunk)"));
        sender.sendMessage(color("&e/farsight autogenerate <true|false> &7- Toggle background generation when empty"));
        sender.sendMessage(color("&e/farsight start &7- Force start generation immediately"));
        sender.sendMessage(color("&e/farsight stop &7- Pause generation immediately"));
        sender.sendMessage(color("&e/farsight status &7- View generator progress"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (!sender.hasPermission("farsight.admin") && !sender.isOp()) {
            return completions;
        }

        if (args.length == 1) {
            List<String> subCommands = Arrays.asList("radius", "center", "centre", "autogenerate", "start", "stop", "pause", "cancel", "status");
            for (String sub : subCommands) {
                if (sub.startsWith(args[0].toLowerCase())) {
                    completions.add(sub);
                }
            }
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase();
            if (sub.equals("autogenerate")) {
                if ("true".startsWith(args[1].toLowerCase())) completions.add("true");
                if ("false".startsWith(args[1].toLowerCase())) completions.add("false");
            } else if (sub.equals("center") || sub.equals("centre")) {
                completions.add("<chunkX>");
            } else if (sub.equals("radius")) {
                completions.add("<number>");
            }
        } else if (args.length == 3) {
            String sub = args[0].toLowerCase();
            if (sub.equals("center") || sub.equals("centre")) {
                completions.add("<chunkZ>");
            }
        }

        return completions;
    }
}
