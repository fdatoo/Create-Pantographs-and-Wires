package de.mrjulsen.paw.registry;

import de.mrjulsen.paw.PantographsAndWires;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.level.Level;

/** Damage types defined as data under data/pantographsandwires/damage_type. */
public final class ModDamageTypes {
    public static final ResourceKey<DamageType> THIRD_RAIL = ResourceKey.create(Registries.DAMAGE_TYPE, new ResourceLocation(PantographsAndWires.MOD_ID, "third_rail"));

    private ModDamageTypes() {}

    /** Falls back to generic damage if the damage type's data failed to load, rather than failing every shock. */
    public static DamageSource thirdRail(Level level) {
        return level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolder(THIRD_RAIL)
            .map(DamageSource::new)
            .orElseGet(() -> level.damageSources().generic());
    }
}
