package net.runelite.client.plugins.microbot.thieving;

import net.runelite.api.NPC;
import net.runelite.api.Skill;
import net.runelite.api.Varbits;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.game.npcoverlay.HighlightedNpc;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.grounditem.Rs2GroundItem;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.prayer.Rs2Prayer;
import net.runelite.client.plugins.microbot.util.prayer.Rs2PrayerEnum;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.skillcalculator.skills.MagicAction;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static net.runelite.api.ObjectID.ORNATE_POOL_OF_REJUVENATION;
import static net.runelite.api.gameval.ObjectID.POH_EXIT_PORTAL;
import static net.runelite.api.gameval.ObjectID1.POH_PRIFDDINAS_PORTAL;
import static net.runelite.client.plugins.microbot.util.bank.enums.BankLocation.PRIFDDINAS;

public class ThievingScript extends Script
{
    public static String version = "2.0";

    // core locations & IDs
    private static final WorldPoint NPC_LINDIR_ELF        = new WorldPoint(3244, 6071, 0);
    private static final WorldPoint HOUSE_PORTAL_LOCATION = new WorldPoint(3239, 6076, 0);
    private static final int DOOR_CLOSED_ID               = 36253;
    private static final WorldPoint DOOR_NPC_LINDIR       = new WorldPoint(3243, 6072, 0);

    private static final List<String> DEFAULT_KEEP = List.of(
            "Dodgy necklace",
            "Rune pouch",
            "Coins",
            "Coin pouch",
            "Crystal shard",
            "Death rune",
            "Nature rune",
            "Enhanced crystal teleport seed"
    );

    private static final List<String> STASH_RUNES = List.of("Death rune","Nature rune");


