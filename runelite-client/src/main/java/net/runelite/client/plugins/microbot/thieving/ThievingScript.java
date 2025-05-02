package net.runelite.client.plugins.microbot.thieving;

import net.runelite.api.NPC;
import net.runelite.api.Skill;
import net.runelite.api.Varbits;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.game.npcoverlay.HighlightedNpc;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.thieving.enums.ThievingNpc;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.skillcalculator.skills.MagicAction;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static net.runelite.api.ObjectID.ORNATE_POOL_OF_REJUVENATION;
import static net.runelite.api.gameval.ObjectID.POH_EXIT_PORTAL;
import static net.runelite.api.gameval.ObjectID1.POH_PRIFDDINAS_PORTAL;
import static net.runelite.client.plugins.microbot.util.bank.enums.BankLocation.PRIFDDINAS;

public class ThievingScript extends Script
{
    public static String version = "1.6.2";
    private ThievingConfig config;
    private static final WorldPoint NPC_LINDIR_ELF = new WorldPoint(
            3244,
            6071,
            0
    );
    private static final WorldPoint HOUSE_PORTAL_LOCATION = new WorldPoint(3239, 6076, 0);
    private static final int DOOR_CLOSED_ID = 36253;
    private static final int DOOR_OPEN_ID   = 36254;

