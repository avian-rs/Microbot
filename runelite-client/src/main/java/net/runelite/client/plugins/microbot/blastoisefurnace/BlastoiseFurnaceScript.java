package net.runelite.client.plugins.microbot.blastoisefurnace;

import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import net.runelite.api.*;
import net.runelite.api.widgets.Widget;
import net.runelite.client.plugins.microbot.shortestpath.ShortestPathPlugin;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;

import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.grandexchange.Rs2GrandExchange;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.npc.Rs2NpcModel;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.blastoisefurnace.enums.Bars;
import net.runelite.client.plugins.microbot.blastoisefurnace.enums.State;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import static net.runelite.api.ItemID.COAL;
import static net.runelite.api.gameval.ItemID.COAL_BAG;

public class BlastoiseFurnaceScript extends Script {
    static final int BAR_DISPENSER = 9092;
    static final int coalBag = 12019;
    private static final int MAX_ORE_PER_INTERACTION = 27;
    public static double version = 1.0;
    public static State state;
    static int staminaTimer;

    static boolean coalBagEmpty;
    static boolean primaryOreEmpty;
    static boolean secondaryOreEmpty;
// ── FIELDS ───────────────────────────────────────────────────────────────────
    /** Timestamps (ms since epoch) for when the next break should occur. */
    private static long nextMicroBreakTime;
    private static long nextMacroBreakTime;
    private static final int COLLECT_BANK_PARENT_WIDGET_ID = 402;

    private static final Map<WorldPoint, Integer> pointWeights = new HashMap<>();

    static {
        pointWeights.put(new WorldPoint(1940, 4962, 0), 30);
        pointWeights.put(new WorldPoint(1941, 4962, 0), 40);
        pointWeights.put(new WorldPoint(1940, 4964, 0), 5);
        pointWeights.put(new WorldPoint(1939, 4962, 0), 20);
        pointWeights.put(new WorldPoint(1939, 4963, 0), 5);
    }
    static {
        state = State.BANKING;
    }

    private BlastoiseFurnaceConfig config;

