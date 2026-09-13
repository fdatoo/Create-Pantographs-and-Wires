package de.mrjulsen.paw.registry;

import de.mrjulsen.paw.PantographsAndWires;
import dev.architectury.registry.registries.DeferredRegister;
import dev.architectury.registry.registries.RegistrySupplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

public class ModSounds {

    public static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(PantographsAndWires.MOD_ID, Registries.SOUND_EVENT);

    /** The 686Hz departure tone, with its 1372Hz partner locked 2:1 beneath it. */
    public static final RegistrySupplier<SoundEvent> MP89_TONE_A = register("electric.mp89_tone_a");
    /** The 1186Hz tone that takes over as the departure tone fades. */
    public static final RegistrySupplier<SoundEvent> MP89_TONE_B = register("electric.mp89_tone_b");
    /** The rising low ridge, cut at 256Hz with its 2nd and 3rd harmonics baked in. */
    public static final RegistrySupplier<SoundEvent> MP89_RIDGE = register("electric.mp89_ridge");
    /** Broadband bed shaped against the reference recording. */
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
