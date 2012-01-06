package se.troed.plugin.Courier;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.Event.Priority;
import org.bukkit.inventory.ItemStack;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.logging.Level;

/**
 *
 * Courier - a Minecraft player to player realistic mail plugin
 *
 * Courier letter maps are uniquely defined by their x-value being 2147087904 (INT_MAX - 395743)
 * - I find it unlikely anyone will ever seriously craft a map at that location, it will have to do.
 * - Other plugin developers can use this fact to skip Courier Letters when they traverse maps
 * - I'll also likely need to use it when I finally solve map recycling.
 *
 * Additionally, Courier letter z-value is the unix timestamp when they were created.
 *
 * How to deal with players who NEVER read their mail. We'll spawn an immense number of postmen
 * and Items over time! I do not track how many times a single mail has been delivered, maybe I should?
 *
 * For recycling purposes, good info:
 * - http://www.minecraftwiki.net/wiki/Map_Item_Format
 *
 // switching out maps dyamically for the user will fail a lot of cases where itemheldevent
 // isn't triggered. that might be fixed though, but isn't currently:
 // https://bukkit.atlassian.net/browse/BUKKIT-437
 // if it WAS to be fixed, then maybe I could use one _real_ map and just fake all the others
 // switching out dynamically based on our magic X-value
 *
 * "using the entity unique id to track my Postmen and I should be able to use the same unique id for
 * (Map) Items. If so, I could use those as keys into the message db instead of actual Map IDs and then
 * use a single Map for its canvas when rendering."
 *
 * = /letter creates an entry in the database, with it's own UUID (unrelated to Minecraft)
 *   and immediately creates a MapItem according to below for the player
 *   Problem: MapItem doesn't really exist, we can only get an ItemStack(MAP,1,mapid) - previously
 *     we relied on mapid as our uuid, now we need a new one that persists. However, that only exists
 *     in Entity (extended by Item) and we do not have an Item! Thus, there's no way to track a Map Item
 *     (is it really anything but a lot of ItemStacks ever?) throughout the world. It might be that maps are
 *     the only uniquely identifiable items in Minecraft, and that my original approach was correct.
 *
 *     if so, we should still have our own letteruuid which mapid maps (hah) towards, making it possible to
 *     re-map mapids to other letteruuids. we could also store a back reference to which mapids reference
 *     which letteruuids - making it possible to immediately recycle those maps when a letter is deleted or
 *     recycled.
 *
 *     we would need a table which just lists all the mapids we've allocated for Courier of course. need
 *     to be able to detect that we've run out and trigger some form of deallocation of the oldest ones
 *     - and that should probably not be one per new message but free up a block of them when done. possibly
 *     a slow operation.
 *
 * + If I go the enchantment route, how to handle already existing ItemStacks with specific MapIds?
 *  -- detect Courier X value, remake them into the new Map Id and enchant.
 *
 * = deliveries create MapItems, and their UUIDs are mapped to the UUID in our database. One new such
 *   UUID mapping for each new Item we ever create point to a letter (to do - detect if they despawn and remove)
 *
 * = picking up item, check X for courier (Z now becomes worthless), lookup in itemuuids database
 *   and create the Letter object in LetterRenderer structure from letteruuids table.
 * = update newmail in player table if renderer says read and we loop through all mail to that player (ouch,
 *   sounds slow, but we could do it async?)
 *
 * = deliverythread checks newmail and sends out deliveries. new itemuuid if no itemuuid for that letteruuid
 *   already exists (sounds like a backwards lookup, hmm)
 *
 * ISSUE: Currently no quick rendering (sendMap) works. Is this fixable?
 *
 * Oh my it was ages since I last did database design. Bukkit persistence is Ebeans and objects.
 *
 * table player
 * key: player1 | data: newmail, itemuuid1, itemuuid2, itemuuid3
 * key: player2 | data: newmail, itemuuid2
 *
 * table itemuuids
 * key: itemuuid1 | letteruuid1
 *      itemuuid2 | letteruuid2
 *      itemuuid3 | letteruuid1
 *
 * table letteruuids
 * key: letteruuid1 | sender, player1, message, delivered, read, date
 *
 * table mapidpool
 * itemuuid1, itemuuid2, itemuuid3 etc ...
 *
 * create a conversion routine from messages.yml to database, run it every time the database disappears for
 * beta testing
 *
 * also initial config and beta testing is done: loop through mapId() from 0 until null, save in array. Find latest non-Courier map,
 * use the first courier map after that as our magic map. Try to reset the index.
 * (for betatest, just create a new one)
 *
 */