    public boolean run(BlastoiseFurnaceConfig config) {
        this.config = config;
        state = State.BANKING;
        Rs2Antiban.resetAntibanSettings();
        Rs2Antiban.antibanSetupTemplates.applySmithingSetup();
        initializeBreakSchedule();

        this.mainScheduledFuture = this.scheduledExecutorService.scheduleWithFixedDelay(() -> {
                    if (!Microbot.isLoggedIn() || !super.run())
                        return;

                    switch (state) {
                        case BANKING:
                            Microbot.status = "Banking";

                            // 1) Open bank
                            if (!Rs2Bank.isOpen()) {
                                Rs2Bank.openBank();
                                sleepUntil(Rs2Bank::isOpen, 20_000);
                            }

                            // 2) Withdraw coal bag if needed
                            if (config.getBars().isRequiresCoalBag() && !Rs2Inventory.contains(COAL_BAG)) {
                                if (!Rs2Bank.hasItem(COAL_BAG)) {
                                    Microbot.showMessage("No coal bag found.");
                                    shutdown();
                                    return;
                                }
                                Rs2Bank.withdrawItem(COAL_BAG);
                            }

                            // 3) Deposit bars but keep potion & vial
                            if (Rs2Inventory.hasItem("bar")) {
                                Rs2Bank.depositAllExcept(
                                        COAL_BAG
                                );
                            }


                            // 4) Out of ores?
                            if (!hasRequiredOresInBank()) {

                                // First check if we can collect any from G.E. offers
                                Rs2GameObject.interact("Bank chest", "Collect");
                                sleepUntil(() -> isOpen());

                                Microbot.log("Bank chest collect is open");
                                if (clickBankCollectWidget())
                                {
                                    Microbot.log("COLLECTED TO BANK");
                                }
                                else {
                                    Microbot.log("FAILED TO CLICK COLLECT BANK BOX");
                                }
                                sleepGaussian(400, 100);
                                Rs2Bank.openBank();
                                sleepUntil(Rs2Bank::isOpen, 2000);

                                if (!hasRequiredOresInBank()) {
                                    Rs2Walker.walkTo(new WorldPoint(2930, 10196, 0));
                                    Rs2Player.logout();
                                    shutdown();
                                    return;
                                }
                            }

                            var client            = Microbot.getClient();
                            int agilityLevel = client.getRealSkillLevel(Skill.AGILITY);
                            int energyThreshold = agilityLevel > 60 ? 4100 : 7500;
                            int currentEnergy = client.getEnergy();

                            if (currentEnergy < energyThreshold) {
                                // try to regenerate run energy with a natural break first
                                Microbot.log("Energy low (" + currentEnergy + " < " + energyThreshold + "), taking a break to recover...");
                                if (client.getRealSkillLevel(Skill.SMITHING) > 60) {
                                    simulateRandomBreak();
                                }
                                // re-pull your energy level after the break
                                currentEnergy = client.getEnergy();
                                Microbot.log("Post-break energy: " + currentEnergy);

                                // only use potion if break didn't restore you above the threshold
                                if (currentEnergy < energyThreshold) {
                                    // your existing stamina‐potion logic…
                                    if (agilityLevel < 60) {
                                        if (currentEnergy < 1600 || !Rs2Player.hasStaminaBuffActive()) {
                                            if (useStaminaPotions()) {
                                                return;
                                            }
                                        }
                                    } else {
                                        if (!Rs2Player.hasStaminaBuffActive()) {
                                            if (useStaminaPotions()) {
                                                return;
                                            }
                                        }
                                    }
                                }
                            }

                            if (dispenserContainsBars()) {
                                handleDispenserLooting();
                            } else {
                                retrieveItemsForCurrentFurnaceInteraction();
                                state = State.SMITHING;
                            }
                            break;

                        case SMITHING:
                            if (barsInDispenser(config.getBars()) > 0)
                                handleDispenserLooting();
                            state = State.BANKING;
                            break;
                    }
                },
                0,
                Rs2Random.randomGaussian(650, 100),
                TimeUnit.MILLISECONDS);

        return true;
    }

    private void handleTax() {
        Microbot.log("Paying noob smithing tax");
        if (!Rs2Bank.isOpen()) {
            Microbot.log("Opening bank");
            Rs2Bank.openBank();
            sleepUntil(Rs2Bank::isOpen, 20000);
        }
        Rs2Bank.depositOne(config.getBars().getPrimaryOre());
        sleepGaussian(400, 100);
        Rs2Bank.depositOne(ItemID.COAL);
        sleepGaussian(400, 100);
        Rs2Bank.withdrawX(ItemID.COINS_995,2500);
        sleepGaussian(400, 100);
        Rs2Bank.closeBank();
        sleepGaussian(400, 100);
        Rs2NpcModel blastie = Rs2Npc.getNpc("Blast Furnace Foreman");
        Rs2Npc.interact(blastie, "Pay");
        sleepUntil(Rs2Dialogue::isInDialogue,10000);
        if (Rs2Dialogue.hasSelectAnOption()) {
            Rs2Dialogue.clickOption("Yes");
            sleepGaussian(400, 100);
            Rs2Dialogue.clickContinue();
            sleepGaussian(400, 100);

        }
    }

