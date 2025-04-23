package net.runelite.client.plugins.microbot.crafting.scripts;

import lombok.Getter;
import lombok.Setter;
import net.runelite.api.Skill;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.crafting.CraftingConfig;
import net.runelite.client.plugins.microbot.crafting.enums.Staffs;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.inventory.Rs2ItemModel;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.math.Rs2Random;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import java.awt.event.KeyEvent;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class StaffScript extends Script {

    public static double version = 1.0;

    private final int battleStaffID = 1391;
    Staffs itemToCraft;

    public void run(CraftingConfig config) {
        Microbot.enableAutoRunOn = false;
        Rs2Antiban.resetAntibanSettings();
        Rs2Antiban.antibanSetupTemplates.applyCraftingSetup();

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            if (!super.run()) return;
            if (config.Afk() && Rs2Random.dicePercentage(3.0)) {
                Microbot.status = "Taking a quick break...";
                sleepGaussian(2500, 1200); // Extended to simulate realistic long AFK pause
            }
            try {
                itemToCraft = config.staffType();

                if (Rs2Inventory.hasItem(battleStaffID)
                        && Rs2Inventory.hasItem(itemToCraft.getOrb())) {
                    craft(config);
                }
                maybeMoveCamera(); // Simulate camera adjustment before action
                if (!Rs2Inventory.hasItem(battleStaffID)
                        || !Rs2Inventory.hasItem(itemToCraft.getOrb())) {
                    bank(config);
                }

            } catch (Exception ex) {
                System.out.println(ex.getMessage());
            }
        }, 0, Rs2Random.randomGaussian(650, 100), TimeUnit.MILLISECONDS);
    }
    private void maybeMoveCamera() {
        if (Rs2Random.dicePercentage(4.0)) { // ~4% chance per loop
            Microbot.status = "Adjusting camera...";

            // Random yaw direction
            int targetAngle = Rs2Random.between(0, 360);
            int maxDeviation = Rs2Random.between(20, 60);
            Rs2Camera.setAngle(targetAngle, maxDeviation);

            // Optional pitch movement
            if (Rs2Random.dicePercentage(30.0)) {
                float targetPitch = Rs2Random.between(30, 96) / 100f; // 30% - 95% pitch
                Rs2Camera.adjustPitch(targetPitch);
            }

            // Optional zoom toggle (very rare)
            if (Rs2Random.dicePercentage(2.0)) {
                int newZoom = Rs2Random.between(100, 300);
                Rs2Camera.setZoom(newZoom);
            }

            sleepGaussian(900, 400); // more natural for a visual readjustment
        }
    }
    private void bank(CraftingConfig config) {
        Rs2Bank.openBank();
        sleepUntil(Rs2Bank::isOpen);

        Rs2Bank.depositAll(itemToCraft.getItemName());
        sleepUntil(() -> Rs2Inventory.contains(itemToCraft.getItemName()));

        Rs2Bank.withdrawX(true, battleStaffID, 14);
        sleepUntil(() -> Rs2Inventory.contains(battleStaffID));


        verifyItemInBank(itemToCraft.getOrb());

        Rs2Bank.withdrawX(true, itemToCraft.getOrb(), 14);
        sleepUntil(() -> Rs2Inventory.contains(itemToCraft.getOrb()));
        maybeHumanIdleBehavior();
        sleepGaussian(1600, 700); // Slower human deposit/pickup confirmation
        Rs2Bank.closeBank();
        maybeMoveCamera(); // Simulate camera adjustment before action
    }

    private void verifyItemInBank(String item) {
        if (Rs2Bank.isOpen() && !Rs2Bank.hasItem(item)) {
            sleepGaussian(1200, 500); // small bump to simulate real confusion
            Microbot.status = "[Shutting down] - Reason: " + item + " not found in the bank.";
            Microbot.getNotifier().notify(Microbot.status);
            sleepGaussian(800, 350); // Natural delay before bank closure
            Rs2Bank.closeBank();
            sleepGaussian(1400, 600); // Final human pause before shutdown // small bump to simulate real confusion
            shutdown();
        }
    }
    private void craft(CraftingConfig config) {
        maybeHumanIdleBehavior();
//        Rs2Inventory.combine(battleStaffID, config.staffType().getId());
        // decide which slots you want
        int staffSlot = 12;
        int orbSlot   = 16;

        // pull exactly those items
        Rs2ItemModel staffItem = Rs2Inventory.get(i ->
                i.getId()   == battleStaffID &&
                        (i.getSlot() == staffSlot || i.getSlot() == orbSlot)
        );
        Rs2ItemModel orbItem = Rs2Inventory.get(i ->
                i.getId()   == itemToCraft.getId() &&
                        (i.getSlot() == staffSlot || i.getSlot() == orbSlot)
        );

        if (staffItem != null && orbItem != null) {
            Rs2Inventory.combine(staffItem, orbItem);
        } else {
            Microbot.log("Could not find items in slots " + staffSlot + " & " + orbSlot);
        }
        Rs2Widget.sleepUntilHasWidgetText("How many do you wish to make?", 270, 5, false, 5000);
        Rs2Keyboard.keyPress(KeyEvent.VK_SPACE);
        sleepGaussian(2500, 500);

        sleepUntil(() -> !Rs2Inventory.hasItem(itemToCraft.getOrb()), 60000);
        maybeMoveCamera(); // Simulate camera adjustment before action
    }
    private void maybeHumanIdleBehavior() {
        if (Rs2Random.dicePercentage(3.0)) {
            Microbot.status = "Checking inventory...";
            Rs2Inventory.open();
            sleepGaussian(800, 300);
        }

        if (Rs2Random.dicePercentage(1.0)) {
            Microbot.status = "Oops, misclicked...";
            Rs2ItemModel orbItem = Rs2Inventory.get(itemToCraft.getOrb());
            if (orbItem != null) {
                Rs2Inventory.hover(orbItem);
                sleepGaussian(400, 150);
            }
        }
    }
    @Override
    public void shutdown() {
        super.shutdown();
    }
}
