package de.mrjulsen.paw.traction;

import java.util.Locale;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import de.mrjulsen.paw.PantographsAndWires;
import de.mrjulsen.paw.config.ModServerConfig;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * /paw_third_rail_follow from to, for operators: works out the third rail a player would lay between two
 * rail block positions along the Create track beside them, without placing anything, and reports the track
 * found at each end, the route and the resulting path. For checking track following against a real line.
 */
public final class ThirdRailCommands {
    private ThirdRailCommands() {}

    public static void register() {
        CommandRegistrationEvent.EVENT.register((dispatcher, registry, selection) -> dispatcher.register(
            Commands.literal("paw_third_rail_follow")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("from", BlockPosArgument.blockPos())
                    .then(Commands.argument("to", BlockPosArgument.blockPos())
                        .executes(ThirdRailCommands::follow)))));
    }

    private static int follow(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerLevel level = context.getSource().getLevel();
        BlockPos from = BlockPosArgument.getLoadedBlockPos(context, "from");
        BlockPos to = BlockPosArgument.getLoadedBlockPos(context, "to");
        long started = System.nanoTime();

        CreateTrackRoute.Spot spot1 = CreateTrackRoute.nearest(level, Vec3.atBottomCenterOf(from));
        CreateTrackRoute.Spot spot2 = CreateTrackRoute.nearest(level, Vec3.atBottomCenterOf(to));
        say(context, "track beside " + from.toShortString() + ": " + describe(spot1));
        say(context, "track beside " + to.toShortString() + ": " + describe(spot2));

        CreateTrackRoute.Result result = CreateTrackRoute.follow(level, from, to, ModServerConfig.THIRD_RAIL_MAX_LENGTH.get());
        double millis = (System.nanoTime() - started) / 1e6;
        say(context, String.format(Locale.ROOT, "result: %s (%.1f ms)", result.reason(), millis));
        if (result.routeLength() > 0) {
            say(context, String.format(Locale.ROOT, "route: %.1f blocks over %d track sections; side %+.2f -> %+.2f; height %+.2f -> %+.2f",
                result.routeLength(), result.sections(), result.side1(), result.side2(), result.up1(), result.up2()));
        }
        if (result.follows()) {
            ThirdRailConnection rail = ThirdRailConnection.alongPath(from, to, result.path(), true, result.side1() > 0, 0);
            double[] path = result.path();
            int count = path.length / 3;
            double minY = Double.MAX_VALUE;
            double maxY = -Double.MAX_VALUE;
            for (int i = 0; i < count; i++) {
                minY = Math.min(minY, path[i * 3 + 1]);
                maxY = Math.max(maxY, path[i * 3 + 1]);
            }
            Vec3 first = new Vec3(path[0], path[1], path[2]);
            Vec3 last = new Vec3(path[(count - 1) * 3], path[(count - 1) * 3 + 1], path[(count - 1) * 3 + 2]);
            say(context, String.format(Locale.ROOT, "rail: %.1f blocks, %d points, height %.2f to %.2f, costs %d rails; ends %.2f and %.2f from the block centres; stored copy %s",
                rail.curve().length(), count, minY, maxY, rail.computedRailCost(),
                first.distanceTo(Vec3.atBottomCenterOf(from)), last.distanceTo(Vec3.atBottomCenterOf(to)),
                ThirdRailConnection.read(from, rail.write()) != null ? "reads back" : "is REJECTED on reading"));
        }
        return result.follows() ? 1 : 0;
    }

    private static String describe(CreateTrackRoute.Spot spot) {
        if (spot == null) {
            return "none within reach";
        }
        Vec3 p = spot.position();
        return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f), %.2f away, %s section %.1f blocks long, %.0f%% along",
            p.x, p.y, p.z, spot.distance(), spot.edge().isTurn() ? "curved" : "straight", spot.edge().getLength(), spot.t() * 100);
    }

    private static void say(CommandContext<CommandSourceStack> context, String line) {
        context.getSource().sendSuccess(() -> Component.literal(line), false);
        PantographsAndWires.LOGGER.info("[PAW third rail] {}", line);
    }
}
