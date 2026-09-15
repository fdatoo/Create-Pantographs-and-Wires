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
import de.mrjulsen.paw.traction.TrainSettings;
import de.mrjulsen.paw.traction.TrainSettingsRegistry;
import de.mrjulsen.paw.config.ModClientConfig;
import de.mrjulsen.paw.traction.TractionDebug;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Silences Create's steam chuffing on trains that carry a current collector, so an electrically
 * driven train doesn't sound like a locomotive. The hiss when a train comes to a complete stop is left
 * alone: it reads as the brakes releasing air, which electric trains do too.
 *
 * Keyed on the train having a collector reporting at all, not on it currently drawing power: gating on
 * live contact would let steam fade back in every time the collector crossed an insulator gap, which
 * reads worse than leaving it be.
 *
 * CarriageSounds.tick plays steam three times through one playAt overload: two chuffing schedules while
 * moving, then the release as the train stops. Only the first two are redirected, by ordinal; the
 * release and the two sounds that go with it through Level.playLocalSound play as normal. The redirects
 * are require=0 on purpose -- if Create's internals move, this quietly stops applying instead of
 * refusing to launch the game over a cosmetic tweak.
 */
@Mixin(CarriageSounds.class)
public class CarriageSoundsMixin {

    @Shadow
    CarriageContraptionEntity entity;

    @Redirect(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lcom/simibubi/create/AllSoundEvents$SoundEntry;playAt(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/phys/Vec3;FFZ)V",
            ordinal = 0
        ),
        require = 0
    )
    private void paw$skipChuffingForElectricTrains(
        SoundEntry soundEntry,
        Level level,
        Vec3 position,
        float volume,
        float pitch,
        boolean fade
    ) {
        TractionDebug.once("chuffing-hook", "client: Create steam chuffing hook is active");
        if (paw$isElectricTrain(level)) {
            paw$logSilenced(level);
            return;
        }
        soundEntry.playAt(level, position, volume, pitch, fade);
    }

    /** The release as the train stops: always played, and logged so its timing can be checked. */
    @Redirect(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lcom/simibubi/create/AllSoundEvents$SoundEntry;playAt(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/phys/Vec3;FFZ)V",
            ordinal = 2
        ),
        require = 0
    )
    private void paw$playStopHiss(
        SoundEntry soundEntry,
        Level level,
        Vec3 position,
        float volume,
        float pitch,
        boolean fade
    ) {
        if (TractionDebug.client() && entity != null) {
            TractionDebug.info("client: train {} stopped, playing the release hiss (volume {})",
                TractionDebug.shortId(entity.trainId), String.format("%.2f", volume));
        }
        soundEntry.playAt(level, position, volume, pitch, fade);
    }

    private void paw$logSilenced(Level level) {
        if (TractionDebug.client() && entity != null
            && TractionDebug.every("chuffing-" + entity.trainId, level.getGameTime(), 200)) {
            TractionDebug.info("client: train {} steam chuffing silenced (electric train)", TractionDebug.shortId(entity.trainId));
        }
    }

    @Redirect(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lcom/simibubi/create/AllSoundEvents$SoundEntry;playAt(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/phys/Vec3;FFZ)V",
            ordinal = 1
        ),
        require = 0
    )
    private void paw$skipHeavyChuffingForElectricTrains(
        SoundEntry soundEntry,
        Level level,
        Vec3 position,
        float volume,
        float pitch,
        boolean fade
    ) {
        if (paw$isElectricTrain(level)) {
            paw$logSilenced(level);
            return;
        }
        soundEntry.playAt(level, position, volume, pitch, fade);
    }

    /** The train's Traction Controller decides; set to default, or without one, the player's setting for electric trains does. */
    private boolean paw$isElectricTrain(Level level) {
        if (entity == null || level == null || entity.trainId == null) {
            return false;
        }
        UUID trainId = entity.trainId;
        TrainSettings.SteamSound steam = TrainSettingsRegistry.CLIENT.of(trainId, level.getGameTime()).steamSound();
        if (steam != TrainSettings.SteamSound.DEFAULT) {
            return steam == TrainSettings.SteamSound.SILENCED;
        }
        return ModClientConfig.SILENCE_STEAM_ON_ELECTRIC_TRAINS.get() && TractionSoundManager.isElectric(trainId, level.getGameTime());
    }
}
