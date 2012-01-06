package se.troed.plugin.Courier;

import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

import java.util.List;
import java.util.logging.Level;

import org.bukkit.ChatColor.*;

/**
 * Naughty: Implementing ServerCommands and onCommand in the same class
 * Nice: Implementing ServerCommands and onCommand in the same class
 */
class CourierCommands /*extends ServerListener*/ implements CommandExecutor {
    private final Courier plugin;

    public CourierCommands(Courier instance) {
        plugin = instance;
    }
    
    // Player is null for console
    // This method didn't turn out that well. Should send a message to sender, when console,
    // about why the command fails when we need a player object
    private boolean allowed(Player p, String c) {
        boolean a = false;
        if (p != null) {
            if(c.equals(Courier.CMD_POSTMAN) && p.hasPermission(Courier.PM_POSTMAN)) {
                a = true;
            } else if(c.equals(Courier.CMD_COURIER) && p.hasPermission(Courier.PM_INFO)) {
                a = true;
            } else if(c.equals(Courier.CMD_POST) && p.hasPermission(Courier.PM_SEND)) {
                a = true;
            }
            plugin.getCConfig().clog(Level.FINE, "Player command event");
        } else {
            // console operator is op, no player and no location
            if(c.equals(Courier.CMD_COURIER)) {
                a = true;
            }
            plugin.getCConfig().clog(Level.FINE, "Server command event");
        }
        plugin.getCConfig().clog(Level.FINE, "Permission: " + a);
        return a;
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        boolean ret = false;

        Player player = null;
        if (sender instanceof Player) {
            player = (Player) sender;
        }

        // sender is always safe to sendMessage to - console as well as player
        // player can from now on be null!

        String cmd = command.getName().toLowerCase();
        if(cmd.equals(Courier.CMD_COURIER) && allowed(player, cmd)) {
            // can be run from console, does not use player
            // courier fees = fee info
            // else general help
            if (args != null && args.length > 0 && args[0].equalsIgnoreCase("fees")) {
                if(plugin.getEconomy() != null) {
                    double fee = plugin.getCConfig().getFeeSend();
                    sender.sendMessage("Courier: The postage is " + plugin.getEconomy().format(fee));
                } else {
                    sender.sendMessage("Courier: There's no cost for sending mail on this server");
                }
            // todo: implement /courier list
            } else {
                sender.sendMessage(ChatColor.WHITE + "/courier fees " + ChatColor.GRAY + ": Lists cost, if any, for sending a mail");
                sender.sendMessage(ChatColor.WHITE + "/post playername message " + ChatColor.GRAY + ": Posts a mail to playername");
            }
            ret = true;
        } else if((cmd.equals(Courier.CMD_POST)) && allowed(player, cmd)) {
            // not allowed to be run from the console, uses player
            if(plugin.getEconomy() != null &&
               plugin.getEconomy().getBalance(player.getName()) < plugin.getCConfig().getFeeSend() &&
               !player.hasPermission(Courier.PM_THEONEPERCENT)) {
                    sender.sendMessage("Courier: Sorry, you don't have enough credit to cover postage (" + plugin.getEconomy().format(plugin.getCConfig().getFeeSend())+ ")");
                ret = true;
            } else if(args == null || args.length < 1) {
                sender.sendMessage("Courier: Error, no recipient for message!");
            } else if(args.length < 2) {
                sender.sendMessage("Courier: Error, message cannot be empty!");
            } else {
                OfflinePlayer[] offPlayers = plugin.getServer().getOfflinePlayers();
                OfflinePlayer p = null;
                for(OfflinePlayer o : offPlayers) {
                    if(o.getName().equalsIgnoreCase(args[0])) {
                       p = o;
                       plugin.getCConfig().clog(Level.FINE, "Found " + p.getName() + " in OfflinePlayers");
                       break;
                    }
                }
                if(p == null) { // todo: remove this section for 1.0.1-R2
                    // See https://bukkit.atlassian.net/browse/BUKKIT-404 by GICodeWarrior
                    // https://github.com/troed/Courier/issues/2
                    // We could end up here if this is to a player who's on the server for the first time
                    p = plugin.getServer().getPlayerExact(args[0]);
                    if(p != null) {
                        plugin.getCConfig().clog(Level.FINE, "Found " + p.getName() + " in getPlayerExact");
                    }
                }
                if(p == null) {
                    // still not found, try lazy matching and display suggestions
                    // (searches online players only)
                    List<Player> players = plugin.getServer().matchPlayer(args[0]);
                    if(players != null && players.size() == 1) {
                        // we got one exact match
                        // p = players.get(0); // don't, could be embarrassing if wrong
                        sender.sendMessage("Courier: Couldn't find " + args[0] + ". Did you mean " + players.get(0).getName() + "?");
                    } else if (players != null && players.size() > 1 && player.hasPermission(Courier.PM_LIST)) {
                      // more than one possible match found
                        StringBuilder suggestList = new StringBuilder();
                        int width = 0;
                        for(Player pl : players) {
                            suggestList.append(pl.getName());
                            suggestList.append(" ");
                            width += pl.getName().length()+1;
                            if(width >= 40) { // todo: how many chars can the console show?
                                suggestList.append("\n");
                                width = 0;
                            }
                        }
                        // players listing who's online. If so, that could be a permission also valid for /courier list
                        sender.sendMessage("Courier: Couldn't find " + args[0] + ". Did you mean anyone of these players?");
                        sender.sendMessage("Courier: " + suggestList.toString());
                    } else {
                        // time to give up
                        sender.sendMessage("Courier: There's no player on this server with the name " + args[0]);
                    }
                }
                if(p != null) {
                    if(plugin.getEconomy() != null && !player.hasPermission(Courier.PM_THEONEPERCENT)) {
                        // withdraw postage fee
                        double fee = plugin.getCConfig().getFeeSend();
                        EconomyResponse er = plugin.getEconomy().withdrawPlayer(player.getName(), fee);
                        if(er.transactionSuccess()) {
                            sender.sendMessage("Courier: Message to " + p.getName() + " sent! Postage fee of " + plugin.getEconomy().format(fee)+ " paid");
                        } else {
                            // todo: if this happens we still send the mail, but without visible confirmation.
                            plugin.getCConfig().clog(Level.WARNING, "Could not withdraw postage fee from " + p.getName());
                        }
                    } else {
                        sender.sendMessage("Courier: Message to " + p.getName() + " sent!");
                    }

                    // todo: figure out max length and show if a cutoff was made
                    // Minecraftfont isValid(message)

                    StringBuilder message = new StringBuilder();
                    for(int i=1; i<args.length; i++) {
                        // %loc -> [X,Y,Z] and such
                        // if this grows, break it out and make it configurable
                        if(args[i].equalsIgnoreCase("%loc") || args[i].equalsIgnoreCase("%pos")) {
                            Location loc = player.getLocation();
                            message.append("[" + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ() + "]");
                        } else {
                            message.append(args[i]);
                        }
                        message.append(" ");
                    }

                    // this is where we actually generate a uid for this message
                    plugin.getCourierdb().storeMessage(plugin.getCourierdb().generateUID(), p.getName(), sender.getName(),  message.toString());
                }
                ret = true;
            }
        } else if(cmd.equals(Courier.CMD_POSTMAN) && allowed(player, cmd)){
            // not allowed to be run from the console, uses player
            if(plugin.getCourierdb().undeliveredMail(player.getName())) {
                int undeliveredMessageId = plugin.getCourierdb().undeliveredMessageId(player.getName());
                if(undeliveredMessageId != -1) {
                    sender.sendMessage("You've got mail!");

                    // Is it the FIRST map viewed on server start that gets the wrong id when rendering?
                    // how can that be? if it's my code I don't see where ...

                    plugin.getCConfig().clog(Level.FINE, "MessageId: " + undeliveredMessageId);
                    String from = plugin.getCourierdb().getSender(player.getName(), undeliveredMessageId);
                    String message = plugin.getCourierdb().getMessage(player.getName(), undeliveredMessageId);
                    plugin.getCConfig().clog(Level.FINE, "Sender: " + from + " Message: " + message);
                    if(from != null && message != null) {
                        Location spawnLoc = plugin.findSpawnLocation(player);
                        if(spawnLoc != null) {
                            Postman postman = new Postman(plugin, player, undeliveredMessageId);
                            plugin.addSpawner(spawnLoc, postman);
                            postman.spawn(spawnLoc);
                            plugin.addPostman(postman);
                        }

                    } else {
                        plugin.getCConfig().clog(Level.SEVERE, "Gotmail but no sender or message found! mapId=" + undeliveredMessageId);
                    }
                } else {
                    plugin.getCConfig().clog(Level.WARNING, "Gotmail but no mailid!");
                }
            }
            ret = true;
        }
        return ret;
    }

/*    public void onServerCommand(ServerCommandEvent event) {
        plugin.getCConfig().clog(Level.FINE, "Server command event");
    }*/
}
