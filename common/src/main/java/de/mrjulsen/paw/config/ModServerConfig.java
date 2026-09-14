package de.mrjulsen.paw.config;

import de.mrjulsen.paw.PantographsAndWires;
import net.minecraftforge.common.ForgeConfigSpec;

public class ModServerConfig {
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    private static final String WARN = "If in doubt, leave unchanged.";
    
    public static final ForgeConfigSpec.ConfigValue<Integer> CATENARY_WIRE_MAX_LENGTH;
    public static final ForgeConfigSpec.ConfigValue<Integer> ENERGY_WIRE_MAX_LENGTH;
    public static final ForgeConfigSpec.ConfigValue<Double> WIRE_COLLISION_TRACER_STEP_SIZE;
    public static final ForgeConfigSpec.ConfigValue<Boolean> BLOCKS_BREAK_WIRES;
    public static final ForgeConfigSpec.ConfigValue<Boolean> WIRE_ENTITY_DAMAGE;
    public static final ForgeConfigSpec.ConfigValue<Integer> THIRD_RAIL_MAX_LENGTH;
    public static final ForgeConfigSpec.ConfigValue<Boolean> THIRD_RAIL_ENTITY_DAMAGE;
    public static final ForgeConfigSpec.ConfigValue<Double> THIRD_RAIL_DAMAGE_AMOUNT;
    public static final ForgeConfigSpec.ConfigValue<Boolean> TRACTION_DEBUG;

    static {
        BUILDER.push(PantographsAndWires.MOD_ID + "_common_config");

        CATENARY_WIRE_MAX_LENGTH = BUILDER.comment(new String[] {"[in Blocks]", "The maximum length of the catenary wire between two masts.", "Default: 48"})
            .defineInRange("wires.catenary_max_length", 50, 16, 64);
        ENERGY_WIRE_MAX_LENGTH = BUILDER.comment(new String[] {"[in Blocks]", "The maximum length of the energy wire between two masts.", "Default: 48"})
            .defineInRange("wires.energy_max_length", 50, 16, 64);
        BLOCKS_BREAK_WIRES = BUILDER.comment(new String[] {"Whether blocks placed in wires can destroy them.", "Default: true"})
            .define("wires.block_destroy_wires", true);
        WIRE_ENTITY_DAMAGE = BUILDER.comment(new String[] {"Whether powered wires should cause damage to entities touching them.", "Default: true"})
            .define("wires.wire_entity_damage", true);

        WIRE_COLLISION_TRACER_STEP_SIZE = BUILDER.comment(new String[] {"[in Block Pixels]", "Which step size is used in the collision calculation of the cables. Lower values increase precision but require more computing power. Higher values are inaccurate but require less more performance.", WARN, "Default: 1"})
            .defineInRange("wires.calculation.collision_tracer_step_size", 1, 0.1, 4);

        THIRD_RAIL_MAX_LENGTH = BUILDER.comment(new String[] {"[in Blocks]", "The maximum distance between the two ends of a placed third rail curve.", "Default: 32"})
            .defineInRange("third_rail.max_length", 32, 8, 128);
        THIRD_RAIL_ENTITY_DAMAGE = BUILDER.comment(new String[] {"Whether touching a third rail's conductor hurts players and mobs.", "Default: true"})
            .define("third_rail.entity_damage", true);
        THIRD_RAIL_DAMAGE_AMOUNT = BUILDER.comment(new String[] {"[in half hearts]", "Damage dealt each time an entity touches a third rail's conductor.", "Default: 8"})
            .defineInRange("third_rail.damage_amount", 8.0D, 0.0D, 1000.0D);

        TRACTION_DEBUG = BUILDER.comment(new String[] {"Logs traction decisions on the server: speed reports sent, drivers holding a direction, and slopes Create treats as turns. Lines start with [PAW traction]. Also switchable with /paw_traction_debug server on|off.", "Default: false"})
            .define("debug.traction_log", false);

        BUILDER.pop();
        SPEC = BUILDER.build();
    }
    
}
