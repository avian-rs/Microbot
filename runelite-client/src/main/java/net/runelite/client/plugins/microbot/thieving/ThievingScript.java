package net.runelite.client.plugins.microbot.thieving;

import net.runelite.api.GameObject;
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
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
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

    private static final WorldPoint NPC_LINDIR_ELF        = new WorldPoint(3244, 6071, 0);
    private static final WorldPoint HOUSE_PORTAL_LOCATION = new WorldPoint(3239, 6076, 0);
    private static final int DOOR_CLOSED_ID               = 36253;
    private static final int DOOR_OPEN_ID                 = 36254;

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
                if (!Microbot.isLoggedIn() || !super.run()) return;

//                // 0) Bank extra seeds
//                if (Rs2Inventory.hasItem(23959))
//                {
//                    depositEnhancedCrystalTeleportSeed();
//                    return;
//                }

                // 1) If stunned
                if (Rs2Player.isStunned())
                {
                    handleStunnedBehavior();
                    return;
                }

                // 2) Emergency heal if HP < 6
                int currHp = Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS);
                if (currHp < 6)
                {
                    useHousePool();
                    return;
                }

                // 3) Ensure dodgy necklace
                if (!Rs2Inventory.hasItemAmount("dodgy necklace", 1, false))
                {
                    Microbot.status = "Out of dodgy necklaces, banking…";
                    bank();
                    return;
                }

                // 4) Optional POH pool healing
                if (config.useHousePool())
                {
                    int hp    = Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS);
                    int maxHp = Microbot.getClient().getRealSkillLevel(Skill.HITPOINTS);
                    if (hp * 100 / maxHp <= config.poolHpThreshold())
                    {
                        useHousePool();
                        return;
                    }
                }

                // 5) Food logic
                List<Rs2ItemModel> foods = Rs2Inventory.getInventoryFood();
                if (config.useFood() && !handleFood(foods)) return;
                if (Rs2Inventory.isFull())
                {
                    Rs2Player.eatAt(99);
                    dropItems(foods);
                }

                // 6) Coin pouch randomness
                if (Rs2Random.nextInt(70, 120, 1.0, false) > 110
                        && Rs2Inventory.hasItemAmount("coin pouch", config.coinPouchTreshHold(), true))
                {
                    Rs2Inventory.interact("coin pouch", "Open-all");
                }

                // 7) Main pickpocket
                if (!ensureDodgyNecklace()) return;
                sleepGaussian(400, 100);
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
     * Retries opening a closed door up to 3 times.
     * @return true if door is now open (or wasn't there), false otherwise.
     */
    private boolean ensureDoorOpen()
    {
        for (int attempt = 1; attempt <= 3; attempt++)
        {
//            List<GameObject> closed = Rs2GameObject.getGameObjects(DOOR_CLOSED_ID);
//            if (closed.isEmpty())
//            {
//                return true;
//            }

            Microbot.log("Opening door, attempt " + attempt);
            Rs2GameObject.interact(DOOR_CLOSED_ID, "Open");
            sleepGaussian(250, 60);
            sleepUntil(() -> Rs2GameObject.getGameObjects(DOOR_CLOSED_ID).isEmpty(), 3000);
            if (Rs2GameObject.getGameObjects(DOOR_CLOSED_ID).isEmpty())
            {
                return true;
            }
        }

        Microbot.log("Failed to open door after 3 attempts");
        return false;
    }

    /**
     * When stunned, roll once (1–100):
     *  1–15   → stash runes
     *  16–30  → drink wine if HP<60
     *  31–45  → drop empty jug
     *  46–55  → 50% chance to misclick/pickpocket (quick Gaussian delay)
     *  56–100 → nothing
     */
    private void handleStunnedBehavior()
    {
        int roll = Rs2Random.nextInt(1, 100, 1.0, false);

        if (roll <= 15)
        {
            // stash runes
            if (Rs2Inventory.contains("Rune pouch"))
            {
                sleepGaussian(200, 50);
                for (String rune : Arrays.asList("Death rune", "Nature rune"))
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
            return;
        }
        else if (roll <= 30)
        {
            // drink wine
            int hp = Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS);
            if (hp < 60 && Rs2Inventory.hasItem(1993))
            {
                Rs2Inventory.interact(1993, "Drink");
                sleepGaussian(200, 50);
            }
            return;
        }
        else if (roll <= 45)
        {
            // drop jug
            if (Rs2Inventory.hasItem(1935))
            {
                Rs2Inventory.interact(1935, "Drop");
                sleepGaussian(200, 50);
            }
            return;
        }
        else if (roll <= 55)
        {
            // misclick pickpocket
            if (Rs2Random.nextInt(1, 100, 1.0, false) <= 50)
            {
                sleepGaussian(110, 25);
                NPC target = getTargetNpc();
                if (target != null)
                {
                    Rs2Npc.pickpocket(target);
                }
            }
            return;
        }
        // else do nothing
    }

    private NPC getTargetNpc()
    {
        Map<NPC, HighlightedNpc> highlights =
                net.runelite.client.plugins.npchighlight.NpcIndicatorsPlugin.getHighlightedNpcs();
        if (!highlights.isEmpty())
            return highlights.keySet().iterator().next();

        if (config.THIEVING_NPC() == ThievingNpc.ELVES)
            return Rs2Npc.getNpc("Lindir");
        else
            return Rs2Npc.getNpc(config.THIEVING_NPC().getName());
    }

    private void attemptPickpocket(NPC npc)
    {
        if (npc == null) return;

        if (doorInTheWay(npc))
        {
            if (!ensureDoorOpen())
            {
                Microbot.log("Could not open door, skipping pickpocket");
                return;
            }
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

            if (rollProbability(5))
            {
                sleepGaussian(50, 20);
                Rs2Npc.pickpocket(npc);
            }
            if (Rs2Player.isStunned() && rollProbability(4))
            {
                sleepGaussian(50, 20);
                Rs2Npc.pickpocket(npc);
            }

//            // fast human sleep ~109±18 ms
//            sleepGaussian(109, 18);
            // inside your click-handling block, instead of fixed sleepGaussian(109,18):

// 1) Mixture of human-like intervals:
//   – 70% “normal” speed around 109 ± 18 ms
//   – 20% “fast” bursts around 60 ± 15 ms
//   – 10% “slow” drift around 200 ± 50 ms
            double r = Math.random();
            if (r < 0.70) {
                sleepGaussian(109, 18);
            } else if (r < 0.90) {
                sleepGaussian(60, 15);
            } else {
                sleepGaussian(200, 50);
            }

// 2) Occasional double-click “combo” (e.g. 15% chance):
            if (Math.random() < 0.15) {
                // second click after a very short human-like pause
                sleepGaussian(40, 10);
                Rs2Npc.pickpocket(npc); // or your click action
            }

// 3) Rare “super slow” break (0.5% chance) to mimic distraction:
            if (Math.random() < 0.005) {
                // random 3–6 s break
                sleepGaussian(4200,1200);
            }

            if (rollProbability(3))
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

        NPC target = getTargetNpc();
        Rs2Walker.walkTo(NPC_LINDIR_ELF);

        if (Microbot.getClient().getBoostedSkillLevel(Skill.PRAYER) > 0
                && !Rs2Prayer.isPrayerActive(Rs2PrayerEnum.RAPID_HEAL))
        {
            Rs2Prayer.toggle(Rs2PrayerEnum.RAPID_HEAL, true);
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

    private boolean rollProbability(int percent)
    {
        return Rs2Random.nextInt(1, 100, 1.0, false) <= percent;
    }

    private void humanSleep()
    {
        int roll = Rs2Random.nextInt(1, 100, 1.0, false);
        if      (roll <= 5)  sleepGaussian(800, 200);
        else if (roll >= 95) sleepGaussian( 50,  20);
        else if (roll <= 25) sleepGaussian(120,  40);
        else                 sleepGaussian(250, 100);
    }

    private void depositEnhancedCrystalTeleportSeed()
    {
        Microbot.log("Banking extra items…");

        boolean opened = Rs2Bank.isNearBank(PRIFDDINAS, 8)
                ? Rs2Bank.openBank()
                : Rs2Bank.walkToBankAndUseBank(PRIFDDINAS);
        if (!opened || !Rs2Bank.isOpen()) return;

        Rs2Inventory.interact(23959, "Deposit-All");
        sleepGaussian(200, 50);

        Rs2Bank.closeBank();
        sleepUntil(() -> !Rs2Bank.isOpen(), 5000);

        Rs2Walker.walkTo(NPC_LINDIR_ELF);
        sleepUntil(() ->
                        Rs2Player.getWorldLocation().distanceTo(NPC_LINDIR_ELF) < 1,
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
        return Rs2GameObject.getGameObjects(DOOR_CLOSED_ID)
                .stream()
                .anyMatch(o -> o.getWorldLocation().equals(between));
    }

    private boolean handleFood(List<Rs2ItemModel> food)
    {
        if (food.isEmpty())
        {
            openCoinPouches();
            bank();
            return false;
        }
        sleepGaussian(400, 100);
        Rs2Player.eatAt(config.hitpoints());
        return true;
    }

    /**
     * Teleport home to your POH pool, drink, and return.
     */
    private void useHousePool()
    {
        Microbot.status = "Walking to house portal…";

        // 1) Open any door in the way (retry up to 3 times)
        if (!ensureDoorOpen())
        {
            Microbot.log("Failed to open door to portal, aborting heal");
            return;
        }
        sleepGaussian(300, 60);

        // 2) Use the portal
        Rs2GameObject.interact(POH_PRIFDDINAS_PORTAL, "Home");
        sleepUntil(() ->
                        Rs2Player.getWorldLocation().getRegionID() != 12894,
                8000
        );
        sleepGaussian(500,100);

        // 3) Drink at the pool
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

        // 4) Exit the house
        Microbot.log("Leaving house…");
        if (!Rs2GameObject.interact(POH_EXIT_PORTAL, "Enter"))
        {
            Microbot.log("Failed to leave house portal!");
            return;
        }
        sleepUntil(() ->
                        Rs2Player.getWorldLocation().distanceTo(HOUSE_PORTAL_LOCATION) < 2,
                8000
        );

        Microbot.status = "Resuming thieving";
    }

    private void openCoinPouches()
    {
        if (Rs2Inventory.hasItemAmount("coin pouch", 1, true))
            Rs2Inventory.interact("coin pouch", "Open-all");
    }

    private boolean ensureDodgyNecklace()
    {
        sleepGaussian(400, 100);
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
        if (!open || !Rs2Bank.isOpen()) return;

        Rs2Bank.depositAll();
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