public class Courier extends JavaPlugin {
    // these must match plugin.yml
    public static final String CMD_POSTMAN = "postman";
    public static final String CMD_COURIER = "courier";
    public static final String CMD_POST = "post";
    public static final String PM_POSTMAN = "courier.postman";
    public static final String PM_SEND = "courier.send";
    public static final String PM_LIST = "courier.list";
    public static final String PM_INFO = "courier.info";
    public static final String PM_THEONEPERCENT = "courier.theonepercent";

    private final int MAGIC_NUMBER = Integer.MAX_VALUE - 395743; // used to id our map
    public static final int MAX_ID = Short.MAX_VALUE; // really, we don't do negative numbers well atm
    public static final int MIN_ID = 1;

    private static Vault vault = null;
    private static Economy economy = null;
    
    private final CourierEntityListener entityListener = new CourierEntityListener(this);
    private final CourierPlayerListener playerListener = new CourierPlayerListener(this);
    private final CourierServerListener serverListener = new CourierServerListener(this);
    private final CourierDeliveryListener deliveryListener = new CourierDeliveryListener(this);
    private final CourierCommands courierCommands = new CourierCommands(this);
    private final CourierDB courierdb = new CourierDB(this);
    private CourierConfig config;
    private LetterRenderer letterRenderer = null;

    private Runnable deliveryThread;
    private int deliveryId = -1;
    private final Map<UUID, Postman> postmen = new HashMap<UUID, Postman>();
    private final Map<Integer, Letter> letters = new HashMap<Integer, Letter>();
    // used temporarily in breaking spawn protections as well as making sure we only announce when spawned
    private final Map<Location, Postman> spawners = new HashMap<Location, Postman>();
    
    // postmen should never live long, will always despawn
    public void addPostman(Postman p) {
        postmen.put(p.getUUID(), p);
        schedulePostmanDespawn(p.getUUID(), getCConfig().getDespawnTime());
    }

    // returns null if it's not one of ours
    public Postman getPostman(UUID uuid) {
        return postmen.get(uuid);
    }
    
    public void addSpawner(Location l, Postman p) {
        // if this just keeps on growing we could detect and warn the admin that something is blocking
        // even our detection of Enderman spawn events. Regular cleanup thread?
        spawners.put(l, p);
        getCConfig().clog(Level.FINE, spawners.size() + " spawners in queue");
    }
    
    public Postman getAndRemoveSpawner(Location l) {
        Postman p = spawners.get(l);
        if(p != null) {
            spawners.remove(l);
        }
        return p;
    }
    
    private void addLetter(int id, Letter l) {
        letters.put(id, l);
    }

    // finds the Letter associated with a specific Map
    // recreates from db after restart
    public Letter getLetter(ItemStack letterItem) {
        if(letterItem == null) { // safety first
            return null;
        }
        Letter letter = letters.get(letterItem.getEnchantmentLevel(Enchantment.DURABILITY));
        if(letter == null) {
            // server has lost the MapView<->Letter associations, re-populate
            // todo: what does this return for an unenchanted item?
            // todo: I think it returns 0 ...
            int id = letterItem.getEnchantmentLevel(Enchantment.DURABILITY);
            String to = getCourierdb().getPlayer(id);
            if(to != null) {
                String from = getCourierdb().getSender(to, id);
                String message = getCourierdb().getMessage(to, id);
                letter = new Letter(from, to, message, id, getCourierdb().getRead(to, id));
                addLetter(id, letter);
                getCConfig().clog(Level.FINE, "New Letter " + id + " created for " + to);
            } else {
                // we've found an item pointing to a Courier letter that does not exist anylonger
                // ripe for re-use!
                getCConfig().clog(Level.FINE, "BAD: " + id + " not found in messages database");
            }
        }
        return letter;
    }
    
    public Economy getEconomy() {
        return economy;
    }

