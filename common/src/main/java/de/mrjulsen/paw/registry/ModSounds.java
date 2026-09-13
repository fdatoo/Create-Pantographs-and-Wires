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
    /** The 2fe motor order; driven by the same electrical frequency that spreads the sidebands. */
    public static final RegistrySupplier<SoundEvent> TRACTION_BASS = register("electric.traction_bass");
    /** Motor-body resonances and broadband vibration; pitch stays fixed as the tonal lines move past. */
    public static final RegistrySupplier<SoundEvent> TRACTION_TEXTURE = register("electric.traction_texture");

    /** MP 89 profile: the 686Hz departure tone with its 1372Hz partner locked 2:1 beneath it. */
    public static final RegistrySupplier<SoundEvent> MP89_TONE_A = register("electric.mp89_tone_a");
    /** MP 89 profile: the 1186Hz tone that takes over as the departure tone fades. */
    public static final RegistrySupplier<SoundEvent> MP89_TONE_B = register("electric.mp89_tone_b");
    /** MP 89 profile: the rising low ridge, cut at 256Hz with its 2nd and 3rd harmonics. */
    public static final RegistrySupplier<SoundEvent> MP89_RIDGE = register("electric.mp89_ridge");
    /** MP 89 profile: broadband bed shaped against the reference recording. */
    public static final RegistrySupplier<SoundEvent> MP89_TEXTURE = register("electric.mp89_texture");

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
