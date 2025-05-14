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

import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static net.runelite.api.ItemID.*;
import static net.runelite.api.ObjectID.ORNATE_POOL_OF_REJUVENATION;
import static net.runelite.api.gameval.ItemID.FIRE_ORB;
import static net.runelite.api.gameval.ObjectID.POH_EXIT_PORTAL;
import static net.runelite.api.gameval.ObjectID1.POH_PRIFDDINAS_PORTAL;
import static net.runelite.client.plugins.microbot.util.bank.enums.BankLocation.PRIFDDINAS;

public class ThievingScript extends Script
{
    public static String version = "2.2";

    // ─── Core locations & IDs ──────────────────────────────────────
    // top of your class
    private static final Set<WorldPoint> TARGET_TILES = Set.of(
            new WorldPoint(3244, 6071, 0),
            new WorldPoint(3243, 6071, 0),
            new WorldPoint(3244, 6070, 0),
            new WorldPoint(3243, 6070, 0)
    );


    private static final WorldPoint NPC_LINDIR_ELF        = new WorldPoint(3244, 6070, 0);
    private static final WorldPoint HOUSE_PORTAL_LOCATION = new WorldPoint(3239, 6076, 0);
    private static final int DOOR_CLOSED_ID               = 36253;
    private static final WorldPoint DOOR_NPC_LINDIR       = new WorldPoint(3243, 6072, 0);
    private static final int[] candidates = {FIRE_ORB,DIAMOND,JUG,GOLD_ORE};
    private static final List<String> DEFAULT_KEEP = List.of(
            "Dodgy necklace", "Rune pouch", "Coins", "Coin pouch",
            "Crystal shard", "Death rune", "Nature rune",
            "Enhanced crystal teleport seed"
    );
    private static final List<String> STASH_RUNES = List.of("Death rune","Nature rune");
    private static final int INVENTORY_CHECK_INTERVAL = 10; // every 10 loops

    // ─── Redemption prayer threshold ───────────────────────────────
    private int redemptionThreshold;

    // ─── Life-like “long” break control ────────────────────────────
    private int maxLongBreaksThisHour;
    private int longBreaksTakenThisHour = 0;
    private long lastLongBreakTime     = System.currentTimeMillis();

    // ─── Scheduled tasks & counters ────────────────────────────────
    private ScheduledFuture<?> breakFuture;
    private ScheduledFuture<?> thinkFuture;
    private int loopCounter = 0;

    @Override
    public boolean run()
    {
        // 1) Pick a one-off HP threshold (skewed low)
        redemptionThreshold = Rs2Random.nextInt(16, 31, 2.0, false);
        Microbot.log("Redemption prayer will toggle at ≤ " + redemptionThreshold + " HP");

        // 2) Core setup
        Microbot.isCantReachTargetDetectionEnabled = true;
        Microbot.enableAutoRunOn = false;
        Rs2Antiban.resetAntibanSettings();
        Rs2Antiban.antibanSetupTemplates.applyThievingSetup();

        // 3) Start main loop
        mainScheduledFuture = scheduledExecutorService
                .scheduleWithFixedDelay(this::mainLoop, 0, 600, TimeUnit.MILLISECONDS);

        // 4) Schedule standard AFK micro-breaks and “thinking” pauses
        scheduleBreakTask();
        scheduleThinkBreak();

        // 5) Schedule life-like long breaks: 2–3 per hour, reset each hour
        maxLongBreaksThisHour = Rs2Random.nextInt(2, 4, 1.0, false);
        scheduledExecutorService.scheduleAtFixedRate(() -> {
            maxLongBreaksThisHour   = Rs2Random.nextInt(2, 4, 1.0, false);
            longBreaksTakenThisHour = 0;
            lastLongBreakTime       = System.currentTimeMillis();
            Microbot.log("Reset long-break cap to " + maxLongBreaksThisHour);
        }, 60, 60, TimeUnit.MINUTES);

        return true;
    }

