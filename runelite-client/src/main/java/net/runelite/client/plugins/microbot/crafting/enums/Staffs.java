package net.runelite.client.plugins.microbot.crafting.enums;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.runelite.api.gameval.ItemID;

@Getter
@RequiredArgsConstructor
public enum Staffs {
    NONE(" ","",0,"", 0),
    WATER_BATTLESTAFF("Water Battlestaff", "Water Battlestaff", 54, "Water Orb", 571),
    EARTH_BATTLESTAFF("Earth battlestaff", "Earth battlestaff", 58, "Earth orb", 575),
    FIRE_BATTLESTAFF("Fire Battlestaff", "Fire Battlestaff", 62, "Fire Orb", 569),
    AIR_BATTLESTAFF("Air Battlestaff", "Air Battlestaff", 66, "Air Orb", 573),
    CRYSTAL("Crystal dust", "Crystal dust", 66, "Super combat potion(4)", ItemID.PRIF_CRYSTAL_SHARD_CRUSHED);

    private final String label;
    private final String itemName;
    private final int levelRequired;
    private final String orb;
    private final int id;
    @Override
    public String toString()
    {
        return label;
    }
}