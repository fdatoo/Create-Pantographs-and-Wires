package de.mrjulsen.paw.traction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.graph.TrackEdge;
import com.simibubi.create.content.trains.graph.TrackGraph;
import com.simibubi.create.content.trains.graph.TrackGraphBounds;
import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Lays a third rail along a real Create track: finds the track beside each of the rail's two blocks, the
 * route between those spots that a train could drive (Create's own TrackEdge.canTravelTo, so it never
 * reverses through a junction), and that route's centreline moved sideways and up to the rail blocks
 * (TrackFollowPath). The two blocks must sit the same distance beside the same track; otherwise there is
 * nothing to follow and the rail is laid as a free curve.
 */
public final class CreateTrackRoute {
    /** How far to the side a rail block may be from the track it follows. */
    public static final double MAX_SIDE = 4.5;
    /** Closer than this a block is on the track rather than beside it. */
    public static final double MIN_SIDE = 0.75;
    /** How far above or below the track a rail block's base may be. */
    public static final double MAX_UP = 2.5;
    /**
     * How much the two ends' distances beside and above the track may differ. Rail blocks sit on whole
     * blocks, so beside a curve their distance from the track varies by up to about a block; the path blends
     * from one end's offset to the other's, so a difference this size never shows as a kink.
     */
    public static final double MAX_MISMATCH = 1.5;
    /** How long a result is reused for the same two blocks, so the placement preview doesn't search every tick. */
    private static final long REUSE_MILLIS = 500;
    /** Sampling step along track, in blocks, when looking for the nearest spot and tracing the route. */
    private static final double SAMPLE = 0.25;

    /**
     * @param path   the rail's path as flattened points (TrackFollowPath), or null when there is nothing to follow
     * @param reason why not, for the placement message and the test command
     */
    public record Result(@Nullable double[] path, String reason, double side1, double side2, double up1, double up2,
        double routeLength, int sections) {

        static Result no(String reason) {
            return new Result(null, reason, 0, 0, 0, 0, 0, 0);
        }

        public boolean follows() {
            return path != null;
        }
    }

    /** A point on a track: a directed track section and how far along it (0 to 1). */
    public record Spot(TrackGraph graph, TrackEdge edge, double t, Vec3 position, double distance) {}

    private CreateTrackRoute() {}

    private record CacheKey(boolean client, BlockPos from, BlockPos to, double maxLength) {}

    private record Cached(CacheKey key, Result result, long at) {}

    private static volatile Cached clientCache;
    private static volatile Cached serverCache;

    public static Result follow(Level level, BlockPos from, BlockPos to, double maxLength) {
        CacheKey key = new CacheKey(level.isClientSide(), from.immutable(), to.immutable(), maxLength);
        Cached cached = key.client() ? clientCache : serverCache;
        long now = System.currentTimeMillis();
        if (cached != null && cached.key().equals(key) && now - cached.at() < REUSE_MILLIS) {
            return cached.result();
        }
        Result result = search(level, from, to, maxLength);
        Cached fresh = new Cached(key, result, now);
        if (key.client()) {
            clientCache = fresh;
        } else {
            serverCache = fresh;
        }
        return result;
    }