    private void handleDispenserLooting() {
        final int MAX_RETRIES = 3;
        int retries = 0;

        while (!Rs2Inventory.isFull() && retries < MAX_RETRIES) {
            sleepGaussian(300,100);
            Rs2GameObject.interact(BAR_DISPENSER, "Take");
            boolean interactionSuccessful = sleepUntil(() ->
                    Rs2Widget.hasWidget("What would you like to take?") ||
                            Rs2Widget.hasWidget("How many would you like") ||
                            Rs2Widget.hasWidget("The bars are still molten!"), 1500);

            if (!interactionSuccessful) {
                retries++;
                Microbot.log("Bar dispenser click may have failed, retrying... (" + retries + ")");
                sleepGaussian(400, 100);
                continue;
            }

            if (Rs2Widget.hasWidget("The bars are still molten!")) {
                if (!Rs2Inventory.interact(ItemID.ICE_GLOVES, "Wear") && !Rs2Inventory.interact(ItemID.SMITHS_GLOVES_I, "Wear")) {
                    Microbot.showMessage("Ice gloves or smith gloves required to loot the hot bars.");
                    Rs2Player.logout();
                    shutdown();
                    return;
                }
                Rs2GameObject.interact(BAR_DISPENSER, "Take");
                sleepUntil(() ->
                        Rs2Widget.hasWidget("What would you like to take?") ||
                                Rs2Widget.hasWidget("How many would you like"), 1500);
            }

            boolean canLootBar = Rs2Widget.hasWidget("How many would you like");
            boolean multipleBarTypes = Rs2Widget.hasWidget("What would you like to take?");

            if (canLootBar || multipleBarTypes) {
                Rs2Keyboard.keyPress(KeyEvent.VK_SPACE);
                Rs2Inventory.waitForInventoryChanges(600);
                break;
            }

            retries++;
            sleepGaussian(600, 100);
        }

        if (retries >= MAX_RETRIES) {
            Microbot.log("Failed to loot from dispenser after multiple attempts.");
        }

        state = State.BANKING;
    }

    private void retrieveCoalAndPrimary() {
        int primaryOre = this.config.getBars().getPrimaryOre();
        if (!Rs2Inventory.hasItem(primaryOre)) {
            Rs2Bank.withdrawAll(primaryOre);
            Rs2Inventory.waitForInventoryChanges(600);
            return;
        }

        boolean fullCoalBag = Rs2Inventory.interact(coalBag, "Fill");
        if (!fullCoalBag)
            return;
        Rs2Inventory.waitForInventoryChanges(600);
        depositOre();
        Rs2Walker.walkFastCanvas(BlastoiseFurnaceScript.getRandomWeightedPoint());
        sleepGaussian(800,250);
        sleepUntil(() -> barsInDispenser(config.getBars()) > 0, 10000);
    }

    private void retrievePrimary() {
        int primaryOre = config.getBars().getPrimaryOre();
        if (!Rs2Inventory.hasItem(primaryOre)) {
            Rs2Bank.withdrawAll(primaryOre);
            return;
        }
        depositOre();

        Rs2Walker.walkFastCanvas(BlastoiseFurnaceScript.getRandomWeightedPoint());
        sleepGaussian(1000,400);
        sleepUntil(() -> barsInDispenser(this.config.getBars()) > 0, 10000);
        sleepGaussian(200,100);
    }

    public static WorldPoint getRandomWeightedPoint() {
        int totalWeight = 0;
        for (int weight : pointWeights.values()) {
            totalWeight += weight;
        }

        int randomWeight = Rs2Random.nextInt(1, totalWeight, 1.0, true);
        int currentSum = 0;
        for (Map.Entry<WorldPoint, Integer> entry : pointWeights.entrySet()) {
            currentSum += entry.getValue();
            if (randomWeight <= currentSum) {
                return entry.getKey();
            }
        }
        return null; // Should never happen if weights are set correctly
    }

    private void retrieveDoubleCoal() {
        if (!Rs2Inventory.hasItem(COAL)) {
            Rs2Bank.withdrawAll(COAL);
            return;
        }
        boolean fullCoalBag = Rs2Inventory.interact(coalBag, "Fill");
        if (!fullCoalBag)
            return;
        sleepGaussian(300,100);
        depositOre();

    }