    public boolean run(ThievingConfig config)
    {
        this.config = config;
        Microbot.isCantReachTargetDetectionEnabled = true;
        Microbot.enableAutoRunOn = false;
        Rs2Antiban.resetAntibanSettings();
        Rs2Antiban.antibanSetupTemplates.applyThievingSetup();

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() ->
        {
            try
            {
                if (!Microbot.isLoggedIn() || !super.run())
                    return;
// 0) If we’ve picked up any “23959” items, bank them and return home
                if (Rs2Inventory.hasItem(23959))
                {
                    depositEnhancedCrystalTeleportSeed();
                    return;
                }
//                if (initialPlayerLocation == null)
//                    initialPlayerLocation = Rs2Player.getWorldLocation();

                // Stash runes when stunned (25% chance)
                if (Rs2Player.isStunned())
                {
                    stashRunesInPouch();
                    return;
                }

                // Ensure we have a dodgy necklace
                if (!Rs2Inventory.hasItemAmount("dodgy necklace", 1, false))
                {
                    Microbot.status = "Out of dodgy necklaces, banking…";
                    bank();
                    return;
                }

                // Optional in-run POH healing
                if (config.useHousePool())
                {
                    int hp    = Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS);
                    int maxHp = Microbot.getClient().getRealSkillLevel(Skill.HITPOINTS);
                    int pct   = hp * 100 / maxHp;
                    if (pct <= config.poolHpThreshold())
                    {
                        useHousePool();
                        return;
                    }
                }

                // Food logic
                List<Rs2ItemModel> foods = Rs2Inventory.getInventoryFood();
                if (config.useFood() && !handleFood(foods))
                    return;
                if (Rs2Inventory.isFull())
                {
                    Rs2Player.eatAt(99);
                    dropItems(foods);
                }

                // Coin pouch randomness
                if (Rs2Random.nextInt(70, 120, 1.0, false) > 110
                        && Rs2Inventory.hasItemAmount("coin pouch", config.coinPouchTreshHold(), true))
                {
                    Rs2Inventory.interact("coin pouch", "Open-all");
                }

                if (!ensureDodgyNecklace())
                    return;

                pickpocket();
            }
            catch (Exception ex)
            {
                Microbot.logStackTrace(this.getClass().getSimpleName(), ex);
            }
        }, 0, 600, TimeUnit.MILLISECONDS);

        return true;
    }

    @Override
    public void shutdown()
    {
        super.shutdown();
        Rs2Walker.setTarget(null);
        Microbot.isCantReachTargetDetectionEnabled = false;
    }

    /**
     * When stunned, sometimes stash Death/Nature runes into your pouch (15% chance).
     */
    private void stashRunesInPouch()
    {
        if (Rs2Random.nextInt(1, 100, 1.0, false) > 15)
        {
            return;
        }

        if (!Rs2Inventory.contains("Rune pouch"))
            return;

        List<String> runes = Arrays.asList("Death rune", "Nature rune");
        for (String rune : runes)
        {
            if (Rs2Inventory.hasItemAmount(rune, 1, true))
            {
                Rs2Inventory.interact(rune, "Use");
                sleepGaussian(150, 50);
                Rs2Inventory.interact("Rune pouch", "Use");
                sleepGaussian(200, 60);
            }
        }
    }
    /**
     * Walk to the Prifddinas bank, deposit all items with ID d,
     * then return to the original pickpocket spot.
     */
    private void depositEnhancedCrystalTeleportSeed()
    {
        Microbot.log("Banking extra items…");


        // 1) Open bank in Prifddinas
        boolean opened = Rs2Bank.isNearBank(PRIFDDINAS, 8)
                ? Rs2Bank.openBank()
                : Rs2Bank.walkToBankAndUseBank(PRIFDDINAS);
        if (!opened || !Rs2Bank.isOpen())
            return;

        // 2) Deposit every 23959 in your inventory
        Rs2Inventory.interact(23959, "Deposit-All");
        sleepGaussian(200, 50);

        // 3) Close bank
        Rs2Bank.closeBank();
        sleepUntil(() -> !Rs2Bank.isOpen(), 5000);

        // 4) Walk back to your starting point
        Rs2Walker.walkTo(NPC_LINDIR_ELF);
        sleepUntil(() ->
                        Rs2Player.getWorldLocation().distanceTo(NPC_LINDIR_ELF) < 3,
                5000
        );
    }
    private boolean doorInTheWay(NPC npc)
    {
        if (npc == null) return false;
        WorldPoint me   = Rs2Player.getWorldLocation();
        WorldPoint them = npc.getWorldLocation();
        WorldPoint between = new WorldPoint(
                (me.getX() + them.getX()) / 2,
                (me.getY() + them.getY()) / 2,
                me.getPlane());
        return Rs2GameObject.getGameObjects(DOOR_CLOSED_ID).stream()
                .anyMatch(o -> o.getWorldLocation().equals(between));
    }

    private void ensureDoorOpen()
    {
        Rs2GameObject.getGameObjects(DOOR_CLOSED_ID).stream().findFirst().ifPresent(o ->
        {
            Rs2GameObject.interact(DOOR_CLOSED_ID, "Open");
            sleepUntil(() ->
                            Rs2GameObject.getGameObjects(DOOR_CLOSED_ID).isEmpty()
                                    || !Rs2GameObject.getGameObjects(DOOR_OPEN_ID).isEmpty(),
                    5000);
        });
        Microbot.log("Opened door");
    }

    private void humanSleep()
    {
        int roll = Rs2Random.nextInt(1, 100, 1.0, false);
        if      (roll <= 5)  sleepGaussian(800, 200);
        else if (roll >= 95) sleepGaussian( 50,  20);
        else if (roll <= 25) sleepGaussian(120,  40);
        else                 sleepGaussian(250, 100);
    }

    /**
     * Open any door in the way, then cast Shadow Veil (if configured),
     * then pickpocket + delay + occasional camera jitter.
     */
    private void attemptPickpocket(NPC npc)
    {
        if (npc == null) return;

        if (doorInTheWay(npc))
        {
            ensureDoorOpen();
            sleepGaussian(250, 60);
        }

        if (config.shadowVeil() && !Rs2Magic.isShadowVeilActive() && !Rs2Bank.isOpen())
        {
            handleShadowVeil();
            sleepGaussian(200, 50);
        }

        if (Rs2Npc.pickpocket(npc))
        {
            Rs2Walker.setTarget(null);
            humanSleep();
            if (Rs2Random.nextInt(1, 100, 1.0, false) <= 3)
                Rs2Camera.rotateCameraRandomly();
        }
    }

    private void pickpocket()
    {
        if (Rs2Bank.isOpen())
        {
            Microbot.log("Bank is open, delaying pickpocket");
            return;
        }

        NPC target = null;
        Map<NPC, HighlightedNpc> highlights =
                net.runelite.client.plugins.npchighlight.NpcIndicatorsPlugin.getHighlightedNpcs();
        if (!highlights.isEmpty())
        {
            target = highlights.keySet().iterator().next();
        }
        else
        {
            if (Objects.requireNonNull(config.THIEVING_NPC()) == ThievingNpc.ELVES) {
                target = Rs2Npc.getNpc("Lindir");
            } else {
                target = Rs2Npc.getNpc(config.THIEVING_NPC().getName());
            }
        }

        Rs2Walker.walkTo(NPC_LINDIR_ELF);
// Toggle Redemption just before pickpocket if HP < 30
        if (Microbot.getClient().getBoostedSkillLevel(Skill.PRAYER) > 0
                && Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS) < 30
                && !Rs2Prayer.isPrayerActive(Rs2PrayerEnum.REDEMPTION))
        {
            Rs2Prayer.toggle(Rs2PrayerEnum.REDEMPTION, true);
            sleepGaussian(300, 50);
        }
        if (Microbot.getClient().getBoostedSkillLevel(Skill.PRAYER) > 0
                && Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS) < 30
                && !Rs2Prayer.isPrayerActive(Rs2PrayerEnum.REDEMPTION))
        {
            Rs2Prayer.toggle(Rs2PrayerEnum.REDEMPTION, true);
            sleepGaussian(300, 50);
        }
        attemptPickpocket(target);
    }

    private boolean handleFood(List<Rs2ItemModel> food)
    {
        if (food.isEmpty())
        {
            openCoinPouches();
            bank();
            return false;
        }
        sleepGaussian(400,100);
        Rs2Player.eatAt(config.hitpoints());
        return true;
    }

    private void useHousePool()
    {
        // 1) Walk into range of the portal
        Microbot.status = "Walking to house portal…";
//        if (Rs2Player.getWorldLocation().distanceTo(HOUSE_PORTAL_LOCATION) > 2)
//        {
//            // walk with a bit of deviation so it’s not perfectly straight
//            Rs2Walker.walkToWithDeviation(HOUSE_PORTAL_LOCATION, 2);
//            sleepUntil(() ->
//                            Rs2Player.getWorldLocation().distanceTo(HOUSE_PORTAL_LOCATION) <= 2,
//                    5000
//            );
//        }

        // 2) If there’s a closed door between you and the portal, open it
        //    (reuses your existing ensureDoorOpen logic)
//        ensureDoorOpen();
        // small pause to let the door finish opening
        Rs2GameObject.interact(DOOR_CLOSED_ID, "Open");
        sleepGaussian(300, 60);

        // 3) Now interact with the portal
        Rs2GameObject.interact(POH_PRIFDDINAS_PORTAL, "Home");
        sleepUntil(() ->
                        Rs2Player.getWorldLocation().getRegionID() != 12894,
                8000
        );
        sleepGaussian(500,100);

        // 4) Drink at the pool
        Microbot.log("Using ornate pool…");
        if (Rs2GameObject.interact(ORNATE_POOL_OF_REJUVENATION, "Drink"))
        {
            sleepUntil(() ->
                            Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS)
                                    == Microbot.getClient().getRealSkillLevel(Skill.HITPOINTS),
                    8000
            );
        }
        else
        {
            Microbot.log("Failed to interact with ornate pool!");
        }
        sleepGaussian(500,100);
        // 5) Exit the house
        Microbot.log("Leaving house…");
        if (Rs2GameObject.interact(POH_EXIT_PORTAL, "Enter"))
        {
            sleepUntil(() ->
                            Rs2Player.getWorldLocation().distanceTo(HOUSE_PORTAL_LOCATION) < 3,
                    8000
            );
        }
        else
        {
            Microbot.log("Failed to leave house portal!");
        }

        Microbot.status = "Resuming thieving";
    }

    private void openCoinPouches()
    {
        if (Rs2Inventory.hasItemAmount("coin pouch", 1, true))
            Rs2Inventory.interact("coin pouch", "Open-all");
    }

    private boolean ensureDodgyNecklace()
    {
        sleepGaussian(400,100);
        if (Rs2Equipment.isWearing("dodgy necklace"))
            return true;
        if (Rs2Inventory.contains("dodgy necklace"))
        {
            Rs2Inventory.wear("dodgy necklace");
            sleepGaussian(200, 100);
            return true;
        }
        Microbot.status = "No dodgy necklace left, banking…";
        bank();
        return false;
    }

    private void handleShadowVeil()
    {
        if (Rs2Bank.isOpen())
        {
            Microbot.log("Skipping Shadow Veil: bank is open");
            return;
        }
        if (!Rs2Magic.isShadowVeilActive()
                && Rs2Magic.isArceeus()
                && Rs2Player.getBoostedSkillLevel(Skill.MAGIC) >= MagicAction.SHADOW_VEIL.getLevel()
                && Microbot.getVarbitValue(Varbits.SHADOW_VEIL_COOLDOWN) == 0)
        {
            Rs2Magic.cast(MagicAction.SHADOW_VEIL);
        }
        sleepGaussian(400, 100);
    }

    private void bank()
    {
        Microbot.status = "Getting food from bank...";
        boolean open = Rs2Bank.isNearBank(PRIFDDINAS, 8)
                ? Rs2Bank.openBank()
                : Rs2Bank.walkToBankAndUseBank(PRIFDDINAS);
        if (!open || !Rs2Bank.isOpen())
            return;

        Rs2Bank.depositAll();
//        Rs2Bank.withdrawX(true, config.food().getName(), config.foodAmount(), true);
        Rs2Bank.withdrawX("dodgy necklace", config.dodgyNecklaceAmount());

        if (config.shadowVeil())
        {
            Rs2Bank.withdrawRunePouch();
            Rs2Inventory.waitForInventoryChanges(5000);
        }

        Rs2Bank.closeBank();
        sleepUntil(() -> !Rs2Bank.isOpen(), 5000);
        Microbot.log("bank closed");
    }

    private void dropItems(List<Rs2ItemModel> food)
    {
        List<String> doNotDropItemList = Arrays.asList(config.DoNotDropItemList().split(","));
        List<String> keep = new ArrayList<>(doNotDropItemList);

        List<String> foodNames = food.stream()
                .map(x -> x.name)
                .collect(Collectors.toList());
        keep.addAll(foodNames);

        keep.add(config.food().getName());
        keep.add("dodgy necklace");
        keep.add("coins");
        keep.add("book of the dead");
        if (config.shadowVeil())
        {
            keep.addAll(Arrays.asList("Fire rune","Earth rune","Cosmic rune","Rune pouch"));
        }

        Rs2Inventory.dropAllExcept(config.keepItemsAboveValue(), keep);
    }
}