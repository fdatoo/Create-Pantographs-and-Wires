package de.mrjulsen.paw.client.debug;

import de.mrjulsen.paw.client.sound.TractionSoundManager;
import de.mrjulsen.paw.traction.CatenaryContactDetector;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Live pantograph state on the F3 screen, so a pantograph that refuses to raise
 * can be traced to the step that actually failed rather than guessed at:
 * whether it was ever switched on, whether the wire sweep found any blocks,
 * whether those blocks held wire collisions, and whether contact resolved.
 */
@Environment(EnvType.CLIENT)
public final class TractionDebugOverlay {

    // Stop drawing shortly after the last pantograph stops reporting, so the line
    // doesn't linger on screen for players nowhere near a train.
    private static final long VISIBLE_FOR_TICKS = 60;

    private static volatile boolean expandable;
    private static volatile boolean touching;
    private static volatile double wireHeight;
    private static volatile double speed;
    private static volatile long lastReportTick = Long.MIN_VALUE;

    private TractionDebugOverlay() {}

    public static void record(
        boolean pantographExpandable,
        boolean pantographTouching,
        double contactHeight,
        double vehicleSpeed,
        long gameTime
    ) {
        expandable = pantographExpandable;
        touching = pantographTouching;
        wireHeight = contactHeight;
        speed = vehicleSpeed;
        lastReportTick = gameTime;
    }

    /** The F3 line, or null when no pantograph has reported recently. */
    public static String line(long gameTime) {
        if (gameTime - lastReportTick > VISIBLE_FOR_TICKS) {
            return null;
        }
        return String.format(
            "[PaW] raised=%s touching=%s height=%.2f speed=%.3f | sweep=%d blocks, %d collisions | ridge=%.0fHz frac=%.2f voices=%d",
            expandable,
            touching,
            wireHeight,
            speed,
            CatenaryContactDetector.lastSweepBlocks,
            CatenaryContactDetector.lastCollisionCount,
            TractionSoundManager.ridgeFrequency(speed),
            TractionSoundManager.speedFraction(speed),
            TractionSoundManager.activeVoiceCount()
        );
    }
}
