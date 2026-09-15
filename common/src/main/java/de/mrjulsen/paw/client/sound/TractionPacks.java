package de.mrjulsen.paw.client.sound;

import java.util.HashMap;
import java.util.Map;
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
 * A pack named "bart" lives at assets/pantographsandwires/traction/bart, so a resource pack can replace it.
 */
@Environment(EnvType.CLIENT)
public final class TractionPacks {
    private static final Map<String, CompletableFuture<TractionPack>> PACKS = new HashMap<>();

    private TractionPacks() {}

    /** Starts loading a pack if it is not loaded or loading already. */
    public static synchronized CompletableFuture<TractionPack> load(String name) {
        return PACKS.computeIfAbsent(name, key -> {
            ResourceManager resources = Minecraft.getInstance().getResourceManager();
            String root = "traction/" + name + "/";
            return CompletableFuture.supplyAsync(() -> {
                try {
                    TractionPack pack = TractionPack.load(path -> {
                        Optional<Resource> resource = resources.getResource(new ResourceLocation(PantographsAndWires.MOD_ID, root + path));
                        return resource.isPresent() ? resource.get().open() : null;
                    });
                    PantographsAndWires.LOGGER.info("Loaded the {} traction sound pack: {} layers", name, pack.layers().size());
                    return pack;
                } catch (Exception e) {
                    PantographsAndWires.LOGGER.error("Could not load the {} traction sound pack", name, e);
                    throw new CompletionException(e);
                }
            }, Util.backgroundExecutor());
        });
    }

    /** The pack if it has finished loading successfully, otherwise null (and loading starts). */
    @Nullable
    public static TractionPack ifLoaded(String name) {
        CompletableFuture<TractionPack> future = load(name);
        return future.isDone() && !future.isCompletedExceptionally() ? future.join() : null;
    }

    /** Forgets loaded packs, so the next use reloads them from the current resources. */
    public static synchronized void invalidate() {
        PACKS.clear();
    }
}