    private void retrieveItemsForCurrentFurnaceInteraction() {
        switch (config.getBars()) {
            case STEEL_BAR:
                handleSteel();
                break;
            case MITHRIL_BAR:
                handleMithril();
                break;
            case ADAMANTITE_BAR:
                handleAdamantite();
                break;
            case RUNITE_BAR:
                handleRunite();
                break;
        }

    }
    public static boolean isOpen() {
        return Rs2Widget.isBankCollectBoxWidgetOpen();
    }
    public static boolean clickBankCollectWidget() {
        if (!isOpen()) return false;
        Microbot.log("Looking for Collect to bank widget");
        Widget BankCollectBox = Rs2Widget.findWidget("Collect to bank", List.of(getBankCollectBoxWidget()), false);
        if (BankCollectBox == null) return false;
        Rs2Widget.clickWidget(BankCollectBox);
        sleepUntilOnClientThread(() -> !isOpen());
        return true;
    }

    private static Widget getBankCollectBoxWidget() {
        if (!isOpen()) return null;
        return Rs2Widget.getWidget(COLLECT_BANK_PARENT_WIDGET_ID, 0);
    }
    private void handleSteel() {
        int coalInFurnace = Microbot.getVarbitValue(Varbits.BLAST_FURNACE_COAL);
        switch (coalInFurnace / MAX_ORE_PER_INTERACTION) {


            case 8:
                retrievePrimary();
                break;
            case 7:
            case 6:

            case 5:
            case 4:
            case 3:
            case 2:

            case 1:
                retrieveCoalAndPrimary();
                break;
            case 0:
                retrieveDoubleCoal();
                break;
            default:
                assert false : "how did you get there";
        }

    }

    private void handleMithril() {
        int coalInFurnace = Microbot.getVarbitValue(Varbits.BLAST_FURNACE_COAL);
        switch (coalInFurnace / MAX_ORE_PER_INTERACTION) {
            case 8:
                retrievePrimary();
                break;
            case 7:
            case 6:

            case 5:
            case 4:
            case 3:
            case 2:
                retrieveCoalAndPrimary();
                break;
            case 1:
                retrieveCoalAndPrimary();
                break;
            case 0:
                retrieveDoubleCoal();
                break;
            default:
                assert false : "how did you get there";

        }

    }

    private void handleAdamantite() {
        int coalInFurnace = Microbot.getVarbitValue(Varbits.BLAST_FURNACE_COAL);
        switch (coalInFurnace / MAX_ORE_PER_INTERACTION) {
            case 8:
                retrievePrimary();
                break;
            case 7:
            case 6:
            case 5:
            case 4:
            case 3:
                retrieveCoalAndPrimary();
                break;
            case 2:
                retrieveDoubleCoal();
                break;
            case 1:
                retrieveDoubleCoal();
                break;
            case 0:
                retrieveDoubleCoal();
                break;
            default:
                assert false : "how did you get there";
        }

    }

    private void handleRunite() {
        int coalInFurnace = Microbot.getVarbitValue(Varbits.BLAST_FURNACE_COAL);
        switch (coalInFurnace / MAX_ORE_PER_INTERACTION) {
            case 8:
                retrievePrimary();
                break;
            case 7:
            case 6:

            case 5:
            case 4:
                retrieveCoalAndPrimary();
                break;
            case 3:
                retrieveCoalAndPrimary();
                break;

            case 2:
                retrieveDoubleCoal();
                break;
            case 1:
                retrieveDoubleCoal();
                break;
            case 0:
                retrieveDoubleCoal();
                break;
            default:
                assert false : "how did you get there";
        }

    }
    /**
     * Withdraw & drink exactly one 1-dose pot.
     * No energy/buff checks here – they’re done once in BANKING.
     * @return true if a pot was pulled & drunk
     */
    private boolean useStaminaPotions()
    {
        final int POT = ItemID.STAMINA_POTION1; // 12631

        if (!Rs2Bank.hasItem(POT))
        {
            Microbot.log("No 1-dose stamina pots left!");
            Rs2Player.logout();
            return false;
        }
//        Microbot.log("WIthdrawing 1-dose stamina pot");
        if (Rs2Inventory.isFull()) {
            Rs2Bank.depositAllExcept(
                    COAL_BAG
            );
        }
        Rs2Bank.withdrawOne(POT);
        Rs2Inventory.waitForInventoryChanges(1000);

        if (Rs2Inventory.interact(POT, "Drink"))
        {
            sleepUntil(Rs2Player::hasStaminaBuffActive, 1000);
            if (Rs2Inventory.hasItem(ItemID.VIAL))
            {
                Rs2Bank.depositOne(ItemID.VIAL);
                Rs2Inventory.waitForInventoryChanges(1000);
            }
//            Microbot.log("Drank stamina pot.");
            return true;
        }

        return false;
    }

