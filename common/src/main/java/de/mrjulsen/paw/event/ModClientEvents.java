package de.mrjulsen.paw.event;

import de.mrjulsen.paw.PantographsAndWires;
import de.mrjulsen.paw.client.debug.TractionDebugOverlay;
import de.mrjulsen.paw.client.sound.TractionSoundManager;
import de.mrjulsen.paw.compat.sodium.IncompatabilityScreen;
import de.mrjulsen.paw.compat.sodium.SodiumCompatEvent;
import de.mrjulsen.wires.item.WireBaseItem;
import de.mrjulsen.wires.render.WireRenderer;
import de.mrjulsen.wires.util.ClientUtils;
import de.mrjulsen.wires.WireClientNetwork;
import de.mrjulsen.wires.WireNetwork;
import dev.architectury.event.CompoundEventResult;
import dev.architectury.event.events.client.ClientGuiEvent;
import dev.architectury.event.events.client.ClientLifecycleEvent;
import dev.architectury.event.events.client.ClientPlayerEvent;
import dev.architectury.event.events.client.ClientTickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.HitResult;

public final class ModClientEvents {

    public static final ResourceLocation WIRE_TEXTURE = new ResourceLocation(PantographsAndWires.MOD_ID, "textures/block/wire.png");

    private ModClientEvents() {}

    public static void init() {

        ClientGuiEvent.DEBUG_TEXT_LEFT.register((lines) -> {
            if (Minecraft.getInstance().level != null) {
                String traction = TractionDebugOverlay.line(Minecraft.getInstance().level.getGameTime());
                if (traction != null) {
                    lines.add(traction);
                }
            }
            // Deliberately outside the useAdvancedLogging gate below. That gate is
            // Platform.isDevelopmentEnvironment(), so the two lines after it have never once
            // appeared on a real client, which is exactly where wires go missing. This line
            // answers the question that separates "the client never received the wire" from
            // "the client has it and still will not draw it".
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null && mc.player != null) {
                SectionPos here = SectionPos.of(mc.player.blockPosition());
                WireClientNetwork net = WireClientNetwork.get(mc.level);
                lines.add(String.format(
                    "[PaW] sec %d,%d,%d known=%s batches=%d | %s",
                    here.x(), here.y(), here.z(),
                    net.hasConnectionsInSection(here),
                    net.connectionsInSection(here).size(),
                    net.debug_text()
                ));

                // Wires hang far above head height, so the section the player stands in is
                // usually two or three cubes below the one holding the wire. Reporting only
                // that cube would read known=false for a perfectly healthy wire. Sweep the
                // surrounding column instead so this can be read from the foot of a pole.
                // Report collision alongside render geometry. Both are created in the same
                // method from the same WireBatch, so the pair separates the two failures that
                // look identical in game: c>0 with r=0 means the wire reached the client and
                // its geometry was lost or filed under some other section, while both zero
                // means the client was never told about the wire at all.
                StringBuilder found = new StringBuilder();
                int sections = 0;
                for (int dy = -2; dy <= 5 && sections < 8; dy++) {
                    for (int dx = -1; dx <= 1 && sections < 8; dx++) {
                        for (int dz = -1; dz <= 1 && sections < 8; dz++) {
                            SectionPos at = SectionPos.of(here.x() + dx, here.y() + dy, here.z() + dz);
                            int batches = net.connectionsInSection(at).size();
                            int collisions = net.getCollisionsTroughSection(at).size();
                            if (batches > 0 || collisions > 0) {
                                found.append(String.format(
                                    " %d,%d,%d(r%d c%d)", at.x(), at.y(), at.z(), batches, collisions
                                ));
                                sections++;
                            }
                        }
                    }
                }
                lines.add("[PaW] wires nearby:" + (found.length() == 0 ? " none" : found.toString()));
            }

            if (!PantographsAndWires.useAdvancedLogging()) {
                return;
            }
            lines.add(WireNetwork.get(ClientUtils.level()).debug_text());
            lines.add(WireClientNetwork.get(ClientUtils.level()).debug_text());
        });

        ClientLifecycleEvent.CLIENT_STARTED.register((mc) -> {        
            if (Minecraft.getInstance() != null) {            
                ReloadableResourceManager reloadableManager = (ReloadableResourceManager)Minecraft.getInstance().getResourceManager();
                reloadableManager.registerReloadListener(new WireRenderer());
            } else {
                PantographsAndWires.LOGGER.error("Could not register ReloadableResourceManager.");
            } 
        });

        ClientPlayerEvent.CLIENT_PLAYER_QUIT.register((server) -> {
            WireClientNetwork.clear();
            TractionSoundManager.stopAll();
        });

        ClientTickEvent.CLIENT_POST.register((mc) -> {
            if (mc.level != null) {
                TractionSoundManager.tick(mc.level.getGameTime());
            }
        });

        ClientGuiEvent.RENDER_HUD.register((graphics, ticks) -> {
            Player player = Minecraft.getInstance().player;
            for (InteractionHand hand : InteractionHand.values()) {
                ItemStack stack = player.getItemInHand(hand);

                if (stack.getItem() instanceof WireBaseItem item) {
                    HitResult lookingAt = Minecraft.getInstance().hitResult;
                    Component text = item.createHudInfoText(stack, Minecraft.getInstance().player, lookingAt);
                    if (text == null) {
                        continue;
                    }                    
                    int scaledWidth = Minecraft.getInstance().getWindow().getGuiScaledWidth();
                    int scaledHeight = Minecraft.getInstance().getWindow().getGuiScaledHeight();
                    graphics.drawCenteredString(Minecraft.getInstance().font, text, scaledWidth / 2, scaledHeight - 100, 0xFFFFFFFF);
                    break;
                }
            }
        });

        if (PantographsAndWires.isSodiumLoaded()) {
            SodiumCompatEvent.init();

            if (!PantographsAndWires.isIndiumLoaded()) {
                ClientGuiEvent.SET_SCREEN.register((screen) -> {
                    return CompoundEventResult.interruptTrue(new IncompatabilityScreen());
                });
            }
        }
    }
    
}
