package de.mrjulsen.paw.client.sound;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import javax.annotation.Nullable;

import de.mrjulsen.paw.PantographsAndWires;
import de.mrjulsen.paw.traction.pack.TractionPack;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

/**
 * Loads traction sound packs from the game's resources, in the background, once per resource reload.
 * The WMATA pack lives at assets/pantographsandwires/traction/wmata, so a resource pack can replace it.
 */
@Environment(EnvType.CLIENT)
public final class TractionPacks {
    public static final String WMATA_ROOT = "traction/wmata/";

    @Nullable
    private static CompletableFuture<TractionPack> wmata;

    private TractionPacks() {}

    /** Starts loading the WMATA pack if it is not loaded or loading already. */
    public static synchronized CompletableFuture<TractionPack> wmata() {
        if (wmata == null) {
            ResourceManager resources = Minecraft.getInstance().getResourceManager();
            wmata = CompletableFuture.supplyAsync(() -> {
                try {
                    TractionPack pack = TractionPack.load(path -> {
                        Optional<Resource> resource = resources.getResource(new ResourceLocation(PantographsAndWires.MOD_ID, WMATA_ROOT + path));
                        return resource.isPresent() ? resource.get().open() : null;
                    });
                    PantographsAndWires.LOGGER.info("Loaded the WMATA traction sound pack: {} layers", pack.layers().size());
                    return pack;
                } catch (Exception e) {
                    PantographsAndWires.LOGGER.error("Could not load the WMATA traction sound pack", e);
                    throw new CompletionException(e);
                }
            }, Util.backgroundExecutor());
        }
        return wmata;
    }

    /** The WMATA pack if it has finished loading successfully, otherwise null. */
    @Nullable
    public static TractionPack wmataIfLoaded() {
        CompletableFuture<TractionPack> future = wmata();
        return future.isDone() && !future.isCompletedExceptionally() ? future.join() : null;
    }

    /** Forgets loaded packs, so the next use reloads them from the current resources. */
    public static synchronized void invalidate() {
        wmata = null;
    }
}