    private void depositOre() {
        Rs2GameObject.interact(ObjectID.CONVEYOR_BELT, "Put-ore-on");
        Rs2Inventory.waitForInventoryChanges(10000);
        if(Rs2Dialogue.hasDialogueText("You must ask the foreman's")){
//            Microbot.log("Need to pay the noob tax");
            handleTax();
            Rs2GameObject.interact(ObjectID.CONVEYOR_BELT, "Put-ore-on");
            Rs2Inventory.waitForInventoryChanges(10000);
        }
        if (this.config.getBars().isRequiresCoalBag()) {
            if (Rs2Equipment.isWearing(9795)) {
                Rs2Inventory.interact(coalBag, "Empty");
                Rs2Inventory.waitForInventoryChanges(3000);
                Rs2GameObject.interact(ObjectID.CONVEYOR_BELT, "Put-ore-on");
                Rs2Inventory.waitForInventoryChanges(3000);
                Rs2Inventory.interact(coalBag, "Empty");
                Rs2Inventory.waitForInventoryChanges(3000);
                Rs2GameObject.interact(ObjectID.CONVEYOR_BELT, "Put-ore-on");
                Rs2Inventory.waitForInventoryChanges(3000);
            }
            else {
                Rs2Inventory.interact(coalBag, "Empty");
                Rs2Inventory.waitForInventoryChanges(3000);
                Rs2GameObject.interact(ObjectID.CONVEYOR_BELT, "Put-ore-on");
                Rs2Inventory.waitForInventoryChanges(3000);
            }

        }
    }

    public int barsInDispenser(Bars bar) {
        switch (bar) {
            case STEEL_BAR:
                return Microbot.getVarbitValue(Varbits.BLAST_FURNACE_STEEL_BAR);
            case MITHRIL_BAR:
                return Microbot.getVarbitValue(Varbits.BLAST_FURNACE_MITHRIL_BAR);
            case ADAMANTITE_BAR:
                return Microbot.getVarbitValue(Varbits.BLAST_FURNACE_ADAMANTITE_BAR);
            case RUNITE_BAR:
                return Microbot.getVarbitValue(Varbits.BLAST_FURNACE_RUNITE_BAR);
            default:
                return -1;
        }
    }

    public boolean dispenserContainsBars() {
        int[] allBarVarbits = {
                Varbits.BLAST_FURNACE_IRON_BAR,
                Varbits.BLAST_FURNACE_STEEL_BAR,
                Varbits.BLAST_FURNACE_MITHRIL_BAR,
                Varbits.BLAST_FURNACE_ADAMANTITE_BAR,
                Varbits.BLAST_FURNACE_RUNITE_BAR
        };

        // Iterate through each bar and check its value
        for (int bar : allBarVarbits) {
            if (Microbot.getVarbitValue(bar) > 0) {
                // Return if bar is found
                return true;
            }
        }

        // Return true if no bars found
        return false;
    }

