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

    /** WMATA 6000-series upper cluster line at 2440Hz, its two drifting satellites baked in. */
    public static final RegistrySupplier<SoundEvent> WMATA_UPPER_LINE = register("electric.wmata_upper_line");
    /** Narrowband noise centred on the upper cluster, for where it turns diffuse. */
    public static final RegistrySupplier<SoundEvent> WMATA_UPPER_DIFFUSE = register("electric.wmata_upper_diffuse");
    /** The independent low sweep, cut at 700Hz. */
    public static final RegistrySupplier<SoundEvent> WMATA_LOW = register("electric.wmata_low");
    /** The brief upper event, cut at 2890Hz. */
    public static final RegistrySupplier<SoundEvent> WMATA_BRIEF = register("electric.wmata_brief");
    /** The later mid ridge, cut at 1350Hz. */
    public static final RegistrySupplier<SoundEvent> WMATA_MID = register("electric.wmata_mid");
    /** The rising noise bed: 400/650/900Hz body bands over broadband rolling noise. */
    public static final RegistrySupplier<SoundEvent> WMATA_NOISE = register("electric.wmata_noise");

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
