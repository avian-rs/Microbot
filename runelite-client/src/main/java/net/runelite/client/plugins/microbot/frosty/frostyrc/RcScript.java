package net.runelite.client.plugins.microbot.frosty.frostyrc;

import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.client.plugins.microbot.frosty.frostyrc.enums.State;
import net.runelite.client.plugins.microbot.breakhandler.BreakHandlerScript;
import net.runelite.client.plugins.microbot.frosty.frostyrc.enums.Teleports;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.tabs.Rs2Tab;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import javax.inject.Inject;
import java.awt.event.KeyEvent;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class RcScript extends Script {
    private final RcPlugin plugin;
    public static State state;

    private final WorldPoint caveFairyRing = new WorldPoint(3447, 9824, 0);
    private final WorldPoint firstCaveExit = new WorldPoint(3460, 9813, 0);
    private final WorldPoint outsideBloodRuins = new WorldPoint(3558, 9779, 0);

    public static final int questCapeTeleportRegion = 10804;
    public static final int bloodAltarRegion = 12875;
    public static final int pureEss = 7936;

    public static final int bloodRuins = ObjectID.BLOODTEMPLE_RUINED;
    public static final int bloodAltar = ObjectID.BLOOD_ALTAR;

    public static final int activeBloodEssence = ItemID.BLOOD_ESSENCE_ACTIVE;
    public static final int inactiveBloodEssence = ItemID.BLOOD_ESSENCE_INACTIVE;
    public static final int bloodRune = ItemID.BLOODRUNE;
    public static final int colossalPouch = ItemID.RCU_POUCH_COLOSSAL;
    public static final int runePouch = ItemID.BH_RUNE_POUCH;

    private static boolean repairingPouch = false;

    @Inject
    public RcScript(RcPlugin plugin) {
        this.plugin = plugin;
    }

    @Inject
    private RcConfig config;
    @Inject
    private Client client;
    @Inject
    private ClientThread clientThread;

    public boolean run() {
        Microbot.enableAutoRunOn = false;
        Rs2Antiban.resetAntibanSettings();
        Rs2Antiban.antibanSetupTemplates.applyRunecraftingSetup();
        Microbot.log("Script has started");
        state = State.BANKING;
        sleepGaussian(500,100);
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn()) return;
                if (!super.run()) return;
                long startTime = System.currentTimeMillis();

                if (Rs2Inventory.anyPouchUnknown()) {
                    checkPouches();
                    return;
                }

                repairingPouch = false;

                switch (state) {
                    case BANKING:
                        handleBanking();
                        break;
                    case GOING_HOME:
                        handleQuestCape();
                        break;
                    case WALKING_TO:
                        handleWalking();
                        break;
                    case CRAFTING:
                        handleCrafting();
                        return;
                }

                long endTime = System.currentTimeMillis();
                long totalTime = endTime - startTime;
                System.out.println("Total time for loop " + totalTime);

            } catch (Exception ex) {
                System.out.println(ex.getMessage());
                Microbot.log("Error in script" + ex.getMessage());
            }
        }, 0, Rs2Random.randomGaussian(650, 100), TimeUnit.MILLISECONDS);
        return true;
    }

    @Override
    public void shutdown() {
        Rs2Antiban.resetAntibanSettings();
        super.shutdown();
        Microbot.log("Script has been stopped");
        //Rs2Player.logout();
    }

    private void checkPouches() {
        Rs2Inventory.interact(colossalPouch, "Check");
        sleepGaussian(500, 100);
    }

    private void handleBanking() {
        int currentRegion = plugin.getMyWorldPoint().getRegionID();

        if (!Teleports.CRAFTING_CAPE.matchesRegion(currentRegion)) {
            Microbot.log("Not in banking region, teleporting");
            handleBankTeleport();
        }
        Rs2Tab.switchToInventoryTab();

        if (Rs2Inventory.isFull() && Rs2Inventory.allPouchesFull() && Rs2Inventory.contains("Pure essence")) {
            Microbot.log("We are full, skipping bank");
            state = State.GOING_HOME;
            return;
        }

        if (plugin.isBreakHandlerEnabled()) {
            BreakHandlerScript.setLockState(false);
        }

        while (!Rs2Bank.isOpen() && isRunning() &&
                (!Rs2Inventory.allPouchesFull()
                        || !Rs2Inventory.contains(colossalPouch)
                        || !Rs2Inventory.contains(pureEss))) {
            Microbot.log("Opening bank");
            Rs2Bank.openBank();
            sleepUntil(Rs2Bank::isOpen, 2500);
            sleepGaussian(400, 100);
        }

        if (!Rs2Equipment.isWearing("Quest point cape")) {
            Rs2Bank.withdrawAndEquip("Quest point cape");
            sleepGaussian(500, 200);
        }

        if (!Rs2Inventory.hasAnyPouch()) {
            Rs2Bank.withdrawItem(colossalPouch);
            sleepGaussian(500, 100);
        }

        if (!Rs2Inventory.contains(activeBloodEssence)) {
            if (!Rs2Bank.hasItem(activeBloodEssence)) {
                Rs2Bank.withdrawItem(inactiveBloodEssence);
                Microbot.log("Withdrawing blood essence");
                sleepUntil(() -> Rs2Inventory.contains(inactiveBloodEssence), 1200);
                Rs2Bank.closeBank();
                sleepUntil(() -> !Rs2Bank.isOpen(), 1200);
                Rs2Inventory.interact(inactiveBloodEssence, "Activate");
                Microbot.log("Activating blood essence");
                sleepGaussian(500, 100);
                Rs2Bank.openBank();
                sleepUntil(Rs2Bank::isOpen, 1200);
            } else {
                Rs2Bank.withdrawItem(activeBloodEssence);
                sleepGaussian(500, 100);
            }
        }

        if (Rs2Inventory.hasDegradedPouch()) {
            repairingPouch = true;
            Microbot.log("Found degraded pouch, withdraw rune pouch");
            Rs2Bank.withdrawRunePouch();
            sleepGaussian(500, 100);
            Rs2Bank.closeBank();
            sleepUntil(() -> !Rs2Bank.isOpen(), 1200);
            Rs2Magic.repairPouchesWithLunar();
            Microbot.log("Repaired Pouch with Lunar");
            sleepGaussian(500, 100);
            Rs2Bank.openBank();
            sleepUntil(Rs2Bank::isOpen, 1200);
            Rs2Bank.depositRunePouch();
            Microbot.log("deposited rune pouch");
            Rs2Bank.closeBank();
            sleepUntil(() -> !Rs2Bank.isOpen(), 700);
        }

        if (repairingPouch) {
            Microbot.log("Filling pouch after repairing.");
            Rs2Tab.switchToInventoryTab();

            Rs2Bank.openBank();
            sleepUntil(Rs2Bank::isOpen, 700);

            while (Rs2Inventory.getRemainingCapacityInPouches() > 0) {
                Microbot.log("Current remaining capacity: " + Rs2Inventory.getRemainingCapacityInPouches());

                if (Rs2Bank.isOpen()) {
                    Microbot.log("After repairing: in while loop: bank is open");

                    if (Rs2Inventory.contains(bloodRune)) {
                        Microbot.log("After repairing: found blood rune, deposit");

                        Rs2Bank.depositAll(bloodRune);
                        sleepGaussian(400, 100);
                    }
                    Microbot.log("After repairing: withdraw all pure essence");
                    Rs2Bank.withdrawAll(pureEss);

                    Microbot.log("Before fill pouches Remaining capacity: " + Rs2Inventory.getRemainingCapacityInPouches());
                    Rs2Inventory.fillPouches();
                    sleepGaussian(400, 100);
                    Rs2Bank.openBank();
                    sleepUntil(Rs2Bank::isOpen, 700);
                    Microbot.log("After fill pouches Remaining capacity: " + Rs2Inventory.getRemainingCapacityInPouches());
                }

                if (!Rs2Inventory.isFull()) {
                    Microbot.log("Inventory is not full in repair state");

                    Rs2Bank.withdrawAll(pureEss);
                    sleepUntil(Rs2Inventory::isFull);
                    Microbot.log("Withdrew all pure essence");

                }
            }
        }
        else{
//            while (!Rs2Inventory.allPouchesFull() || !Rs2Inventory.isFull() && isRunning()) {
            while (Rs2Inventory.getRemainingCapacityInPouches() > 0) {
                Microbot.log("Pouches are not full yet");
                if (Rs2Bank.isOpen()) {
                    Microbot.log("Pouches not full, bank open");

                    if (Rs2Inventory.contains(bloodRune)) {
                        Rs2Bank.depositAll(bloodRune);
                        sleepGaussian(400, 100);
                    }
                    Rs2Bank.withdrawAll(pureEss);
                    sleepGaussian(400, 100);
                    Microbot.log("Pouches not fullbank open , withdraw all pure ess");

                    Rs2Inventory.fillPouches();
                    Microbot.log("Pouches not full bank open, filled pouches");

                    sleepGaussian(400, 100);
                }
                if (!Rs2Inventory.isFull()) {
                    Microbot.log("Pouches are not full yet, withdraw all pure ess");

                    Rs2Bank.withdrawAll(pureEss);
                    sleepUntil(Rs2Inventory::isFull);
                }
            }
        }

        if (Rs2Bank.isOpen() && Rs2Inventory.allPouchesFull() && Rs2Inventory.isFull()) {
            Microbot.log("We are full, lets go");
            Microbot.log("Remaining capacity: " + Rs2Inventory.getRemainingCapacityInPouches());
            Rs2Keyboard.keyPress(KeyEvent.VK_ESCAPE);

            sleepUntil(() -> !Rs2Bank.isOpen(), 700);
            state = State.GOING_HOME;
        }
    }

    private void handleQuestCape() {
        Teleports questCapeTeleport = Teleports.QUEST_CAPE;

        if (plugin.isBreakHandlerEnabled()) {
            BreakHandlerScript.setLockState(true);
        }

        for (Integer itemId : questCapeTeleport.getItemIds()) {
            if (Rs2Equipment.isWearing(itemId)) {
                sleepGaussian(300,100);
                Rs2Equipment.interact(itemId, questCapeTeleport.getInteraction());
                sleepUntil(() -> plugin.getMyWorldPoint().getRegionID() == (questCapeTeleportRegion));
//                sleepGaussian(300, 100);
            }
        }

        TileObject fairyRing = Rs2GameObject.getAll().stream()
                .filter(Objects::nonNull)
                .filter(obj -> obj.getLocalLocation().distanceTo(Microbot.getClient().getLocalPlayer().getLocalLocation()) < 5000)
                .filter(obj -> {
                    ObjectComposition composition = Rs2GameObject.getObjectComposition(obj.getId());
                    if (composition == null) return false;
                    return composition.getName().toLowerCase().contains("fairy");
                })
                .findFirst().orElse(null);

        if (fairyRing == null) {
            Microbot.log("Unable to find fairies, resetting from bank to retry");
            state = State.BANKING;
            return;
        } else {
            Microbot.log("Interacting with fairy ring");
            Rs2GameObject.interact(fairyRing, "Last-destination (DLS)");
            sleepUntil(() -> plugin.getMyWorldPoint().equals(caveFairyRing));
        }
        state = State.WALKING_TO;
    }

    private boolean isInBloodRuinsArea(WorldPoint point) {
        return point.getX() >= 3555 && point.getX() <= 3563 &&
                point.getY() >= 9779 && point.getY() <= 9780 &&
                point.getPlane() == 0;
    }

    private void handleWalking() {
        if (plugin.isBreakHandlerEnabled()) {
            BreakHandlerScript.setLockState(true);
        }

        Microbot.log("Current location after waiting: " + plugin.getMyWorldPoint());
        if (plugin.getMyWorldPoint().equals(caveFairyRing)) {
            sleepGaussian(300, 100);
            Rs2GameObject.interact(16308, "Enter");
            sleepUntil(() -> Rs2Player.getWorldLocation().equals(firstCaveExit), 1200);
            sleepGaussian(300, 100);
        }

        if (plugin.getMyWorldPoint().equals(firstCaveExit)) {
            Microbot.log("Walking to ruins: " + outsideBloodRuins);
            Rs2Walker.walkTo(outsideBloodRuins);
            sleepUntil(() -> isInBloodRuinsArea(plugin.getMyWorldPoint()), 700);
            state = State.CRAFTING;
        }
    }

    private void handleCrafting() {
        if (plugin.isBreakHandlerEnabled()) {
            BreakHandlerScript.setLockState(true);
        }

        Rs2GameObject.interact(bloodRuins, "Enter");
        sleepUntil(() -> !Rs2Player.isAnimating() && plugin.getMyWorldPoint().getRegionID() == bloodAltarRegion);
        sleepGaussian(250, 75);
        Rs2GameObject.interact(bloodAltar, "Craft-rune");
        Rs2Player.waitForXpDrop(Skill.RUNECRAFT);
        plugin.updateXpGained();

        handleEmptyPouch();

        while (plugin.getMyWorldPoint().getRegionID() == bloodAltarRegion && isRunning()) {
            if (Rs2Inventory.allPouchesEmpty() && !Rs2Inventory.contains("Pure essence")) {
                Microbot.log("We are in altar region and out of p ess, banking...");
                handleBankTeleport();
                sleepGaussian(200, 50);
            }
        }
        state = State.BANKING;
    }

    private void handleEmptyPouch() {
        while (!Rs2Inventory.allPouchesEmpty() && isRunning()) {
            Microbot.log("Pouches are not empty. Crafting more");
            Rs2Inventory.emptyPouches();
            Rs2Inventory.waitForInventoryChanges(600);
            sleepGaussian(400, 100);
            Rs2GameObject.interact(bloodAltar, "Craft-rune");
            Rs2Player.waitForXpDrop(Skill.RUNECRAFT);
            plugin.updateXpGained();
        }
    }

    private void handleBankTeleport() {
        Rs2Tab.switchToInventoryTab();
        sleepGaussian(800, 200);

        Teleports craftingCapeTeleport = Teleports.CRAFTING_CAPE;
        for (Integer id : craftingCapeTeleport.getItemIds()) {
            if (Rs2Inventory.contains(id)) {
                if (Rs2Inventory.interact(id, craftingCapeTeleport.getInteraction())) {
                    return;
                }
            }
        }
        sleepGaussian(400, 100);
        sleepUntil(() -> plugin.getMyWorldPoint().getRegionID() == craftingCapeTeleport.getBankingRegionIds()[0]);
    }
}