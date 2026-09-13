package de.mrjulsen.paw.registry;

import de.mrjulsen.paw.PantographsAndWires;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

public class ModSounds {

    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(PantographsAndWires.MOD_ID, Registries.SOUND_EVENT);

    public static final RegistrySupplier<SoundEvent> ELECTRIC_TRACTION_HUM = SOUNDS.register(
        "electric.traction_hum",
        () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(PantographsAndWires.MOD_ID, "electric.traction_hum"))
    );

    public static void init() {
        SOUNDS.register();
    }
}
