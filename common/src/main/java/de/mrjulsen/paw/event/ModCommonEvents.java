package de.mrjulsen.paw.event;

import de.mrjulsen.paw.traction.ElectricSupply;
import de.mrjulsen.paw.traction.ThirdRailCommands;
import de.mrjulsen.paw.traction.TractionDebugCommands;
import de.mrjulsen.wires.WireNetwork;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.TickEvent;

public final class ModCommonEvents {

    private ModCommonEvents() {}
    
    public static void init() {
        TractionDebugCommands.register();
        ThirdRailCommands.register();

        LifecycleEvent.SERVER_LEVEL_LOAD.register((level) -> {
            level.getDataStorage().computeIfAbsent((nbt) -> WireNetwork.load(level, nbt), () -> WireNetwork.create(level), WireNetwork.getFileId(level.dimensionTypeId()));
        });

        TickEvent.SERVER_POST.register((server) -> ElectricSupply.SERVER.tick());

        LifecycleEvent.SERVER_STOPPED.register((server) -> {
            WireNetwork.clear();
            ElectricSupply.SERVER.clear();
        });
    }
}