    private static Result search(Level level, BlockPos from, BlockPos to, double maxLength) {
        Vec3 start = Vec3.atBottomCenterOf(from);
        Vec3 end = Vec3.atBottomCenterOf(to);
        Spot spot1 = nearest(level, start);
        if (spot1 == null) {
            return Result.no("no track beside the first rail block");
        }
        Spot spot2 = nearest(level, end);
        if (spot2 == null) {
            return Result.no("no track beside the second rail block");
        }
        if (spot1.graph() != spot2.graph()) {
            return Result.no("the two rail blocks are beside different track networks");
        }
        List<Vector3d> centreline = route(spot1, spot2, maxLength * 1.5);
        if (centreline == null || centreline.size() < 2) {
            return Result.no("no drivable route along the track between the two rail blocks");
        }
        int last = centreline.size() - 1;
        double side1 = TrackFollowPath.side(joml(start), centreline.get(0), TrackFollowPath.tangentAt(centreline, 0));
        double side2 = TrackFollowPath.side(joml(end), centreline.get(last), TrackFollowPath.tangentAt(centreline, last));
        double up1 = start.y - centreline.get(0).y;
        double up2 = end.y - centreline.get(last).y;
        double routeLength = TrackFollowPath.length(centreline);
        int sections = countSections;
        if (Math.abs(side1) < MIN_SIDE || Math.abs(side2) < MIN_SIDE) {
            return new Result(null, "a rail block is on the track, not beside it", side1, side2, up1, up2, routeLength, sections);
        }
        if (Math.signum(side1) != Math.signum(side2) || Math.abs(side1 - side2) > MAX_MISMATCH || Math.abs(up1 - up2) > MAX_MISMATCH) {
            return new Result(null, "the rail blocks aren't the same distance beside the track", side1, side2, up1, up2, routeLength, sections);
        }
        return new Result(TrackFollowPath.offset(centreline, side1, side2, up1, up2), "follows the track", side1, side2, up1, up2, routeLength, sections);
    }

    /** Sections used by the latest route; read straight after route() on the same thread. */
    private static int countSections;

    /** The nearest track point beside a rail block's base, or null when no track is close enough. */
    @Nullable
    public static Spot nearest(Level level, Vec3 point) {
        AABB query = new AABB(point, point).inflate(MAX_SIDE + 1, MAX_UP + 1, MAX_SIDE + 1);
        Spot best = null;
        for (TrackGraph graph : Create.RAILWAYS.sided(level).trackNetworks.values()) {
            TrackGraphBounds bounds = graph.getBounds(level);
            if (bounds == null || bounds.box == null || !bounds.box.inflate(1).intersects(query)) {
                continue;
            }
            for (TrackNodeLocation location : graph.getNodes()) {
                if (location.getDimension() != null && !location.getDimension().equals(level.dimension())) {
                    continue;
                }
                TrackNode node = graph.locateNode(location);
                if (node == null) {
                    continue;
                }
                for (Map.Entry<TrackNode, TrackEdge> entry : graph.getConnectionsFrom(node).entrySet()) {
                    TrackEdge edge = entry.getValue();
                    // Each section is stored once in each direction; look at one of them.
                    if (edge.isInterDimensional() || node.getNetId() > entry.getKey().getNetId() || !edgeBox(graph, edge).intersects(query)) {
                        continue;
                    }
                    double length = Math.max(edge.getLength(), 1e-3);
                    int steps = Math.max(1, (int) Math.ceil(length / SAMPLE));
                    for (int i = 0; i <= steps; i++) {
                        double t = i / (double) steps;
                        Vec3 on = edge.getPosition(graph, t);
                        double dy = Math.abs(point.y - on.y);
                        if (dy > MAX_UP) {
                            continue;
                        }
                        double horizontal = Math.hypot(point.x - on.x, point.z - on.z);
                        if (horizontal > MAX_SIDE) {
                            continue;
                        }
                        double score = horizontal + dy * 0.5;
                        if (best == null || score < best.distance()) {
                            best = new Spot(graph, edge, t, on, score);
                        }
                    }
                }
            }
        }
        return best;
    }

    private static AABB edgeBox(TrackGraph graph, TrackEdge edge) {
        if (edge.isTurn()) {
            return edge.getTurn().getBounds().inflate(1);
        }
        return new AABB(edge.node1.getLocation().getLocation(), edge.node2.getLocation().getLocation()).inflate(1);
    }

    /** One piece of a route: a directed section from one fraction along it to another. */
    private record Piece(TrackEdge edge, double from, double to) {}

    private record Visit(TrackEdge via, double distance, @Nullable Visit previous) {}

