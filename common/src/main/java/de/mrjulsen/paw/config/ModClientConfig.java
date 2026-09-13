package de.mrjulsen.paw.config;

import de.mrjulsen.paw.PantographsAndWires;
import net.minecraftforge.common.ForgeConfigSpec;

public class ModClientConfig {
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.ConfigValue<Boolean> DEBUG_ORIGINAL_HITBOX;
    public static final ForgeConfigSpec.ConfigValue<Boolean> SILENCE_STEAM_ON_ELECTRIC_TRAINS;
    public static final ForgeConfigSpec.ConfigValue<Double> TRACTION_VOLUME;
    public static final ForgeConfigSpec.ConfigValue<String> TRACTION_PROFILE;

    static {
        BUILDER.push(PantographsAndWires.MOD_ID + "_client_config");

        DEBUG_ORIGINAL_HITBOX = BUILDER.comment(new String[] {"Shows the real AABB hitbox of rotated blocks and not a rotated version of the outline.", "Default: OFF"})
            .define("debug.show_original_block_hitbox", false);

        SILENCE_STEAM_ON_ELECTRIC_TRAINS = BUILDER.comment(new String[] {"Silences Create's steam chuffing and arrival hiss on trains carrying a pantograph.", "Default: ON"})
            .define("sound.silence_steam_on_electric_trains", true);

        TRACTION_VOLUME = BUILDER.comment(new String[] {"Loudness of the electric traction sound. 0 disables it entirely.", "Default: 0.5"})
            .defineInRange("sound.traction_volume", 0.5D, 0.0D, 1.0D);

        TRACTION_PROFILE = BUILDER.comment(new String[] {
                "Which traction sound to use.",
                "inverter: gear-stepped switching frequency with PWM sidebands, derived from how IGBT drives behave.",
                "mp89: components measured from an MP 89 recording, with its rising ridge mapped onto train speed.",
                "Default: mp89"
            })
            .defineInList("sound.traction_profile", "mp89", java.util.List.of("inverter", "mp89"));

        BUILDER.pop();
        SPEC = BUILDER.build();
    }
    
}
