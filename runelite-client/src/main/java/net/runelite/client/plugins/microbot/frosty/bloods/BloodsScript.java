package net.runelite.client.plugins.microbot.frosty.bloods;

import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.client.plugins.microbot.frosty.bloods.enums.State;
import net.runelite.client.plugins.microbot.breakhandler.BreakHandlerScript;
import net.runelite.client.plugins.microbot.frosty.bloods.enums.Teleports;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.antiban.enums.Activity;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.equipment.Rs2Equipment;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.tabs.Rs2Tab;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import javax.inject.Inject;
import java.awt.event.KeyEvent;
import java.util.*;
import java.util.concurrent.TimeUnit;

public class BloodsScript extends Script {
    private final BloodsPlugin plugin;
    public static State state;

    private final WorldPoint questCapeFairyRing = new WorldPoint(2738, 3351, 0);
    private final WorldPoint caveFairyRing = new WorldPoint(3447, 9824, 0);
    private final WorldPoint firstCaveExit = new WorldPoint(3460, 9813, 0);
    private final WorldPoint outsideBloodRuins = new WorldPoint(3558, 9779, 0);
    private final ArrayList<WorldPoint> outsideBloodRuinsArray = new ArrayList<>();

    public static final int questCapeTeleportRegion = 10804;
    public static final int bloodAltarRegion = 12875;
    public static final int pureEss = 7936;

    public static final int bloodRuins = ObjectID.BLOODTEMPLE_RUINED;
    public static final int bloodAltar = ObjectID.BLOOD_ALTAR;

    public static final int activeBloodEssence = ItemID.BLOOD_ESSENCE_ACTIVE;
    public static final int inactiveBloodEssence = ItemID.BLOOD_ESSENCE_INACTIVE;
    public static final int bloodRune = ItemID.BLOODRUNE;
    public static final int colossalPouch = ItemID.RCU_POUCH_COLOSSAL;

    private static boolean repairingPouch = false;

    @Inject
    public BloodsScript(BloodsPlugin plugin) {
        this.plugin = plugin;
    }

    @Inject
    private BloodsConfig config;
    @Inject
    private Client client;
    @Inject
    private ClientThread clientThread;

