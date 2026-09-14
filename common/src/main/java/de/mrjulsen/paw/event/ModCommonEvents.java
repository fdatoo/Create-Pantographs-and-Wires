package de.mrjulsen.paw.event;

import de.mrjulsen.paw.traction.TractionDebugCommands;
import de.mrjulsen.wires.WireNetwork;
import dev.architectury.event.events.common.LifecycleEvent;

public final class ModCommonEvents {

    private ModCommonEvents() {}
    
    public static void init() {
        TractionDebugCommands.register();

        LifecycleEvent.SERVER_LEVEL_LOAD.register((level) -> {
            level.getDataStorage().computeIfAbsent((nbt) -> WireNetwork.load(level, nbt), () -> WireNetwork.create(level), WireNetwork.getFileId(level.dimensionTypeId()));
        });

        LifecycleEvent.SERVER_STOPPED.register((server) -> {
            WireNetwork.clear();
        });
    }
}
