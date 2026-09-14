package de.mrjulsen.paw.traction;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import de.mrjulsen.paw.PantographsAndWires;
import de.mrjulsen.paw.network.TractionDebugPacket;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * /paw_traction_debug client on|off switches the traction sound logging of the player's own game client
 * (and its F3 lines); /paw_traction_debug server on|off, for operators, switches the server's.
 */
public final class TractionDebugCommands {
    private TractionDebugCommands() {}

    public static void register() {
        CommandRegistrationEvent.EVENT.register((dispatcher, registry, selection) -> dispatcher.register(
            Commands.literal("paw_traction_debug")
                .then(Commands.literal("client")
                    .then(Commands.literal("on").executes(context -> setClient(context, true)))
                    .then(Commands.literal("off").executes(context -> setClient(context, false))))
                .then(Commands.literal("server")
                    .requires(source -> source.hasPermission(2))
                    .then(Commands.literal("on").executes(context -> setServer(context, true)))
                    .then(Commands.literal("off").executes(context -> setServer(context, false))))));
    }

    private static int setClient(CommandContext<CommandSourceStack> context, boolean enabled) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        PantographsAndWires.net().CHANNEL.sendToPlayer(player, new TractionDebugPacket(enabled));
        context.getSource().sendSuccess(() -> Component.literal("Traction sound logging on your client " + (enabled ? "on" : "off")
            + (enabled ? ". Lines start with [PAW traction] in your game log, and F3 shows the live state." : ".")), false);
        return 1;
    }

    private static int setServer(CommandContext<CommandSourceStack> context, boolean enabled) {
        TractionDebug.setServer(enabled);
        context.getSource().sendSuccess(() -> Component.literal("Traction server logging " + (enabled ? "on" : "off")
            + ". Lines start with [PAW traction] in the server log."), true);
        return 1;
    }
}
