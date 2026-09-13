package de.mrjulsen.paw.mixin.create;

import java.util.UUID;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.simibubi.create.AllSoundEvents.SoundEntry;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.CarriageSounds;

import de.mrjulsen.paw.client.sound.TractionSoundManager;
import de.mrjulsen.paw.config.ModClientConfig;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Silences Create's steam chuffing and arrival hiss on trains that carry a
 * pantograph, so an electrically driven train doesn't sound like a locomotive.
 *
 * Keyed on the train having a pantograph reporting at all, not on it currently
 * drawing power: gating on live contact would let steam fade back in every time
 * the collector crossed an insulator gap, which reads worse than leaving it be.
 *
 * All three steam calls in CarriageSounds.tick share one playAt overload, so a
 * single redirect covers the chuffing schedules and the arrival release. The
 * redirect is require=0 on purpose -- if Create's internals move, this quietly
 * stops applying instead of refusing to launch the game over a cosmetic tweak.
 */
@Mixin(CarriageSounds.class)
public class CarriageSoundsMixin {

    @Shadow
    CarriageContraptionEntity entity;

    @Redirect(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lcom/simibubi/create/AllSoundEvents$SoundEntry;playAt(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/phys/Vec3;FFZ)V"
        ),
        require = 0
    )
    private void paw$skipSteamForElectricTrains(
        SoundEntry soundEntry,
        Level level,
        Vec3 position,
        float volume,
        float pitch,
        boolean fade
    ) {
        if (paw$isElectricTrain(level)) {
            return;
        }
        soundEntry.playAt(level, position, volume, pitch, fade);
    }

    private boolean paw$isElectricTrain(Level level) {
        if (!ModClientConfig.SILENCE_STEAM_ON_ELECTRIC_TRAINS.get()) {
            return false;
        }
        if (entity == null || level == null) {
            return false;
        }
        UUID trainId = entity.trainId;
        return trainId != null && TractionSoundManager.isElectric(trainId, level.getGameTime());
    }
}
