package de.mrjulsen.paw.registry;

import de.mrjulsen.paw.PantographsAndWires;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

public class ModSounds {

    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(PantographsAndWires.MOD_ID, Registries.SOUND_EVENT);

    /** Switching frequency and its harmonics; pitched as a block, since real harmonics track fc. */
    public static final RegistrySupplier<SoundEvent> TRACTION_CARRIER = register("electric.traction_carrier");
    /** A bare reference tone, played once per PWM sideband at its own independently computed pitch. */
    public static final RegistrySupplier<SoundEvent> TRACTION_SIDEBAND = register("electric.traction_sideband");
    /** Motor-body resonances and low-end vibration; pitch stays fixed as the tonal lines move past it. */
    public static final RegistrySupplier<SoundEvent> TRACTION_TEXTURE = register("electric.traction_texture");

    private static RegistrySupplier<SoundEvent> register(String name) {
        return SOUNDS.register(
            name,
            () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(PantographsAndWires.MOD_ID, name))
        );
    }

    public static void init() {
        SOUNDS.register();
    }
}
