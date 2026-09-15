package de.mrjulsen.paw.traction;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.mrjulsen.paw.config.TractionSoundProfile;
import net.minecraft.nbt.CompoundTag;

class TrainSettingsTest {
    private static final UUID TRAIN = UUID.randomUUID();

    @Test
    void settingsSurviveBeingSaved() {
        TrainSettings settings = new TrainSettings(TrainSettings.SoundPack.WMATA, TrainSettings.SpeedUnit.KILOMETRES_PER_HOUR, TrainSettings.SteamSound.PLAYED);
        CompoundTag blockEntityData = new CompoundTag();
        blockEntityData.put(TrainSettings.NBT_KEY, settings.write());
        assertEquals(settings, TrainSettings.from(blockEntityData));
    }

    @Test
    void missingOrUnknownValuesFallBackToDefaults() {
        assertEquals(TrainSettings.DEFAULT, TrainSettings.from(null));
        assertEquals(TrainSettings.DEFAULT, TrainSettings.from(new CompoundTag()));
        CompoundTag tag = new CompoundTag();
        tag.putString("SoundPack", "a pack from the future");
        tag.putString("SpeedUnit", "mph");
        CompoundTag data = new CompoundTag();
        data.put(TrainSettings.NBT_KEY, tag);
        TrainSettings read = TrainSettings.from(data);
        assertEquals(TrainSettings.SoundPack.DEFAULT, read.soundPack());
        assertEquals(TrainSettings.SpeedUnit.MILES_PER_HOUR, read.speedUnit());
        assertEquals(TrainSettings.SteamSound.DEFAULT, read.steamSound());
    }

    @Test
    void unitsConvertFromMetresPerSecond() {
        assertEquals(144, TrainSettings.SpeedUnit.KILOMETRES_PER_HOUR.fromMetresPerSecond(40), 1e-9);
        assertEquals(89.477, TrainSettings.SpeedUnit.MILES_PER_HOUR.fromMetresPerSecond(40), 1e-3);
        assertEquals(14, TrainSettings.SpeedUnit.METRES_PER_SECOND.fromMetresPerSecond(14), 1e-9);
    }

    @Test
    void packsMapToProfiles() {
        assertEquals(TractionSoundProfile.BART, TrainSettings.SoundPack.BART.profile());
        assertEquals(null, TrainSettings.SoundPack.DEFAULT.profile());
        assertEquals(null, TrainSettings.SoundPack.NONE.profile());
    }

    @Test
    void theMostRecentlyChosenSettingsWinWhicheverCarriageHoldsThem() {
        TrainSettingsRegistry registry = new TrainSettingsRegistry();
        TrainSettings older = TrainSettings.DEFAULT.withSoundPack(TrainSettings.SoundPack.BART);
        TrainSettings newer = TrainSettings.DEFAULT.withSoundPack(TrainSettings.SoundPack.WMATA);
        registry.report(TRAIN, 0, 5, new TrainSettingsRegistry.Stamped(older, 1_000), 100);
        registry.report(TRAIN, 2, 0, new TrainSettingsRegistry.Stamped(newer, 2_000), 100);
        assertEquals(newer, registry.of(TRAIN, 100));
        assertEquals(2_000, registry.find(TRAIN, 100).orElseThrow().changed());
    }

    @Test
    void equalTimesGoToTheLowestCarriageAndStaleReportsExpire() {
        TrainSettingsRegistry registry = new TrainSettingsRegistry();
        TrainSettings rear = TrainSettings.DEFAULT.withSoundPack(TrainSettings.SoundPack.WMATA);
        TrainSettings front = TrainSettings.DEFAULT.withSoundPack(TrainSettings.SoundPack.BART);
        registry.report(TRAIN, 2, 0, new TrainSettingsRegistry.Stamped(rear, 0), 100);
        registry.report(TRAIN, 0, 5, new TrainSettingsRegistry.Stamped(front, 0), 100);
        assertEquals(front, registry.of(TRAIN, 100));

        registry.report(TRAIN, 2, 0, new TrainSettingsRegistry.Stamped(rear, 0), 100 + TrainSettingsRegistry.STALE_TICKS + 1);
        assertEquals(rear, registry.of(TRAIN, 100 + TrainSettingsRegistry.STALE_TICKS + 1), "the front controller went quiet");
        assertEquals(TrainSettings.DEFAULT, registry.of(UUID.randomUUID(), 100));
    }

    @Test
    void theChangeTimeIsSavedWithTheSettings() {
        CompoundTag data = new CompoundTag();
        assertEquals(0, TrainSettings.changedAt(data));
        data.put(TrainSettings.NBT_KEY, TrainSettings.DEFAULT.withSpeedUnit(TrainSettings.SpeedUnit.MILES_PER_HOUR).write(123_456L));
        assertEquals(123_456L, TrainSettings.changedAt(data));
        assertEquals(TrainSettings.SpeedUnit.MILES_PER_HOUR, TrainSettings.from(data).speedUnit());
    }
}
