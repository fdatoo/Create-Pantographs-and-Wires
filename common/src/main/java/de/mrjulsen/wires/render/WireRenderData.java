package de.mrjulsen.wires.render;

import java.util.HashMap;
import java.util.Map;

import org.joml.Vector3f;

import de.mrjulsen.wires.render.WireRenderPoint.VertexCorner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;

public class WireRenderData {
    private final WireRenderPoint[] points;

    public WireRenderData(int size) {
        this.points = new WireRenderPoint[size];
    }

    public void setPoint(WireRenderPoint point, int index) {
        this.points[index] = point;
    }

    public WireRenderPoint getPoint(int index) {
        return points[index];
    }

    public int count() {
        return points.length;
    }

    public Map<SectionPos, WireSegmentRenderData> splitInChunkSections(SectionPos startSection) {
        Map<SectionPos, WireSegmentRenderData> result = new HashMap<>();
        Vector3f v = points[0].vertex(VertexCorner.CENTER);
        SectionPos lastRawSection = sectionOf(v);
        WireRenderPoint lastVertices = points[0].offset(lastRawSection);
        SectionPos lastSection = lastRawSection.offset(startSection.getX(), startSection.getY(), startSection.getZ());
        result.computeIfAbsent(lastSection, x -> new WireSegmentRenderData()).add(lastVertices);

        for (int i = 1; i < points.length; i++) {
            v = points[i].vertex(VertexCorner.CENTER);
            SectionPos rawSection = sectionOf(v);
            SectionPos section = rawSection.offset(startSection.getX(), startSection.getY(), startSection.getZ());
            WireRenderPoint vertices = points[i].offset(rawSection);
            if (lastSection.equals(section) || i < points.length - 1) {                
                result.computeIfAbsent(section, x -> new WireSegmentRenderData()).add(vertices);
            }
            if (!lastSection.equals(section)) {
                result.computeIfAbsent(lastSection, x -> new WireSegmentRenderData()).add(points[i].offset(lastRawSection));
            }

            lastVertices = vertices;
            lastSection = section;
            lastRawSection = rawSection;
        }

        return result;
    }

    /**
     * These coordinates are local to the wire's origin section, so they go negative whenever
     * a wire runs west, north or down from where it starts.
     *
     * A cast to int truncates towards zero while SectionPos floors with >>4, and the two
     * disagree for any value in (-1, 0), (-17, -16) and so on. A point landing in one of
     * those bands was filed one section away from the section that really contains it. The
     * drawn position stayed right, because offset() subtracts exactly what the key adds, but
     * the key is also what decides whether an all-air section is kept alive for its wire and
     * which section's bounds the geometry is culled against.
     */
    private static SectionPos sectionOf(Vector3f v) {
        return SectionPos.of(new BlockPos(Mth.floor(v.x), Mth.floor(v.y), Mth.floor(v.z)));
    }
}
