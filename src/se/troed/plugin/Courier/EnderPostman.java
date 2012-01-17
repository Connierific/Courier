package se.troed.plugin.Courier;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.CreatureType;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Player;
import org.bukkit.material.MaterialData;

public class EnderPostman extends Postman {

    EnderPostman(Courier plug, Player p, int id) {
        super(plug, p, id);
    }

/*    public CreatureType getType() {
        return CreatureType.ENDERMAN;
    }*/

    public void spawn(Location l) {
        postman = (Enderman) player.getWorld().spawnCreature(l, CreatureType.ENDERMAN);
        // gah, item vs block ...
        // MaterialData material = new MaterialData(Material.PAPER);
        ((Enderman)postman).setCarriedMaterial(new MaterialData(Material.BOOKSHELF));
        uuid = postman.getUniqueId();
    }

    @Override
    public void drop() {
        ((Enderman)postman).setCarriedMaterial(new MaterialData(Material.AIR));
        super.drop();
    }

}