    /**
     * Picks a spot suitably in front of the player's eyes and checks to see if there's room 
     * for a postman (Enderman) to spawn in line-of-sight
     * 
     * Currently this can fail badly not checking whether we're on the same Y ..
     *
     * Also: Should be extended to check at least a few blocks to the sides and not JUST direct line of sight
     *
     */
    @SuppressWarnings("JavaDoc")
    Location findSpawnLocation(Player p) {
        Location sLoc = null;

        // o,o,o,o,o,o,x 
        List<Block> blocks = p.getLineOfSight(null, getCConfig().getSpawnDistance());
        if(blocks != null && !blocks.isEmpty()) {
            Block block = blocks.get(blocks.size()-1); // get last block
            getCConfig().clog(Level.FINE, "findSpawnLocation got lineOfSight");
            if(!block.isEmpty() && blocks.size()>1) {
                getCConfig().clog(Level.FINE, "findSpawnLocation got non-air last block");
                block = blocks.get(blocks.size()-2); // this SHOULD be an air block, then
            }
            if(block.isEmpty()) {
                // find bottom
                getCConfig().clog(Level.FINE, "findSpawnLocation air block");
                while(block.getRelative(BlockFace.DOWN, 1).isEmpty()) {
                    getCConfig().clog(Level.FINE, "findSpawnLocation going down ...");
                    block = block.getRelative(BlockFace.DOWN, 1);
                }
                // verify this is something we can stand on and that we fit
                if(!block.getRelative(BlockFace.DOWN, 1).isLiquid() && block.getRelative(BlockFace.UP, 1).isEmpty() && block.getRelative(BlockFace.UP, 2).isEmpty()) {
                    Location tLoc = block.getLocation();
                    getCConfig().clog(Level.FINE, "findSpawnLocation got location! [" + tLoc.getBlockX() + "," + tLoc.getBlockY() + "," + tLoc.getBlockZ() + "]");

                    // make sure we spawn in the middle of the blocks, not at the corner
                    sLoc = new Location(tLoc.getWorld(), tLoc.getBlockX()+0.5, tLoc.getBlockY(), tLoc.getBlockZ()+0.5);
                }
            }
        }
            
        if(sLoc == null) {
            getCConfig().clog(Level.FINE, "Didn't find room to spawn Postman");
            // fail
        }

        return sLoc;
    }

    public CourierDB getCourierdb() {
        return courierdb;
    }

    private void despawnPostman(UUID uuid) {
        config.clog(Level.FINE, "Despawning postman " + uuid);
        Postman postman = postmen.get(uuid);
        if(postman != null) {
            postman.remove();
            postmen.remove(uuid);
        } // else, shouldn't happen
    }

    public void schedulePostmanDespawn(final UUID uuid, int time) {
        // if there's an existing (long) timeout on a postman and a quick comes in, cancel the first and start the new
        // I don't know if it's long ... but I could add that info to Postman
        Runnable runnable = postmen.get(uuid).getRunnable();
        if(runnable != null) {
            config.clog(Level.FINE, "Cancel existing despawn on Postman " + uuid);
            getServer().getScheduler().cancelTask(postmen.get(uuid).getTaskId());    
        }
        runnable = new Runnable() {
            public void run() {
                despawnPostman(uuid);
            }
        };
        postmen.get(uuid).setRunnable(runnable);
        // in ticks. one tick = 50ms
        config.clog(Level.FINE, "Scheduled " + time + " second despawn for Postman " + uuid);
        int taskId = getServer().getScheduler().scheduleSyncDelayedTask(this, runnable, time*20);
        if(taskId >= 0) {
            postmen.get(uuid).setTaskId(taskId);
        } else {
            config.clog(Level.WARNING, "Despawning task scheduling failed");
        }
    }
    
    private void startDeliveryThread() {
        if(deliveryId >= 0) {
            config.clog(Level.WARNING, "Multiple calls to startDelivery()!");
        }
        if(deliveryThread == null) {
            deliveryThread = new Runnable() {
                public void run() {
                    deliverMail(); 
                }
            };
        }
        deliveryId = getServer().getScheduler().scheduleSyncRepeatingTask(this, deliveryThread, getCConfig().getInitialWait()*20, getCConfig().getNextRoute()*20);
        if(deliveryId < 0) {
            config.clog(Level.WARNING, "Delivery task scheduling failed");
        }
    }
    
/*    private void stopDeliveryThread() {
        if(deliveryId != -1) {
            getServer().getScheduler().cancelTask(deliveryId);
            deliveryId = -1;
        }
    }*/

