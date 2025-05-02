package net.runelite.client.plugins.microbot.thieving.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ThievingNpc {
    NONE("None", 0),
    MASTER_FARMER("master farmer", 38),
    PALADIN("Paladin", 70),
    ELVES("Elves", 85);

    private final String name;
    private final int thievingLevel;

    @Override
    public String toString() {
        return name;
    }

}