    /** The centreline from one spot to another along drivable track, or null when there is none within maxLength. */
    @Nullable
    static List<Vector3d> route(Spot from, Spot to, double maxLength) {
        TrackGraph graph = from.graph();
        List<Piece> pieces = new ArrayList<>();
        countSections = 0;
        if (from.edge() == to.edge()) {
            pieces.add(from.t() <= to.t() ? new Piece(from.edge(), from.t(), to.t())
                : new Piece(reverse(graph, from.edge()), 1 - from.t(), 1 - to.t()));
        } else {
            TrackEdge forward = from.edge();
            TrackEdge backward = reverse(graph, forward);
            TrackEdge target = to.edge();
            TrackEdge targetReversed = reverse(graph, target);
            if (backward == null || targetReversed == null) {
                return null;
            }
            PriorityQueue<Visit> queue = new PriorityQueue<>((a, b) -> Double.compare(a.distance(), b.distance()));
            Map<TrackEdge, Double> settled = new HashMap<>();
            queue.add(new Visit(forward, (1 - from.t()) * forward.getLength(), null));
            queue.add(new Visit(backward, from.t() * backward.getLength(), null));
            Visit bestVisit = null;
            Piece bestLast = null;
            double bestTotal = Double.MAX_VALUE;
            while (!queue.isEmpty()) {
                Visit visit = queue.poll();
                if (visit.distance() >= bestTotal || visit.distance() > maxLength) {
                    break;
                }
                Double known = settled.get(visit.via());
                if (known != null && known <= visit.distance()) {
                    continue;
                }
                settled.put(visit.via(), visit.distance());
                TrackNode at = visit.via().node2;
                // Reaching the section the second spot is on, travelling either way along it.
                if (at == target.node1 && visit.via().canTravelTo(target)) {
                    double total = visit.distance() + to.t() * target.getLength();
                    if (total < bestTotal) {
                        bestTotal = total;
                        bestVisit = visit;
                        bestLast = new Piece(target, 0, to.t());
                    }
                }
                if (at == target.node2 && visit.via().canTravelTo(targetReversed)) {
                    double total = visit.distance() + (1 - to.t()) * targetReversed.getLength();
                    if (total < bestTotal) {
                        bestTotal = total;
                        bestVisit = visit;
                        bestLast = new Piece(targetReversed, 0, 1 - to.t());
                    }
                }
                for (TrackEdge next : graph.getConnectionsFrom(at).values()) {
                    if (next.isInterDimensional() || next == target || next == targetReversed || !visit.via().canTravelTo(next)) {
                        continue;
                    }
                    double distance = visit.distance() + next.getLength();
                    if (distance <= maxLength) {
                        queue.add(new Visit(next, distance, visit));
                    }
                }
            }
            if (bestVisit == null) {
                return null;
            }
            List<Piece> reversed = new ArrayList<>();
            reversed.add(bestLast);
            for (Visit visit = bestVisit; visit != null; visit = visit.previous()) {
                if (visit.previous() == null) {
                    boolean isForward = visit.via() == forward;
                    reversed.add(new Piece(visit.via(), isForward ? from.t() : 1 - from.t(), 1));
                } else {
                    reversed.add(new Piece(visit.via(), 0, 1));
                }
            }
            Collections.reverse(reversed);
            pieces.addAll(reversed);
        }
        countSections = pieces.size();

        List<Vector3d> points = new ArrayList<>();
        for (Piece piece : pieces) {
            double length = Math.max(piece.edge().getLength() * Math.abs(piece.to() - piece.from()), 1e-3);
            int steps = Math.max(1, (int) Math.ceil(length / SAMPLE));
            for (int i = 0; i <= steps; i++) {
                double t = piece.from() + (piece.to() - piece.from()) * i / steps;
                Vector3d point = joml(piece.edge().getPosition(graph, t));
                if (points.isEmpty() || points.get(points.size() - 1).distance(point) > 1e-3) {
                    points.add(point);
                }
            }
        }
        return points;
    }

    @Nullable
    private static TrackEdge reverse(TrackGraph graph, TrackEdge edge) {
        return graph.getConnectionsFrom(edge.node2).get(edge.node1);
    }

    private static Vector3d joml(Vec3 v) {
        return new Vector3d(v.x, v.y, v.z);
    }
}