    private void mainLoop()
    {
        try
        {
            if (!Microbot.isLoggedIn() || !super.run()) return;
            loopCounter++;

            // ─── Life-like random long breaks ────────────────────────────
            long now = System.currentTimeMillis();
            if (longBreaksTakenThisHour < maxLongBreaksThisHour
                    && now - lastLongBreakTime > TimeUnit.MINUTES.toMillis(10))
            {
                if (Rs2Random.diceFractional(0.0005))
                {
                    BreakType type = BreakType.randomType();
                    int secs      = type.randomDurationSec();
                    int jitterMs  = secs * 1000 / 10;

                    Microbot.status = "AFK – " + type.desc;
                    Microbot.log("Life break (" + type.desc + ") for " + secs + "s");

                    longBreaksTakenThisHour++;
                    lastLongBreakTime = now;
                    Microbot.log("Starting “" + type.desc + "” break for " + secs + "s");
                    Microbot.status = "AFK – " + type.desc;
                    sleepGaussian(secs * 1000, jitterMs);
                    Microbot.log("“" + type.desc + "” break ended");
                    Microbot.status = "Resuming thieving";
                    return;
                }
            }

            // ─── Redemption prayer toggle ───────────────────────────────
            int currHp = Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS);
            int maxHp  = Microbot.getClient().getRealSkillLevel(Skill.HITPOINTS);
            if (!Rs2Prayer.isPrayerActive(Rs2PrayerEnum.REDEMPTION)
                    && currHp <= redemptionThreshold
                    && !Rs2Prayer.isOutOfPrayer())
            {
                Microbot.status = "Toggling Redemption prayer";
                Rs2Prayer.toggle(Rs2PrayerEnum.REDEMPTION);
                sleepGaussian(200, 50);
            }

            // ─── 1) stunned? ────────────────────────────────────────────
            if (Rs2Player.isStunned())
            {
                handleStunnedBehavior();
                return;
            }
            if (enhancedSeedOnGround()) {
                return;
            }
            // ─── 2) emergency heal ─────────────────────────────────────
            if (currHp < 6 || currHp * 100 / maxHp <= 9)
            {
                useHousePool();
                return;
            }

            // ─── 3) ensure dodgy necklace ──────────────────────────────
            if (!ensureDodgyNecklace()) return;

            // ─── 4) periodic inventory maintenance ─────────────────────
            if (loopCounter % INVENTORY_CHECK_INTERVAL == 0)
            {
                handleInventoryMaintenance();
            }

            // ─── 5) approach & pickpocket ─────────────────────────────
            sleepGaussian(400, 100);
            pickpocket();
        }
        catch (Exception ex)
        {
            Microbot.logStackTrace(this.getClass().getSimpleName(), ex);
        }
    }

    // ─── Life-like break types ──────────────────────────────────────
    private enum BreakType
    {
        PHONE_CHECK("checking phone",    5,  15,  0.6),
        STRETCH     ("stretching",      10,  20,  0.2),
        SNACK       ("getting a snack", 30,  60,  0.15),
        BATHROOM    ("bathroom break",  60, 180,  0.05);

        final String desc;
        final int    minSec, maxSec;
        final double weight;

        BreakType(String d, int min, int max, double w)
        {
            desc   = d;
            minSec = min;
            maxSec = max;
            weight = w;
        }

        static BreakType randomType()
        {
            double total = Arrays.stream(values()).mapToDouble(b -> b.weight).sum();
            double r     = Math.random() * total;
            for (BreakType b : values())
            {
                r -= b.weight;
                if (r <= 0) return b;
            }
            return BATHROOM;
        }

        int randomDurationSec()
        {
            return Rs2Random.nextInt(minSec, maxSec, 1.0, false);
        }
    }

    private void scheduleBreakTask()
    {
        int minutes = Rs2Random.nextInt(18, 35, 1.0, false);
        Microbot.log("Next AFK micro-break in " + minutes + " min");
        breakFuture = scheduledExecutorService.schedule(() -> {
            Microbot.log("Starting AFK micro-break now");
            Microbot.status = "Taking a micro break";
            performMicroBreak();
            Microbot.log("AFK micro-break ended");
            Microbot.status = "Resuming thieving";
            scheduleBreakTask();
        }, minutes, TimeUnit.MINUTES);
    }

    private void scheduleThinkBreak()
    {
        int seconds = Rs2Random.nextInt(120, 240, 1.0, false);
        Microbot.log("Next thinking pause in " + seconds + " s");
        thinkFuture = scheduledExecutorService.schedule(() -> {
            Microbot.log("Thinking pause…");
            Microbot.status = "Thinking…";
            sleepGaussian(2250, 500);
            Microbot.log("Thinking pause ended");
            Microbot.status = "Resuming thieving";
            scheduleThinkBreak();
        }, seconds, TimeUnit.SECONDS);
    }

    private void handleInventoryMaintenance()
    {
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
        else if (Rs2Random.nextInt(70, 125, 1.0, false) > 110
                && Rs2Inventory.hasItemAmount("Coin pouch", 25, true))
        {
            openCoinPouches();
        }
    }

    /** Pure AFK micro-break: 1–3 minutes, single sleep with ~10% jitter. */
    private void performMicroBreak()
    {
        int seconds = Rs2Random.nextInt(60, 180, 1.0, false);
        int meanMs  = seconds * 1000;
        int jitter  = meanMs / 10;  // 10% stddev

        Microbot.log("AFK micro-break for " + seconds + "s");
        Microbot.status = "Taking a micro break";
        sleepGaussian(meanMs, jitter);
        Microbot.log("AFK micro-break complete");
        Microbot.status = "Resuming thieving";
    }

    /** Ultra-fast–biased click delay: 80% superfast, 16% normal, 4% hesitation. */
    private void fastClickDelay()
    {
        double r = Math.random();
        if      (r < 0.78) sleepGaussian(84, 18);
        else if (r < 0.88) sleepGaussian(118, 30);
        else               sleepGaussian(200, 50);
    }

    private void pickpocket()
    {
        if (Rs2Bank.isOpen()) return;

        NPC target = getTargetNpc();
        Rs2Walker.walkTo(NPC_LINDIR_ELF);
        attemptPickpocket(target);
    }

    private void attemptPickpocket(NPC npc)
    {
        if (npc == null) return;
        if (doorInTheWay(npc) && !ensureDoorOpen()) return;
        if (!Rs2Magic.isShadowVeilActive() && !Rs2Bank.isOpen() && TARGET_TILES.contains(Microbot.getClient().getLocalPlayer().getWorldLocation()))
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

            fastClickDelay();

            if (Rs2Random.diceFractional(0.05))
            {
                Rs2Npc.hoverOverActor(npc);
                sleepGaussian(200, 50);
            }
            if (Rs2Random.diceFractional(0.02))
            {
                Microbot.log("few seconds break");
                sleepGaussian(4200, 1200);
            }
//            if (Rs2Random.diceFractional(0.03))
//            {
//                Rs2Camera.rotateCameraRandomly();
//            }
        }
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
    private boolean enhancedSeedOnGround()
    {
        if (Rs2GroundItem.exists(ENHANCED_CRYSTAL_TELEPORT_SEED, 5)) {
            while (Rs2GroundItem.exists(ENHANCED_CRYSTAL_TELEPORT_SEED, 5)) {
                if (Rs2Inventory.isFull()) {
                    handleInventoryMaintenance();
                }
                Microbot.log("PICKING UP ENHANCED SEED");
                Rs2GroundItem.pickup(ENHANCED_CRYSTAL_TELEPORT_SEED);
                sleepGaussian(600, 100);
            }
            return true;
        }
        return false;
    }

    private void handleStunnedBehavior()
    {
        int roll = Rs2Random.nextInt(1, 100, 1.0, false);
        if (roll <= 25)
        {
            if (dropRandomInventoryItem()) Microbot.log("Dropped random item");
            if (Rs2GroundItem.exists(JUG_OF_WINE, 2)) {
                Rs2GroundItem.pickup(JUG_OF_WINE);
                Rs2Inventory.waitForInventoryChanges(600);
            }
            sleepGaussian(200, 50);
        }
        else if (roll <= 50)
        {
            int hp = Microbot.getClient().getBoostedSkillLevel(Skill.HITPOINTS);
            if (hp < 87 && Rs2Inventory.hasItem(JUG_OF_WINE))
            {
                Rs2Inventory.interact(JUG_OF_WINE, "Drink");
                Rs2Inventory.waitForInventoryChanges(600);
            }
        }
        else if (roll <= 71 && Rs2Random.nextInt(1, 100, 1.0, false) <= 15)
        {
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

    private boolean ensureDodgyNecklace()
    {

        final String ITEM = "dodgy necklace";
        final int MAX_ATTEMPTS = 3;


        if (Rs2Equipment.isWearing(ITEM))
            return true;

        if (!Rs2Inventory.contains(ITEM))
        {
            Microbot.log(ITEM + " not in inventory, cannot equip. Banking.");
            bank();
            return false;
        }

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++)
        {
            Microbot.log("Attempt " + attempt + " to equip " + ITEM);
            Rs2Inventory.wear(ITEM);

            // wait up to 1 second for the equip to register
            boolean equipped = sleepUntil(() -> Rs2Equipment.isWearing(ITEM), 1000);
            if (equipped)
            {
                Microbot.log(ITEM + " successfully equipped on attempt " + attempt);
                return true;
            }

            // small Gaussian pause before retrying
            sleepGaussian(300, 30);  // ~±10% variance around 300ms
        }

        Microbot.log("Failed to equip Dodgy necklace after " + MAX_ATTEMPTS + " attempts");
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
        boolean open = Rs2Bank.isNearBank(PRIFDDINAS, 8)
                ? Rs2Bank.openBank()
                : Rs2Bank.walkToBankAndUseBank(PRIFDDINAS);
        if (!open || !Rs2Bank.isOpen()) return;

        Rs2Bank.depositAll();
        Rs2Bank.withdrawX("dodgy necklace", 21);
        Rs2Inventory.waitForInventoryChanges(1000);
        Rs2Bank.withdrawRunePouch();
        Rs2Inventory.waitForInventoryChanges(1000);
        Rs2Bank.closeBank();
        sleepUntil(() -> !Rs2Bank.isOpen(), 1000);
    }
    /**
     * Looks for any of the given items in inventory, picks one at random, and drops it.
     * @return true if an item was dropped, false if none of the candidates were found
     */
    private boolean dropRandomInventoryItem()
    {
        List<Integer> present;
        present = new ArrayList<>();
        for (int item : candidates)
        {
            if (Rs2Inventory.contains(item))
            {
                present.add(item);
            }
        }

        // 2) If none are present, bail out
        if (present.isEmpty())
        {
            Microbot.log("No drop candidates found in inventory.");
            return false;
        }

        // 3) Pick one at random
        int toDrop = present.get(Rs2Random.nextInt(0, present.size()-1, 1.0, false));
        Microbot.log("Randomly selected to drop: " + toDrop);

        // 4) Drop it and wait for the change
        Rs2Inventory.drop(toDrop);
        Rs2Inventory.waitForInventoryChanges(600);

        // 5) Optional human‐like pause before continuing
        sleepGaussian(200, 30);  // ~±15% around 200ms
        return true;
    }

    @Override
    public void shutdown()
    {
        super.shutdown();
        if (breakFuture != null)  breakFuture.cancel(true);
        if (thinkFuture != null)  thinkFuture.cancel(true);
        Rs2Walker.setTarget(null);
        Microbot.isCantReachTargetDetectionEnabled = false;
    }
}