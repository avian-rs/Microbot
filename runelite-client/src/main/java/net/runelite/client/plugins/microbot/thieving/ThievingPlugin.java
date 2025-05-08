package net.runelite.client.plugins.microbot.thieving;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.awt.*;

@PluginDescriptor(
        name = PluginDescriptor.Mocrosoft + "Thieving",
        description = "Microbot thieving plugin",
        tags = {"thieving", "microbot", "skilling"},
        enabledByDefault = false
)
@Slf4j
public class ThievingPlugin extends Plugin {

    @Inject
    private OverlayManager overlayManager;
    @Inject
    private ThievingOverlay thievingOverlay;

    @Inject
    ThievingScript thievingScript;


    @Override
    protected void startUp() throws AWTException {
        if (overlayManager != null) {
            overlayManager.add(thievingOverlay);
        }
        thievingScript.run();
    }

    protected void shutDown() {
        thievingScript.shutdown();
        overlayManager.remove(thievingOverlay);
    }
}
