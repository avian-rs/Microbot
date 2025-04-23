package net.runelite.client.plugins.microbot.crafting.scripts;

import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.crafting.CraftingConfig;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.bank.Rs2Bank;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.math.Random;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import java.awt.event.KeyEvent;
import java.util.concurrent.TimeUnit;

public class DefaultScript extends Script {
    public boolean run(CraftingConfig config) {
        Microbot.enableAutoRunOn = false;
        Rs2Antiban.resetAntibanSettings();
        Rs2Antiban.antibanSetupTemplates.applyCraftingSetup();

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn()) return;
                if (!super.run()) return;
                if (config.Afk() && Random.random(1, 100) == 2)
                    sleep(1000, 60000);
                final int leather = 1745; //green dragon leather
                final int craftedItem = 1135; // green d'hide body
                final int costume_needle = 29920;
                if (Microbot.isGainingExp) return;
                if (!Rs2Inventory.hasItem(craftedItem)) {
                    if (!Rs2Inventory.isFull()) {
                        Rs2Bank.openBank();
                        sleepUntil(Rs2Bank::isOpen, 600);
                        Rs2Bank.withdrawItem(true, costume_needle);
                        sleepUntil(() -> Rs2Inventory.hasItem(costume_needle), 600);
                        Rs2Bank.withdrawAll(leather);
                        sleepUntil(() -> Rs2Inventory.hasItem(leather), 600);

                    } else if (Rs2Inventory.hasItem(leather)) {
                        Rs2Bank.closeBank();
                        Rs2Inventory.combine(costume_needle, leather);
                        Rs2Widget.sleepUntilHasWidgetText("How many do you wish to make?", 270, 5, false, 5000);
                        Rs2Keyboard.keyPress(KeyEvent.VK_SPACE);
                        sleepGaussian(2500, 500);
                    } else {
                        shutDown();
                    }
                } else {
                    Rs2Bank.openBank();
                    sleepUntil(Rs2Bank::isOpen, 600);
                    Rs2Bank.depositAll(craftedItem);
                    sleepGaussian(400,100);
                    Rs2Bank.withdrawAll(leather);
                    sleepGaussian(400,100);
                    Rs2Bank.closeBank();
                    sleepUntil(() -> !Rs2Bank.isOpen(), 600);

                }
            } catch (Exception ex) {
                System.out.println(ex.getMessage());
                //Microbot.getNotifier().notify("Script failure");
            }

        }, 0, 600, TimeUnit.MILLISECONDS);
        return true;
    }

    public void shutDown() {
        super.shutdown();
    }
}