    private void deliverMail() {
        // find first online player with undelivered mail
        // spawn new thread to deliver the mail
        Player[] players = getServer().getOnlinePlayers();
        for (Player player : players) {
            // I really need to remember which players have had a postman sent out even if they
            // haven't read their mail. Time to separate delivered and read ... maybe even picked up as well?
            // currently picked up count as delivered, maybe that's what it should as well :)

            // hmm I made all these changes and nothing uses read atm. Weird.
            if (courierdb.undeliveredMail(player.getName())) {
// is this lookup slow? it saves us in the extreme case new deliveries are scheduled faster than despawns
// oh my this was crappy coding. why did I add this, esp. considering it could never have worked?
//                if (!postmen.containsValue(player)) {
                    int undeliveredMessageId = getCourierdb().undeliveredMessageId(player.getName());
                    config.clog(Level.FINE, "Undelivered messageid: " + undeliveredMessageId);
                    if (undeliveredMessageId != -1) {
                        Location spawnLoc = findSpawnLocation(player);
                        if(spawnLoc != null && player.getWorld().hasStorm()) {
                            // I think I consider this to be a temporary solution to
                            // http://dev.bukkit.org/server-mods/courier/tickets/4-postmen-are-spawned-outside-even-if-its-raining/
                            // Also, do endermen get hurt by snowfall? (and damage events for endermen in rain are lacking in Bukkit, right?)
                            //
                            // hey. so rails on a block cause my findSpawnLocation to choose the block above
                            // I guess there are additional checks I should add. emptiness?
                            // todo: that also means we try to spawn a postman on top of rails even in rain
                            // todo: and glass blocks _don't_ seem to be included in "getHighest..." which I feel is wrong ("non-air")
                            config.clog(Level.FINE, "Top sky facing block at Y: " + player.getWorld().getHighestBlockYAt(spawnLoc));
                            if(player.getWorld().getHighestBlockYAt(spawnLoc) == spawnLoc.getBlockY()) {
                                spawnLoc = null;
                            }
                        }
                        if (spawnLoc != null) {
                            Postman postman = new Postman(this, player, undeliveredMessageId);
                            // separate instantiation from spawning, save spawnLoc in instantiation
                            // and create a new method to lookup unspawned locations. Use loc matching
                            // in onCreatureSpawn as mob-denier override variable.
                            this.addSpawner(spawnLoc, postman);
                            postman.spawn(spawnLoc);
                            // since we COULD be wrong when using location, re-check later if it indeed
                            // was a Postman we allowed through and despawn if not? Extra credit surely.
                            // Let's see if it's ever needed first
                            this.addPostman(postman);
                        }
                    } else {
                        config.clog(Level.SEVERE, "undeliveredMail and undeliveredMessageId not in sync: " + undeliveredMessageId);
                    }
//                }
            }
        }
    }

    public void startDeliveries() {
        startDeliveryThread();
        config.clog(Level.FINE, "Deliveries have started");
    }
    
    public void pauseDeliveries() {
        getServer().getScheduler().cancelTasks(this);
        for (Map.Entry<UUID, Postman> uuidPostmanEntry : postmen.entrySet()) {
            Postman postman = (Postman) ((Map.Entry) uuidPostmanEntry).getValue();
            if (postman != null) {
                postman.remove();
            }
        }
        courierdb.save();
        deliveryId = -1;
        config.clog(Level.FINE, "Deliveries are now paused");
    }
    
    public void onDisable() {
        pauseDeliveries();
        spawners.clear();
        config.clog(Level.INFO, this.getDescription().getName() + " is now disabled.");
    }