    private boolean hasRequiredOresInBank() {
        int primaryOre = this.config.getBars().getPrimaryOre();
        int secondaryOre = this.config.getBars().getSecondaryOre() == null ? -1 : this.config.getBars().getSecondaryOre();
        boolean hasPrimaryOre = Rs2Bank.hasItem(primaryOre);
        boolean hasSecondaryOre = secondaryOre != -1 && Rs2Bank.hasItem(secondaryOre);
        return hasPrimaryOre && hasSecondaryOre;
    }
    private void initializeBreakSchedule() {
        long now = System.currentTimeMillis();
        nextMicroBreakTime = now + getRandomMicroInterval();
        nextMacroBreakTime = now + getRandomMacroInterval();
    }

    // ── INTERVAL GENERATORS ───────────────────────────────────────────────────────
    /** ~2 micro-breaks per hour → mean 30 min (1 800 000 ms), σ ≈10 min (600 000 ms) */
    private static long getRandomMicroInterval() {
        return Rs2Random.randomGaussian(4_200_000, 420_000);
    }
    /** ~0.5 macro-breaks per hour → mean 2 h (7_200_000 ms), σ ≈30 min (1_800_000 ms) */
    private static long getRandomMacroInterval() {
        return Rs2Random.randomGaussian(11_420_000, 1_800_000);
    }

// ── DURATION GENERATORS ───────────────────────────────────────────────────────
    /** Micro-break length: mean 3 min (180 000 ms), σ ≈1 min (60 000 ms) */
    private static long getRandomMicroDuration() {
        return Rs2Random.nextInt(60_000, 175_000, 2.0, false);
    }
    /** Macro-break length: mean 20 min (1_200 000 ms), σ ≈5 min (300 000 ms) */
    private static long getRandomMacroDuration() {
        return Rs2Random.randomGaussian(420_000, 150_000);
    }

    private void performBreak(long durationMs) {
        // climb upstairs…
        Rs2GameObject.interact(9138, "Climb-up");
        Rs2Player.waitForAnimation();
        sleepUntil(() -> !Rs2Player.isAnimating());

        // sit idle for the break duration (cast to ints)
        int dur   = (int) durationMs;
        int sigma = (int) (durationMs * 0.1);
        sleepGaussian(dur, sigma);

        // climb back down…
        Rs2GameObject.interact(9084, "Climb-down");
        Rs2Player.waitForAnimation();
        sleepUntil(() -> !Rs2Player.isAnimating());

        // reopen bank to resume…
        Rs2Bank.openBank();
        sleepUntil(Rs2Bank::isOpen, 2000);
    }
// ── UPDATED simulateRandomBreak ───────────────────────────────────────────────
    /** Now handles both micro- and macro-breaks, scheduled independently. */
    private void simulateRandomBreak() {
        long now = System.currentTimeMillis();

        // Macro breaks first (they’re rarer but longer)
        if (now >= nextMacroBreakTime) {
            Microbot.log("========== TAKING A MACRO BREAK ==========");
            performBreak(getRandomMacroDuration());
            // schedule next macro AND reset micro so they don't immediately fire
            nextMacroBreakTime = now + getRandomMacroInterval();
            nextMicroBreakTime = now + getRandomMicroInterval();
            return;
        }

        // Otherwise micro-breaks
        if (now >= nextMicroBreakTime) {
            Microbot.log("========== TAKING A MICRO BREAK ==========");
            performBreak(getRandomMicroDuration());
            nextMicroBreakTime = now + getRandomMicroInterval();
        }
    }

    public void shutdown() {
        if (mainScheduledFuture != null && !mainScheduledFuture.isDone()) {
            mainScheduledFuture.cancel(true);
            ShortestPathPlugin.exit();
            if (Microbot.getClientThread().scheduledFuture != null)
                Microbot.getClientThread().scheduledFuture.cancel(true);
            initialPlayerLocation = null;
            Microbot.pauseAllScripts = false;
            Microbot.getSpecialAttackConfigs().reset();
        }


        state = State.BANKING;
        primaryOreEmpty = false;
        secondaryOreEmpty = false;
        super.shutdown();
    }
}
