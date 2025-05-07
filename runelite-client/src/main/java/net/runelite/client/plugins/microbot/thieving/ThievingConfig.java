package net.runelite.client.plugins.microbot.thieving;

import net.runelite.client.config.*;
import net.runelite.client.plugins.microbot.thieving.enums.ThievingNpc;

@ConfigGroup("Thieving")
public interface ThievingConfig extends Config {

    @ConfigItem(
            keyName = "guide",
            name = "How to use",
            description = "How to use this plugin",
            position = 0,
            section = generalSection
    )
    default String GUIDE() {
        return "Start near any of the npc";
    }
    @ConfigSection(
            name = "general",
            description = "general",
            position = 0
    )
    String generalSection = "General";

    @ConfigItem(
            keyName = "Npc",
            name = "Npc",
            description = "Choose the npc to start thieving from",
            position = 0,
            section = generalSection
    )
    default ThievingNpc THIEVING_NPC()
    {
        return ThievingNpc.NONE;
    }

    @ConfigItem(
            keyName = "ardougneAreaCheck",
            name = "Ardy Knights Bank Area Check?",
            description = "Enforce Ardougne Knight to be in Ardougne Bank area",
            position = 1,
            section = generalSection
    )
    default boolean ardougneAreaCheck()
    {
        return false;
    }

    @ConfigSection(
            name = "buffs",
            description = "general",
            position = 0
    )
    String buffsSection = "Buffs";

    @ConfigItem(
            keyName = "shadowVeil",
            name = "Shadow veil",
            description = "Choose whether to shadow veil",
            position = 0,
            section = buffsSection
    )
    default boolean shadowVeil() {
        return false;
    }

    @ConfigSection(
            name = "Coin pouch & Items",
            description = "Coin pouch & Items",
            position = 2
    )
    String coinPouchSection = "Coin pouch & Items";

    @ConfigItem(
            keyName = "Coin Pouch Threshold",
            name = "How many coin pouches in your inventory before opening?",
            description = "How many coin pouches do you need in your inventory before opening them?",
            position = 1,
            section = coinPouchSection
    )
    default int coinPouchThreshold()
    {
        return 28;
    }

    @ConfigItem(
            keyName = "KeepItem",
            name = "Keep items above value",
            description = "Keep items above the gp value",
            position = 1,
            section = coinPouchSection
    )
    default int keepItemsAboveValue()
    {
        return 10000;
    }

    @ConfigItem(
            keyName = "DodgyNecklaceAmount",
            name = "Dodgy necklace Amount",
            description = "Amount of dodgy necklace to withdraw from bank",
            position = 1,
            section = coinPouchSection
    )
    default int dodgyNecklaceAmount()
    {
        return 5;
    }

    @ConfigItem(
            keyName = "DoNotDropitemList",
            name = "Do not drop item list",
            description = "Do not drop item list comma seperated",
            position = 1,
            section = coinPouchSection
    )
    default String DoNotDropItemList()
    {
        return "";
    }
    @ConfigSection(
            name = "House-Pool Healing",
            description = "Teleport to POH & use ornate pool when low HP",
            position = 3
    )
    String poolSection = "House-Pool Healing";

//    @ConfigItem(
//            keyName = "useHousePool",
//            name = "Use POH pool to heal",
//            description = "Teleport to Player-Owned House & use ornate rejuvenation pool when below HP threshold",
//            position = 0,
//            section = poolSection
//    )
//    default boolean useHousePool()
//    {
//        return false;
//    }

    @ConfigItem(
            keyName = "poolHpThreshold",
            name = "Pool at HP ≤ %",
            description = "If your current Hitpoints % falls to or below this, teleport to house and heal",
            position = 1,
            section = poolSection
    )
    @Range(min = 1, max = 99)
    default int poolHpThreshold()
    {
        return 10;
    }
}