    public void onEnable() {
        this.loadConfig();
        courierdb.load();
        boolean abort = false;

        // Register our events
        PluginManager pm = getServer().getPluginManager();
        // Highest since we might need to override spawn deniers
        pm.registerEvent(Event.Type.CREATURE_SPAWN, entityListener, Priority.Highest, this);
        // I register as High on some events since I know I only modify for Endermen I've spawned
        pm.registerEvent(Event.Type.ENTITY_TARGET, entityListener, Priority.High, this);
        pm.registerEvent(Event.Type.ENTITY_DAMAGE, entityListener, Priority.High, this);
        pm.registerEvent(Event.Type.ENDERMAN_PICKUP, entityListener, Priority.High, this);
        pm.registerEvent(Event.Type.ENDERMAN_PLACE, entityListener, Priority.High, this);
        pm.registerEvent(Event.Type.PLAYER_QUIT, playerListener, Priority.Monitor, this);
        pm.registerEvent(Event.Type.PLAYER_JOIN, playerListener, Priority.Monitor, this);
        pm.registerEvent(Event.Type.PLAYER_INTERACT_ENTITY, playerListener, Priority.Monitor, this);
        pm.registerEvent(Event.Type.PLAYER_ITEM_HELD, playerListener, Priority.Monitor, this);
        pm.registerEvent(Event.Type.PLAYER_PICKUP_ITEM, playerListener, Priority.Monitor, this);
//        pm.registerEvent(Event.Type.MAP_INITIALIZE, serverListener, Priority.Normal, this);
//        pm.registerEvent(Event.Type.SERVER_COMMAND, courierCommands, Priority.Normal, this);
        pm.registerEvent(Event.Type.CUSTOM_EVENT, deliveryListener, Priority.Normal, this);

        getCommand(CMD_POSTMAN).setExecutor(courierCommands);
        getCommand(CMD_COURIER).setExecutor(courierCommands);
        getCommand(CMD_POST).setExecutor(courierCommands);

        // Prepare the magic Courier Map we use for all rendering
        short mapId = courierdb.getCourierMapId();
        if(mapId == -1) {
            // we don't have an allocated map stored, see if there is one we've forgotten about
            for(short i=0; i<Short.MAX_VALUE; i++) {
                MapView mv = getServer().getMap(i);
                if(mv != null && mv.getCenterX() == MAGIC_NUMBER) {
                    // there we go, a nice Courier Letter map
                    // todo: before release, convert all but one into enchanted maps
                    // or even, convert them all and allocate a new? safety net
                    mapId = i;
                    courierdb.setCourierMapId(mapId);
                    getCConfig().clog(Level.INFO, "Found existing Courier map with id " + mv.getId());
                    break;
                } else if(mv == null) {
                    // no Courier Maps found, we need to create one for our use
                    // Maps are saved in the world-folders, use default world
                    mv = getServer().createMap(getServer().getWorlds().get(0)); // todo: dunno. user configurable?
                    mv.setCenterX(MAGIC_NUMBER);
                    mapId = mv.getId();
                    getCConfig().clog(Level.INFO, "Rendering map claimed with the id " + mv.getId());
                    courierdb.setCourierMapId(mapId);
                    break;
                }
            }
        }
        if(mapId == -1) {
            getCConfig().clog(Level.SEVERE, "Could not allocate a Map. This is a fatal error.");
            abort = true;
        }

        if(!abort) {
            MapView mv = getServer().getMap(mapId);
            if(letterRenderer == null) {
                letterRenderer = new LetterRenderer(this);
            }
            letterRenderer.initialize(mv); // does this make a difference at all?
            List<MapRenderer> renderers = mv.getRenderers();
            for(MapRenderer r : renderers) { // remove existing renderers
                mv.removeRenderer(r);
            }
            mv.addRenderer(letterRenderer);
        }
        
        if(!abort && getServer().getOnlinePlayers().length > 0) {
            // players already on, we've been reloaded
            startDeliveries();
        }

        // if config says we should use economy, require vault + economy support
        if(!abort && config.getUseFees()) {
            Plugin x = getServer().getPluginManager().getPlugin("Vault");
            if(x != null && x instanceof Vault) {
                vault = (Vault) x;

                if(setupEconomy()) {
                    config.clog(Level.INFO, "Courier has linked to " + economy.getName() + " through Vault");
                } else {
                    config.clog(Level.SEVERE, "Vault could not find an Economy plugin installed!");
                    abort = true;
                }
            } else {
                config.clog(Level.SEVERE, "Courier relies on Vault for economy support and Vault isn't installed!");
                config.clog(Level.INFO, "See http://dev.bukkit.org/server-mods/vault/");
                config.clog(Level.INFO, "If you don't want economy support, set UseFees to false in Courier config.");
                abort = true;
            }
        }

        if(!abort) {
            PluginDescriptionFile pdfFile = this.getDescription();
            config.clog(Level.INFO, pdfFile.getName() + " version v" + pdfFile.getVersion() + " is enabled!");
        } else {
            setEnabled(false);
        }
    }

    // in preparation for plugin config dynamic reloading
    void loadConfig() {
        getConfig().options().copyDefaults(true);
        saveConfig();
        config = new CourierConfig(this);
    }

    public CourierConfig getCConfig() {
        return config;
    }

    private Boolean setupEconomy() {
        RegisteredServiceProvider<Economy> economyProvider = getServer().getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy.class);
        if (economyProvider != null) {
            economy = economyProvider.getProvider();
        }

        return (economy != null);
    }
}