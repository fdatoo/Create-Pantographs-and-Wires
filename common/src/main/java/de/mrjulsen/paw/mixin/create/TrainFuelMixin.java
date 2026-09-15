package de.mrjulsen.paw.mixin.create;

import java.util.UUID;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.simibubi.create.content.trains.entity.Train;

import de.mrjulsen.paw.config.ModServerConfig;
import de.mrjulsen.paw.traction.ElectricSupply;
import de.mrjulsen.paw.traction.TractionDebug;

/**
 * Electric trains count as powered. Create gives a train its powered top speed and acceleration only
 * while fuelTicks is above zero, and tops it up by burning fuel items whenever it drives. A train drawing
 * power (ElectricSupply) is kept topped up instead, without taking anything from its inventories.
 *
 * require=0: if Create's internals move, trains go back to needing fuel rather than the game refusing to start.
 */
@Mixin(value = Train.class, remap = false)
public abstract class TrainFuelMixin {
    @Shadow
    public UUID id;

    @Shadow
    public int fuelTicks;

    @Inject(method = "burnFuel", at = @At("HEAD"), cancellable = true, require = 0)
    private void paw$electricSupply(CallbackInfo ci) {
        if (!ModServerConfig.ELECTRIC_TRAINS_POWERED.get() || !ElectricSupply.SERVER.supplied(id)) {
            return;
        }
        TractionDebug.once("electric-fuel", "server: electric trains are counting as fuelled");
        fuelTicks = Math.max(fuelTicks, ElectricSupply.FUEL_TICKS);
        ci.cancel();
    }
}
