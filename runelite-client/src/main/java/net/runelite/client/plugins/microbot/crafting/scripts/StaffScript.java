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
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.keyboard.Rs2Keyboard;
import net.runelite.client.plugins.microbot.util.math.Random;
import net.runelite.client.plugins.microbot.util.widget.Rs2Widget;

import java.util.concurrent.TimeUnit;

import static net.runelite.api.ItemID.BIG_BONES;
//
//class ProgressiveStaffmakingModel {
//    @Getter
//    @Setter
//    private Staffs itemToCraft;
//}

public class StaffScript extends Script {

    public static double version = 1.0;
//    ProgressiveStaffmakingModel model = new ProgressiveStaffmakingModel();

    private final int battleStaffID = 1391;
    Staffs itemToCraft;

    public void run(CraftingConfig config) {
        Microbot.enableAutoRunOn = false;
        Rs2Antiban.resetAntibanSettings();
        Rs2Antiban.antibanSetupTemplates.applyCraftingSetup();

//        if (config.staffType() == Staffs.PROGRESSIVE)
//            calculateItemToCraft();

        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            if (!super.run()) return;
            if (config.Afk() && Random.random(1, 100) == 2)
                sleep(1000, 60000);
            try {
                itemToCraft = config.staffType();

                if (Rs2Inventory.hasItem(battleStaffID)
                        && Rs2Inventory.hasItem(itemToCraft.getOrb())) {
                    craft(config);
                }
                if (!Rs2Inventory.hasItem(battleStaffID)
                        || !Rs2Inventory.hasItem(itemToCraft.getOrb())) {
                    bank(config);
                }

            } catch (Exception ex) {
                System.out.println(ex.getMessage());
            }
        }, 0, 600, TimeUnit.MILLISECONDS);
    }

    private void bank(CraftingConfig config) {
        Rs2Bank.openBank();
//        sleepUntilOnClientThread(() -> Rs2Bank.isOpen());
        sleepUntil(Rs2Bank::isOpen);

        Rs2Bank.depositAll(itemToCraft.getItemName());
        sleepUntil(() -> Rs2Inventory.contains(itemToCraft.getItemName()));

//        sleepUntilOnClientThread(() -> !Rs2Inventory.hasItem(itemToCraft.getItemName()));

        Rs2Bank.withdrawX(true, battleStaffID, 14);
        sleepUntil(() -> Rs2Inventory.contains(battleStaffID));


        verifyItemInBank(itemToCraft.getOrb());

        Rs2Bank.withdrawX(true, itemToCraft.getOrb(), 14);
        sleepUntil(() -> Rs2Inventory.contains(itemToCraft.getOrb()));

//        sleepUntilOnClientThread(() -> Rs2Inventory.hasItem(itemToCraft.getOrb()));
//        sleepGaussian();
        sleepGaussian(1500, 900);
        Rs2Bank.closeBank();
    }

    private void verifyItemInBank(String item) {
        if (Rs2Bank.isOpen() && !Rs2Bank.hasItem(item)) {
            Rs2Bank.closeBank();
            Microbot.status = "[Shutting down] - Reason: " + item + " not found in the bank.";
            Microbot.getNotifier().notify(Microbot.status);
            shutdown();
        }
    }

    private void craft(CraftingConfig config) {
        Rs2Inventory.combine(battleStaffID, config.staffType().getId());

        sleepUntilOnClientThread(() -> Rs2Widget.getWidget(17694734) != null);

        keyPress('1');

        sleepUntilOnClientThread(() -> Rs2Widget.getWidget(17694734) == null);

        sleepUntilOnClientThread(() -> !Rs2Inventory.hasItem(itemToCraft.getOrb()), 60000);
    }

    @Override
    public void shutdown() {
        super.shutdown();
    }
}