    public boolean run() {
        Microbot.enableAutoRunOn = false;
        Rs2Antiban.resetAntibanSettings();
        Rs2Antiban.antibanSetupTemplates.applyRunecraftingSetup();
        Rs2Antiban.setActivity(Activity.CRAFTING_BLOODS_TRUE_ALTAR);
//        Rs2Camera.setZoom(300);
//        Rs2Camera.setPitch(369);
        sleepGaussian(700, 200);
        state = State.BANKING;
        Microbot.log("Script has started");
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
        }, 0, 1000, TimeUnit.MILLISECONDS);
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
        sleepGaussian(900, 200);
    }

    private void handleBanking() {
        int currentRegion = plugin.getMyWorldPoint().getRegionID();

        if (!Teleports.EDGEVILLE_TELEPORT.matchesRegion(currentRegion)) {
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
//            Rs2Bank.openBank(Rs2GameObject.findObjectByLocation(new WorldPoint(3095, 3491, 0)));
        Rs2Bank.openBank();
            sleepUntil(Rs2Bank::isOpen, 2500);
            sleepGaussian(800, 200);
        }

        if (!Rs2Equipment.isWearing("Quest point cape")) {
            Rs2Bank.withdrawAndEquip("Quest point cape");
            sleepGaussian(500, 200);
        }

        if (!Rs2Inventory.hasAnyPouch()) {
            Rs2Bank.withdrawItem(colossalPouch);
            sleepGaussian(700, 200);
        }

        if (!Rs2Inventory.contains(activeBloodEssence)) {
            if (!Rs2Bank.hasItem(activeBloodEssence)) {
                Rs2Bank.withdrawItem(inactiveBloodEssence);
                Microbot.log("Withdrawing blood essence");
                sleepGaussian(700, 200);
            } else {
                Rs2Bank.withdrawItem(activeBloodEssence);
                sleepGaussian(700, 200);
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
            sleepUntil(() -> !Rs2Bank.isOpen(), 1200);
            return;
        }
        if (repairingPouch) {
            Microbot.log("Filling pouch after repairing.");
            Rs2Bank.openBank();
            sleepUntil(Rs2Bank::isOpen, 1200);
            Microbot.log("After repairing: opened bank");
            while (!Rs2Inventory.allPouchesFull() || !Rs2Inventory.isFull() && isRunning()) {
                Microbot.log("After repairing: in while loop");
                if (Rs2Bank.isOpen()) {
                    if (Rs2Inventory.contains(bloodRune)) {
                        Rs2Bank.depositAll(bloodRune);
                        sleepGaussian(500, 200);
                    }
                    Rs2Bank.withdrawAll(pureEss);
                    Rs2Inventory.fillPouches();
                    sleepGaussian(700, 200);
                }
                if (!Rs2Inventory.isFull()) {
                    Rs2Bank.withdrawAll(pureEss);
                    sleepUntil(Rs2Inventory::isFull);
                }
            }
        }
        else{
            while (!Rs2Inventory.allPouchesFull() || !Rs2Inventory.isFull() && isRunning()) {
                Microbot.log("Pouches are not full yet");
                if (Rs2Bank.isOpen()) {
                    if (Rs2Inventory.contains(bloodRune)) {
                        Rs2Bank.depositAll(bloodRune);
                        sleepGaussian(500, 200);
                    }
                    Rs2Bank.withdrawAll(pureEss);
                    Rs2Inventory.fillPouches();
                    sleepGaussian(700, 200);
                }
                if (!Rs2Inventory.isFull()) {
                    Rs2Bank.withdrawAll(pureEss);
                    sleepUntil(Rs2Inventory::isFull);
                }
            }
        }

        if (Rs2Bank.isOpen() && Rs2Inventory.allPouchesFull() && Rs2Inventory.isFull()) {
            Microbot.log("We are full, lets go");
            Rs2Keyboard.keyPress(KeyEvent.VK_ESCAPE);
            sleepUntil(() -> !Rs2Bank.isOpen(), 1200);
            if (Rs2Inventory.contains(inactiveBloodEssence)) {
                Rs2Inventory.interact(inactiveBloodEssence, "Activate");
                Microbot.log("Activating blood essence");
                sleepGaussian(700, 200);
            }
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
                Rs2Equipment.interact(itemId, questCapeTeleport.getInteraction());
                sleepUntil(() -> plugin.getMyWorldPoint().getRegionID() == (questCapeTeleportRegion));
                sleepGaussian(500, 200);
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

    private void handleWalking() {
        if (plugin.isBreakHandlerEnabled()) {
            BreakHandlerScript.setLockState(true);
        }

        Microbot.log("Current location after waiting: " + plugin.getMyWorldPoint());
        if (plugin.getMyWorldPoint().equals(caveFairyRing)) {
            sleepGaussian(900, 200);
            Rs2GameObject.interact(16308, "Enter");
            sleepUntil(() -> Rs2Player.getWorldLocation().equals(firstCaveExit), 1200);
            sleepGaussian(900, 200);
        }

        if (plugin.getMyWorldPoint().equals(firstCaveExit)) {
            Microbot.log("Walking to ruins: " + outsideBloodRuins);
            Rs2Walker.walkTo(outsideBloodRuins);
            outsideBloodRuinsArray.add(new WorldPoint(3555, 9779, 0));
            outsideBloodRuinsArray.add(new WorldPoint(3556, 9779, 0));
            outsideBloodRuinsArray.add(new WorldPoint(3557, 9779, 0));
            outsideBloodRuinsArray.add(new WorldPoint(3558, 9779, 0));
            outsideBloodRuinsArray.add(new WorldPoint(3559, 9779, 0));
            outsideBloodRuinsArray.add(new WorldPoint(3560, 9779, 0));
            outsideBloodRuinsArray.add(new WorldPoint(3561, 9779, 0));
            outsideBloodRuinsArray.add(new WorldPoint(3562, 9779, 0));
            outsideBloodRuinsArray.add(new WorldPoint(3563, 9779, 0));
            outsideBloodRuinsArray.add(new WorldPoint(3555, 9780, 0));
            outsideBloodRuinsArray.add(new WorldPoint(3556, 9780, 0));
            outsideBloodRuinsArray.add(new WorldPoint(3557, 9780, 0));
            outsideBloodRuinsArray.add(new WorldPoint(3558, 9780, 0));
            outsideBloodRuinsArray.add(new WorldPoint(3559, 9780, 0));
            outsideBloodRuinsArray.add(new WorldPoint(3560, 9780, 0));
            outsideBloodRuinsArray.add(new WorldPoint(3561, 9780, 0));
            outsideBloodRuinsArray.add(new WorldPoint(3562, 9780, 0));
            outsideBloodRuinsArray.add(new WorldPoint(3563, 9780, 0));

            sleepUntil(() -> outsideBloodRuinsArray.stream()
                    .anyMatch(p -> p.equals(plugin.getMyWorldPoint())), 1200);

            outsideBloodRuinsArray.clear();
            state = State.CRAFTING;
        }
    }

    private void handleCrafting() {
        if (plugin.isBreakHandlerEnabled()) {
            BreakHandlerScript.setLockState(true);
        }

        Rs2GameObject.interact(bloodRuins, "Enter");
        sleepUntil(() -> !Rs2Player.isAnimating() && plugin.getMyWorldPoint().getRegionID() == bloodAltarRegion);
        sleepGaussian(700, 200);
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
            sleepGaussian(700, 200);
            Rs2GameObject.interact(bloodAltar, "Craft-rune");
            Rs2Player.waitForXpDrop(Skill.RUNECRAFT);
            plugin.updateXpGained();
        }
    }

    private void handleBankTeleport() {
        Rs2Tab.switchToEquipmentTab();
        sleepGaussian(800, 200);

        Teleports edgevilleTeleport = Teleports.EDGEVILLE_TELEPORT;
        Optional<Integer> rodId = Arrays.stream(edgevilleTeleport.getItemIds())
                .filter(Rs2Equipment::isWearing)
                .findFirst();
        rodId.ifPresent(id -> Rs2Equipment.interact(id, edgevilleTeleport.getInteraction()));
        sleepUntil(() -> plugin.getMyWorldPoint().getRegionID() == edgevilleTeleport.getBankingRegionIds()[0]);
    }
}