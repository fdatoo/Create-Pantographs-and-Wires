package de.mrjulsen.paw.traction;

import java.util.Locale;

import javax.annotation.Nullable;

import de.mrjulsen.mcdragonlib.core.ITranslatableEnum;
import de.mrjulsen.paw.config.TractionSoundProfile;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

/**
 * One train's settings, kept in a Traction Controller block on the train. A train without a controller
 * uses DEFAULT, which leaves each choice to the player's own client settings.
 */
public record TrainSettings(SoundPack soundPack, SpeedUnit speedUnit, SteamSound steamSound) {
    /** Where the settings sit in the controller's block entity data. */
    public static final String NBT_KEY = "TrainSettings";
    public static final TrainSettings DEFAULT = new TrainSettings(SoundPack.DEFAULT, SpeedUnit.METRES_PER_SECOND, SteamSound.DEFAULT);

    public enum SoundPack implements ITranslatableEnum {
        /** The player's own traction sound setting. */
        DEFAULT("default", null),
        BART("bart", TractionSoundProfile.BART),
        WMATA("wmata", TractionSoundProfile.WMATA),
        MP89("mp89", TractionSoundProfile.MP89),
        /** No traction sound for this train. */
        NONE("none", null);

        private final String id;
        @Nullable
        private final TractionSoundProfile profile;

        SoundPack(String id, @Nullable TractionSoundProfile profile) {
            this.id = id;
            this.profile = profile;
        }

        /** The profile this choice plays, or null for DEFAULT and NONE. */
        @Nullable
        public TractionSoundProfile profile() {
            return profile;
        }

        @Override
        public String getEnumName() {
            return "train_sound_pack";
        }

        @Override
        public String getEnumValueName() {
            return id;
        }
    }

    public enum SpeedUnit implements ITranslatableEnum {
        METRES_PER_SECOND("mps", 1.0),
        KILOMETRES_PER_HOUR("kmh", 3.6),
        MILES_PER_HOUR("mph", 3600 / 1609.344);

        private final String id;
        private final double perMetrePerSecond;

        SpeedUnit(String id, double perMetrePerSecond) {
            this.id = id;
            this.perMetrePerSecond = perMetrePerSecond;
        }

        public double fromMetresPerSecond(double speedMps) {
            return speedMps * perMetrePerSecond;
        }

        @Override
        public String getEnumName() {
            return "train_speed_unit";
        }

        @Override
        public String getEnumValueName() {
            return id;
        }
    }

    public enum SteamSound implements ITranslatableEnum {
        /** The player's own setting for steam on electric trains. */
        DEFAULT("default"),
        SILENCED("silenced"),
        PLAYED("played");

        private final String id;

        SteamSound(String id) {
            this.id = id;
        }

        @Override
        public String getEnumName() {
            return "train_steam_sound";
        }

        @Override
        public String getEnumValueName() {
            return id;
        }
    }

    public TrainSettings withSoundPack(SoundPack value) {
        return new TrainSettings(value, speedUnit, steamSound);
    }

    public TrainSettings withSpeedUnit(SpeedUnit value) {
        return new TrainSettings(soundPack, value, steamSound);
    }

    public TrainSettings withSteamSound(SteamSound value) {
        return new TrainSettings(soundPack, speedUnit, value);
    }

    public CompoundTag write() {
        CompoundTag tag = new CompoundTag();
        tag.putString("SoundPack", soundPack.getEnumValueName());
        tag.putString("SpeedUnit", speedUnit.getEnumValueName());
        tag.putString("SteamSound", steamSound.getEnumValueName());
        return tag;
    }

    /** Reads settings; missing or unknown values fall back to their defaults. */
    public static TrainSettings read(CompoundTag tag) {
        return new TrainSettings(
            byId(SoundPack.values(), tag.getString("SoundPack"), DEFAULT.soundPack),
            byId(SpeedUnit.values(), tag.getString("SpeedUnit"), DEFAULT.speedUnit),
            byId(SteamSound.values(), tag.getString("SteamSound"), DEFAULT.steamSound)
        );
    }

    /** The settings in a controller's block entity data, or DEFAULT when it has none. */
    public static TrainSettings from(@Nullable CompoundTag blockEntityData) {
        return blockEntityData != null && blockEntityData.contains(NBT_KEY) ? read(blockEntityData.getCompound(NBT_KEY)) : DEFAULT;
    }

    public void writeTo(FriendlyByteBuf buf) {
        buf.writeEnum(soundPack);
        buf.writeEnum(speedUnit);
        buf.writeEnum(steamSound);
    }

    public static TrainSettings readFrom(FriendlyByteBuf buf) {
        return new TrainSettings(buf.readEnum(SoundPack.class), buf.readEnum(SpeedUnit.class), buf.readEnum(SteamSound.class));
    }

    private static <E extends Enum<E> & ITranslatableEnum> E byId(E[] values, String id, E fallback) {
        String wanted = id.toLowerCase(Locale.ROOT);
        for (E value : values) {
            if (value.getEnumValueName().equals(wanted)) {
                return value;
            }
        }
        return fallback;
    }
}
