

package net.runelite.client.plugins.microbot.blastoisefurnace;

import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import net.runelite.api.widgets.ComponentID;
import net.runelite.client.plugins.microbot.shortestpath.ShortestPathPlugin;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings;

import net.runelite.api.ItemID;
import net.runelite.api.ObjectID;
import net.runelite.client.plugins.microbot.util.dialogues.Rs2Dialogue;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.npc.Rs2NpcModel;
import net.runelite.api.Varbits;
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

    private boolean hasRequiredOresForSmithing() {
        int primaryOre = this.config.getBars().getPrimaryOre();
        int secondaryOre = this.config.getBars().getSecondaryOre() == null ? -1 : this.config.getBars().getSecondaryOre();
        boolean hasPrimaryOre = Rs2Bank.hasItem(primaryOre);
        boolean hasSecondaryOre = secondaryOre != -1 && Rs2Bank.hasItem(secondaryOre);
        return hasPrimaryOre && hasSecondaryOre;
    }

    public boolean run(BlastoiseFurnaceConfig config) {
        staminaTimer = 0;
        this.config = config;
        Microbot.enableAutoRunOn = false;
        state = State.BANKING;
        primaryOreEmpty = !Rs2Inventory.hasItem(config.getBars().getPrimaryOre());
        secondaryOreEmpty = !Rs2Inventory.hasItem(config.getBars().getSecondaryOre());
        Rs2Antiban.resetAntibanSettings();
        Rs2Antiban.antibanSetupTemplates.applySmithingSetup();

        this.mainScheduledFuture = this.scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn()) {
                    return;
                }

                if (!super.run()) {
                    return;
                }

                switch (state) {
                    case BANKING:
                        Microbot.status = "Banking";
                        if (!Rs2Bank.isOpen()) {
                            System.out.println("Opening bank");
                            Rs2Bank.openBank();
                            sleepUntil(Rs2Bank::isOpen, 20000);
                        }

                        if (config.getBars().isRequiresCoalBag() && !Rs2Inventory.contains(coalBag)) {
                            if (!Rs2Bank.hasItem(coalBag)) {
                                Microbot.showMessage("No coal bag found in inventory and bank.");
                                this.shutdown();
                                return;
                            }

                            Rs2Bank.withdrawItem(coalBag);
                        }

                        if (Rs2Inventory.hasItem("bar")) {
                            Rs2Bank.depositAllExcept(coalBag, ItemID.ICE_GLOVES, ItemID.SMITHS_GLOVES_I);
                        }

                        if (!this.hasRequiredOresForSmithing()) {
                            Microbot.log("Out of ores. Walking you out for coffer safety");
                            Rs2Walker.walkTo(new WorldPoint(2930, 10196, 0));
                            Rs2Player.logout();
                            this.shutdown();
                        }

                        if (!Rs2Player.hasStaminaBuffActive() && Microbot.getClient().getEnergy() < 6100) {
                            this.useStaminaPotions();
                        }

                        // Check here if dispenser contains bars. If so we need to clean-up
                        if (dispenserContainsBars()) {
                            Rs2Bank.depositAllExcept(coalBag, ItemID.ICE_GLOVES, ItemID.SMITHS_GLOVES_I);
                            handleDispenserLooting();
                            return;
                        }else {
                            this.retrieveItemsForCurrentFurnaceInteraction();
                            state = State.SMITHING;
                        }
                        break;
                    case SMITHING:
                        System.out.println("clicking conveyor");

                        if (barsInDispenser(config.getBars()) > 0) {
                            handleDispenserLooting();
                        }

                        state = State.BANKING;
                        break;
                }
            } catch (Exception ex) {

                Microbot.logStackTrace(this.getClass().getSimpleName(), ex);
            }

        }, 0, Rs2Random.randomGaussian(650, 100), TimeUnit.MILLISECONDS);
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
        sleep(500, 1200);
        Rs2Bank.depositOne(ItemID.COAL);
        sleep(500, 1200);
        Rs2Bank.withdrawX(ItemID.COINS_995,2500);
        sleep(500, 1200);
        Rs2Bank.closeBank();
        sleep(500, 1200);
        Rs2NpcModel blastie = Rs2Npc.getNpc("Blast Furnace Foreman");
        Rs2Npc.interact(blastie, "Pay");
        sleepUntil(Rs2Dialogue::isInDialogue,10000);
        if (Rs2Dialogue.hasSelectAnOption()) {
            Rs2Dialogue.clickOption("Yes");
            sleep(1000, 1850);
            Rs2Dialogue.clickContinue();
            sleep(500, 1300);

        }
    }

    private void handleDispenserLooting() {

        // Check if the inventory is full before interacting with the dispenser
        if (!Rs2Inventory.isFull()) {
            Rs2GameObject.interact(BAR_DISPENSER, "Take");

            sleepUntil(() ->
                    Rs2Widget.hasWidget("What would you like to take?") ||
                    Rs2Widget.hasWidget("How many would you like") ||
                    Rs2Widget.hasWidget("The bars are still molten!"), 5000);

            boolean noIceGlovesEquipped = Rs2Widget.hasWidget("The bars are still molten!");

            if (noIceGlovesEquipped){
                if (!Rs2Inventory.interact(ItemID.ICE_GLOVES, "Wear") && !Rs2Inventory.interact(ItemID.SMITHS_GLOVES_I, "Wear")) {
                    Microbot.showMessage("Ice gloves or smith gloves required to loot the hot bars.");
                    Rs2Player.logout();
                    this.shutdown();
                    return;
                }
                Rs2GameObject.interact(BAR_DISPENSER, "Take");
            }

            sleepUntil(() -> Rs2Widget.hasWidget("What would you like to take?") || Rs2Widget.hasWidget("How many would you like"), 3000);

            // If somehow multiple type of bars are created we need to clean up the dispenser.
            boolean multipleBarTypes = Rs2Widget.hasWidget("What would you like to take?");
            boolean canLootBar = Rs2Widget.hasWidget("How many would you like");

            if (super.run()) {
                if (canLootBar) {
                    Rs2Keyboard.keyPress(KeyEvent.VK_SPACE);
                } else if (multipleBarTypes) {
                    Rs2Keyboard.keyPress(KeyEvent.VK_SPACE);
                }
                Rs2Inventory.waitForInventoryChanges(5000);
            }
        }

        state = State.BANKING;
    }

    private void retrieveCoalAndPrimary() {
        int primaryOre = this.config.getBars().getPrimaryOre();
        if (!Rs2Inventory.hasItem(primaryOre)) {
            Rs2Bank.withdrawAll(primaryOre);
            sleepGaussian(500,100);
            return;
        }

        boolean fullCoalBag = Rs2Inventory.interact(coalBag, "Fill");
        if (!fullCoalBag)
            return;
        sleepGaussian(500,100);
        depositOre();
        Rs2Walker.walkFastCanvas(BlastoiseFurnaceScript.getRandomWeightedPoint());
        sleepGaussian(1700,400);
        sleepUntil(() -> barsInDispenser(config.getBars()) > 0, 10000);
        sleepGaussian(500,100);
    }

    private void retrievePrimary() {
        int primaryOre = config.getBars().getPrimaryOre();
        if (!Rs2Inventory.hasItem(primaryOre)) {
            Rs2Bank.withdrawAll(primaryOre);
            return;
        }
        depositOre();

        Rs2Walker.walkFastCanvas(BlastoiseFurnaceScript.getRandomWeightedPoint());
        sleepGaussian(1700,400);
        sleepUntil(() -> barsInDispenser(this.config.getBars()) > 0, 10000);
        sleepGaussian(300,100);
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

    private void useStaminaPotions() {

        boolean usedPotion = false;

        // Step 2: If energy is above 71% but below 81%, use Stamina potion if no stamina buff is active
        if (Microbot.getClient().getEnergy() < 6100 && !Rs2Player.hasStaminaBuffActive()) {
            usedPotion = usePotionIfNeeded(12631, 6100);
        }

        // Sleep after using a potion
        if (usedPotion) {
            sleepGaussian(250,50);
        }
    }

    private boolean usePotionIfNeeded(int id, int energyThreshold) {
        if (Microbot.getClient().getEnergy() < energyThreshold) {
            if (withdrawPotion(id)) {
                if (drinkPotion(id)) {
                    depositItems(id);
                    return true; // Potion was successfully used
                }
            }
        }
        return false; // Potion was not used
    }

    private boolean withdrawPotion(String potionName) {
        Rs2Bank.withdrawOne(potionName);
        sleepGaussian(650,150);
        return true;
    }
    private boolean withdrawPotion(int id) {
        Rs2Bank.withdrawOne(id);
        sleepGaussian(650,150);
        return true;
    }

    private boolean drinkPotion(int id) {
        Rs2Inventory.interact(id, "Drink");
        sleepGaussian(650,150);
        return true;
    }

    private void depositItems(String potionName) {
        if (Rs2Inventory.hasItem(potionName)) {
            Rs2Bank.depositOne(potionName);
        }
        if (Rs2Inventory.hasItem(ItemID.VIAL)) {
            Rs2Bank.depositOne(ItemID.VIAL);
        }
    }
    private void depositItems(int id) {
        if (Rs2Inventory.hasItem(id)) {
            Rs2Bank.depositOne(id);
        }
        if (Rs2Inventory.hasItem(ItemID.VIAL)) {
            Rs2Bank.depositOne(ItemID.VIAL);
        }
    }

    private void depositOre() {
        Rs2GameObject.interact(ObjectID.CONVEYOR_BELT, "Put-ore-on");
        Rs2Inventory.waitForInventoryChanges(10000);
        if(Rs2Dialogue.hasDialogueText("You must ask the foreman's")){
            Microbot.log("Need to pay the noob tax");
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



    private void applyAntiBanSettings() {
        Rs2AntibanSettings.antibanEnabled = true;
        Rs2AntibanSettings.naturalMouse = true;
        Rs2AntibanSettings.devDebug = true;
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