    public boolean run()
    {
        Microbot.isCantReachTargetDetectionEnabled = true;
        Microbot.enableAutoRunOn = false;
        Rs2Antiban.resetAntibanSettings();
        Rs2Antiban.antibanSetupTemplates.applyThievingSetup();

        // pre-compute thresholds
        final int coinPouchThreshold   = 75;
        final int dodgyNecklaceAmount = 21;

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() ->
        {
            try
            {
                if (!Microbot.isLoggedIn() || !super.run()) return;

                var client = Microbot.getClient();
                int currHp = client.getBoostedSkillLevel(Skill.HITPOINTS);
                int maxHp  = client.getRealSkillLevel(Skill.HITPOINTS);

                // 1) stunned?
                if (Rs2Player.isStunned())
                {
                    handleStunnedBehavior();
                    return;
                }

                // 2) emergency heal if very low
                if (currHp < 6 || currHp * 100 / maxHp <= 9)
                {
                    useHousePool();
                    return;
                }

                // 3) out of necklaces?
                if (!Rs2Inventory.hasItemAmount("dodgy necklace", 1, false))
                {
                    Microbot.status = "Out of dodgy necklaces, banking…";
                    bank();
                    return;
                }

                // 4) inventory full?
                if (Rs2Inventory.isFull())
                {
                    Rs2Inventory.dropAllExcept(10000, DEFAULT_KEEP);
                    for (String rune : STASH_RUNES)
                    {
                        if (Rs2Inventory.hasItem(rune))
                        {
                            Rs2Inventory.interact(rune, "Use");
                            sleepGaussian(150, 50);
                            Rs2Inventory.interact("Rune pouch", "Use");
                            sleepGaussian(200, 60);
                        }
                    }
                }

                // 5) occasionally open coin pouches
                if (Rs2Random.nextInt(70, 120, 1.0, false) > 110
                        && Rs2Inventory.hasItemAmount("coin pouch", coinPouchThreshold, true))
                {
                    Rs2Inventory.interact("coin pouch", "Open-all");
                }

                // 6) main pickpocket
                if (!ensureDodgyNecklace(dodgyNecklaceAmount)) return;
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

    private boolean ensureDoorOpen()
    {
        for (int i = 0; i < 3; i++)
        {
            Rs2GameObject.interact(DOOR_NPC_LINDIR, "Open");
            sleepGaussian(250, 60);
            sleepUntil(() -> Rs2GameObject.getGameObjects(DOOR_CLOSED_ID).isEmpty(), 3000);
            if (Rs2GameObject.getGameObjects(DOOR_CLOSED_ID).isEmpty())
                return true;
        }
        return false;
    }

    private void handleStunnedBehavior()
    {
        int roll = Rs2Random.nextInt(1, 100, 1.0, false);
        if (roll <= 10)
        {
            if (Rs2GroundItem.exists(1993, 2)) {
                Rs2GroundItem.pickup(1993);
            }
            sleepGaussian(200, 50);
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
        }
        else if (roll <= 45)
        {
            // drop jug
            if (Rs2Inventory.hasItem(1935))
            {
                Rs2Inventory.interact(1935, "Drop");
                sleepGaussian(200, 50);
            }
        }
        else if (roll <= 55 && Rs2Random.nextInt(1, 100, 1.0, false) <= 15)
        {
            // misclick pickpocket attempt
            sleepGaussian(110, 25);
            NPC t = getTargetNpc();
            if (t != null) Rs2Npc.pickpocket(t);
        }
    }

    private NPC getTargetNpc()
    {
        Map<NPC, HighlightedNpc> highlights =
                net.runelite.client.plugins.npchighlight.NpcIndicatorsPlugin.getHighlightedNpcs();
        if (!highlights.isEmpty())
            return highlights.keySet().iterator().next();

        return Rs2Npc.getNpc("Lindir");
    }

    private void attemptPickpocket(NPC npc)
    {
        if (npc == null) return;
        if (doorInTheWay(npc) && !ensureDoorOpen()) return;
        if (!Rs2Magic.isShadowVeilActive() && !Rs2Bank.isOpen())
        {
            handleShadowVeil();
            sleepGaussian(150, 50);
        }
        if (Rs2Npc.pickpocket(npc))
        {
            Rs2Walker.setTarget(null);

            if (Rs2Random.diceFractional(0.05))
            {
                sleepGaussian(50, 20);
                Rs2Npc.pickpocket(npc);
            }
            if (Rs2Player.isStunned() && Rs2Random.diceFractional(0.04))
            {
                sleepGaussian(50, 20);
                Rs2Npc.pickpocket(npc);
            }

            double r = Math.random();
            if      (r < 0.70) sleepGaussian(85, 22);
            else if (r < 0.90) sleepGaussian(65, 15);
            else               sleepGaussian(200, 50);

            if (Rs2Random.diceFractional(0.05))
            {
                Rs2Npc.hoverOverActor(npc);
                sleepGaussian(200, 50);
            }
            if (Rs2Random.diceFractional(0.05))
            {
                sleepGaussian(40, 10);
                Rs2Npc.pickpocket(npc);
            }
            if (Rs2Random.diceFractional(0.01))
            {
                sleepGaussian(4200, 1200);
            }
            if (Rs2Random.diceFractional(0.03))
            {
                Rs2Camera.rotateCameraRandomly();
            }
        }
    }

    private void pickpocket()
    {
        if (Rs2Bank.isOpen()) return;

        NPC target = getTargetNpc();
        Rs2Walker.walkTo(NPC_LINDIR_ELF);

        if (Microbot.getClient().getBoostedSkillLevel(Skill.PRAYER) > 0
                && Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS) < 30
                && !Rs2Prayer.isPrayerActive(Rs2PrayerEnum.REDEMPTION))
        {
            Rs2Prayer.toggle(Rs2PrayerEnum.REDEMPTION, true);
            sleepGaussian(300, 50);
        }

        attemptPickpocket(target);
    }

    private boolean doorInTheWay(NPC npc)
    {
        if (npc == null) return false;
        WorldPoint me   = Rs2Player.getWorldLocation();
        WorldPoint them = npc.getWorldLocation();
        WorldPoint between = new WorldPoint(
                (me.getX() + them.getX()) / 2,
                (me.getY() + them.getY()) / 2,
                me.getPlane()
        );
        return Rs2GameObject.getGameObjects(DOOR_CLOSED_ID)
                .stream()
                .anyMatch(o -> o.getWorldLocation().equals(between));
    }

    private void useHousePool()
    {
        Microbot.status = "Walking to house portal…";
        if (!ensureDoorOpen()) return;
        sleepGaussian(300, 60);

        Rs2GameObject.interact(POH_PRIFDDINAS_PORTAL, "Home");
        sleepUntil(() -> Rs2Player.getWorldLocation().getRegionID() != 12894, 8000);
        sleepGaussian(500, 100);

        if (Rs2GameObject.interact(ORNATE_POOL_OF_REJUVENATION, "Drink"))
        {
            sleepUntil(() ->
                            Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS)
                                    == Microbot.getClient().getRealSkillLevel(Skill.HITPOINTS),
                    8000
            );
        }
        sleepGaussian(500, 100);

        if (!Rs2GameObject.interact(POH_EXIT_PORTAL, "Enter")) return;
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

    private boolean ensureDodgyNecklace(int want)
    {
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
        if (Rs2Bank.isOpen()) return;
        if (!Rs2Magic.isShadowVeilActive()
                && Rs2Magic.isArceeus()
                && Rs2Player.getBoostedSkillLevel(Skill.MAGIC) >= MagicAction.SHADOW_VEIL.getLevel()
                && Microbot.getVarbitValue(Varbits.SHADOW_VEIL_COOLDOWN) == 0)
        {
            Rs2Magic.cast(MagicAction.SHADOW_VEIL);
            sleepGaussian(400, 100);
        }
    }

    private void bank()
    {
        Microbot.status = "Getting food from bank...";
        boolean open = Rs2Bank.isNearBank(PRIFDDINAS, 8)
                ? Rs2Bank.openBank()
                : Rs2Bank.walkToBankAndUseBank(PRIFDDINAS);
        if (!open || !Rs2Bank.isOpen()) return;

        Rs2Bank.depositAll();
        Rs2Bank.withdrawX("dodgy necklace", 21);

        Rs2Bank.withdrawRunePouch();
        Rs2Inventory.waitForInventoryChanges(5000);

        Rs2Bank.closeBank();
        sleepUntil(() -> !Rs2Bank.isOpen(), 5000);
    }
}