package de.mrjulsen.paw.traction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import de.mrjulsen.paw.traction.PantographContactGeometry.ContactResult;
import de.mrjulsen.paw.traction.PantographContactGeometry.WireSegment;

class PantographContactGeometryTest {
    private static final double EPSILON = 1e-9;
    private static final Vector3d COLLECTOR_BASE = new Vector3d(0, 0, 0);
    private static final Vector3d UP = new Vector3d(0, PantographContactGeometry.MAX_HEIGHT, 0);
    private static final Vector3d RIGHT = new Vector3d(PantographContactGeometry.MAX_WIDTH / 2, 0, 0);

    @Test
    void wireSegmentSnapshotsMutableEndpointVectors() {
        Vector3d start = new Vector3d(0, 2, -1);
        Vector3d end = new Vector3d(0, 2, 1);
        WireSegment segment = new WireSegment(start, end);

        start.set(99, 99, 99);
        end.set(-99, -99, -99);
        Vector3d exposedStart = segment.start();
        exposedStart.set(42, 42, 42);

        assertEquals(new Vector3d(0, 2, -1), segment.start());
        assertEquals(new Vector3d(0, 2, 1), segment.end());
    }

    @Test
    void equalHeightTiePreservesFirstWinningSegmentAndSource() {
        Object firstSource = new Object();
        Object secondSource = new Object();
        WireSegment first = new WireSegment(
            new Vector3d(-0.5, 2, -1),
            new Vector3d(-0.5, 2, 1),
            firstSource
        );
        WireSegment second = new WireSegment(
            new Vector3d(0.5, 2, -1),
            new Vector3d(0.5, 2, 1),
            secondSource
        );

        PantographContactGeometry.ContactDiagnostics diagnostics =
            PantographContactGeometry.findContactWithDiagnostics(
                COLLECTOR_BASE,
                UP,
                RIGHT,
                List.of(first, second)
            );
        ContactResult result = diagnostics.result();

        assertTrue(result.touching());
        assertSame(first, result.winningSegment());
        assertSame(firstSource, result.winningSegment().source());
        assertTrue(diagnostics.exactFallbackUsed());
    }

    @Test
    void frameAboveDocumentedConditionLimitFailsClosed() {
        Vector3d right = new Vector3d(1, 0, 0);
        Vector3d up = new Vector3d(1, 5e-5, 0);
        Vector3d point = new Vector3d(up).mul(0.5);
        WireSegment wire = new WireSegment(
            new Vector3d(point).sub(0, 0, 1),
            new Vector3d(point).add(0, 0, 1)
        );

        ContactResult result = PantographContactGeometry.findContact(
            COLLECTOR_BASE,
            up,
            right,
            List.of(wire)
        );

        assertEquals(ContactResult.none(), result);
    }

    @Test
    void representablyOutsideLocalSkewTopIsRejectedWithoutAcceptanceEpsilon() {
        Vector3d right = new Vector3d(1, 0.25, 0);
        Vector3d up = new Vector3d(0.5, 2, 0.25);
        double outsideHeight = Math.nextUp(1.0);
        Vector3d point = new Vector3d(up).mul(outsideHeight);
        Vector3d normal = new Vector3d(right).cross(up).normalize();

        for (double rightSign : new double[] { 1, -1 }) {
            for (boolean reversed : new boolean[] { false, true }) {
                Vector3d first = new Vector3d(point).sub(normal);
                Vector3d second = new Vector3d(point).add(normal);
                WireSegment wire = reversed
                    ? new WireSegment(second, first)
                    : new WireSegment(first, second);
                ContactResult result = PantographContactGeometry.findContact(
                    COLLECTOR_BASE,
                    up,
                    new Vector3d(right).mul(rightSign),
                    List.of(wire)
                );

                assertEquals(ContactResult.none(), result,
                    "rightSign=" + rightSign + ", reversed=" + reversed);
            }
        }
    }

    @Test
    void subnormalCollectorAxisFailsClosed() {
        Vector3d subnormalUp = new Vector3d(0, Double.MIN_VALUE, 0);
        WireSegment wire = new WireSegment(
            new Vector3d(0, 0, -1),
            new Vector3d(0, 0, 1)
        );

        ContactResult result = PantographContactGeometry.findContact(
            COLLECTOR_BASE,
            subnormalUp,
            RIGHT,
            List.of(wire)
        );

        assertEquals(ContactResult.none(), result);
    }

    @Test
    void collectorLocalInputsAreWorldBorderEquivalent() {
        Vector3d collectorBase = new Vector3d(0.75, 0.125, 0.25);
        Vector3d up = new Vector3d(0.5, 2, 0);
        Vector3d right = new Vector3d(1, -0.25, 0);

        for (int anchor : new int[] { 0, 29_999_999, -30_000_000 }) {
            int collisionBlockX = anchor + 1;
            int collisionBlockY = anchor + 1;
            int collisionBlockZ = anchor;
            WireSegment segment = new WireSegment(
                (double) collisionBlockX - anchor + 0.25,
                (double) collisionBlockY - anchor + 0.0625,
                (double) collisionBlockZ - anchor - 0.75,
                (double) collisionBlockX - anchor + 0.25,
                (double) collisionBlockY - anchor + 0.0625,
                (double) collisionBlockZ - anchor + 1.25
            );

            ContactResult result = PantographContactGeometry.findContact(
                collectorBase,
                up,
                right,
                List.of(segment)
            );

            assertTrue(result.touching(), "anchor=" + anchor);
            assertEquals(PantographContactGeometry.MAX_HEIGHT / 2, result.height(), EPSILON,
                "anchor=" + anchor);
        }
    }

    @Test
    void frameConditionPolicyAcceptsBelowAndRejectsAboveThreshold() {
        Vector3d right = new Vector3d(1, 0, 0);
        double acceptedAngle = 2 * Math.atan(1 / 9_000.0);
        double rejectedAngle = 2 * Math.atan(1 / 11_000.0);

        assertConditionedFrameContact(right, acceptedAngle, true);
        assertConditionedFrameContact(right, rejectedAngle, false);
    }

    @Test
    void solverDoesNotMutateCallerFrameVectors() {
        Vector3d base = new Vector3d(0.125, 0.25, 0.375);
        Vector3d up = new Vector3d(0.5, 2, 0);
        Vector3d right = new Vector3d(1, -0.25, 0);
        Vector3d baseBefore = new Vector3d(base);
        Vector3d upBefore = new Vector3d(up);
        Vector3d rightBefore = new Vector3d(right);
        Vector3d point = new Vector3d(base).add(new Vector3d(up).mul(0.5));

        PantographContactGeometry.findContact(
            base,
            up,
            right,
            List.of(new WireSegment(
                new Vector3d(point).sub(0, 0, 1),
                new Vector3d(point).add(0, 0, 1)
            ))
        );

        assertEquals(baseBefore, base);
        assertEquals(upBefore, up);
        assertEquals(rightBefore, right);
    }

    @Test
    void powerOfTwoScaleAndOrientationPreserveContactHeight() {
        for (int exponent : new int[] { -400, -40, 0, 40, 400 }) {
            double scale = Math.scalb(1.0, exponent);
            Vector3d up = new Vector3d(0.5, 2, 0).mul(scale);
            Vector3d right = new Vector3d(1, -0.25, 0).mul(scale);
            Vector3d point = new Vector3d(up).mul(0.375)
                .add(new Vector3d(right).mul(0.5));
            Vector3d normalOffset = new Vector3d(0, 0, scale);
            assertTrue(PantographContactGeometry.isValidCollectorFrame(up, right),
                "frame exponent=" + exponent);

            for (double rightSign : new double[] { 1, -1 }) {
                Vector3d orientedRight = new Vector3d(right).mul(rightSign);
                WireSegment forward = new WireSegment(
                    new Vector3d(point).sub(normalOffset),
                    new Vector3d(point).add(normalOffset)
                );
                WireSegment reversed = new WireSegment(forward.end(), forward.start());
                for (WireSegment segment : List.of(forward, reversed)) {
                    ContactResult result = PantographContactGeometry.findContact(
                        new Vector3d(),
                        up,
                        orientedRight,
                        List.of(segment)
                    );
                    assertTrue(result.touching(), "exponent=" + exponent + ", sign=" + rightSign
                        + ", result=" + result);
                    assertEquals(0.375 * PantographContactGeometry.MAX_HEIGHT, result.height(), EPSILON,
                        "exponent=" + exponent + ", sign=" + rightSign);
                }
            }
        }
    }

    @Test
    void wellSeparatedExtremePowerOfTwoScalesStayOnFilteredPath() {
        for (int exponent : new int[] { -400, 400 }) {
            double scale = Math.scalb(1.0, exponent);
            Vector3d up = new Vector3d(0.5, 2, 0).mul(scale);
            Vector3d right = new Vector3d(1, -0.25, 0).mul(scale);
            Vector3d point = new Vector3d(up).mul(0.375)
                .add(new Vector3d(right).mul(0.5));
            WireSegment wire = new WireSegment(
                new Vector3d(point).sub(0, 0, scale),
                new Vector3d(point).add(0, 0, scale)
            );

            PantographContactGeometry.ContactDiagnostics diagnostics =
                PantographContactGeometry.findContactWithDiagnostics(
                    new Vector3d(),
                    up,
                    right,
                    List.of(wire)
                );

            assertTrue(diagnostics.result().touching(), "exponent=" + exponent);
            assertEquals(0.375 * PantographContactGeometry.MAX_HEIGHT,
                diagnostics.result().height(), EPSILON, "exponent=" + exponent);
            assertFalse(diagnostics.exactFallbackUsed(), "exponent=" + exponent);
        }
    }

    @Test
    void quotientComparisonUnderflowSelectsNearerWireWithoutExactFallback() {
        double scale = Math.scalb(1.0, -400);
        Vector3d up = new Vector3d(0, PantographContactGeometry.MAX_HEIGHT, 0).mul(scale);
        Vector3d right = new Vector3d(PantographContactGeometry.MAX_WIDTH / 2, 0, 0).mul(scale);
        WireSegment fartherWire = new WireSegment(
            new Vector3d(up).mul(0.75).sub(0, 0, 1),
            new Vector3d(up).mul(0.75).add(0, 0, 1)
        );
        WireSegment nearerWire = new WireSegment(
            new Vector3d(up).mul(0.25).sub(0, 0, 1),
            new Vector3d(up).mul(0.25).add(0, 0, 1)
        );

        PantographContactGeometry.ContactDiagnostics diagnostics =
            PantographContactGeometry.findContactWithDiagnostics(
                new Vector3d(),
                up,
                right,
                List.of(fartherWire, nearerWire)
            );

        assertTrue(diagnostics.result().touching());
        assertEquals(0.25 * PantographContactGeometry.MAX_HEIGHT,
            diagnostics.result().height(), EPSILON);
        assertSame(nearerWire, diagnostics.result().winningSegment());
        assertFalse(diagnostics.exactFallbackUsed());
    }

    @Test
    void determinantProductUnderflowFailsClosedWithoutExactFallback() {
        Vector3d right = new Vector3d(Double.MIN_NORMAL, 0, 0);
        Vector3d up = new Vector3d(0, Double.MIN_NORMAL, 0);
        WireSegment wire = new WireSegment(
            new Vector3d(0, Double.MIN_NORMAL / 2, -0.5),
            new Vector3d(0, Double.MIN_NORMAL / 2, 0.5)
        );

        PantographContactGeometry.ContactDiagnostics diagnostics =
            PantographContactGeometry.findContactWithDiagnostics(
                new Vector3d(),
                up,
                right,
                List.of(wire)
            );

        assertEquals(ContactResult.none(), diagnostics.result());
        assertFalse(diagnostics.exactFallbackUsed());
    }

    @Test
    void subnormalWireDirectionFailsClosed() {
        WireSegment wire = new WireSegment(
            new Vector3d(0, 2, 0),
            new Vector3d(0, 2, Double.MIN_VALUE)
        );

        ContactResult result = PantographContactGeometry.findContact(
            COLLECTOR_BASE,
            UP,
            RIGHT,
            List.of(wire)
        );

        assertEquals(ContactResult.none(), result);
    }

    @Test
    void horizontalWireCrossingCollectorReturnsHeight() {
        WireSegment wire = new WireSegment(
            new Vector3d(0, 2, -1),
            new Vector3d(0, 2, 1)
        );

        ContactResult result = PantographContactGeometry.findContact(
            COLLECTOR_BASE,
            UP,
            RIGHT,
            List.of(wire)
        );

        assertTrue(result.touching());
        assertEquals(2, result.height(), EPSILON);
    }

    @Test
    void horizontalCollectorUpVectorReturnsCollectorSpaceHeight() {
        Vector3d horizontalUp = new Vector3d(0, 0, PantographContactGeometry.MAX_HEIGHT);
        WireSegment wire = new WireSegment(
            new Vector3d(0, -1, PantographContactGeometry.MAX_HEIGHT / 2),
            new Vector3d(0, 1, PantographContactGeometry.MAX_HEIGHT / 2)
        );

        ContactResult result = PantographContactGeometry.findContact(
            COLLECTOR_BASE,
            horizontalUp,
            RIGHT,
            List.of(wire)
        );

        assertTrue(result.touching());
        assertEquals(PantographContactGeometry.MAX_HEIGHT / 2, result.height(), EPSILON);
    }

    @Test
    void skewCollectorAxesReturnHalfHeightInCollectorSpace() {
        Vector3d skewUp = new Vector3d(1, PantographContactGeometry.MAX_HEIGHT, 0);
        Vector3d halfHeightContact = new Vector3d(skewUp).mul(0.5);
        WireSegment wire = new WireSegment(
            new Vector3d(halfHeightContact).sub(0, 0, 1),
            new Vector3d(halfHeightContact).add(0, 0, 1)
        );

        ContactResult result = PantographContactGeometry.findContact(
            COLLECTOR_BASE,
            skewUp,
            RIGHT,
            List.of(wire)
        );

        assertTrue(result.touching());
        assertEquals(PantographContactGeometry.MAX_HEIGHT / 2, result.height(), EPSILON);
    }

    @Test
    void negatingSkewCollectorRightAxisDoesNotChangeContact() {
        Vector3d skewUp = new Vector3d(1, PantographContactGeometry.MAX_HEIGHT, 0);
        Vector3d halfHeightContact = new Vector3d(skewUp).mul(0.5);
        WireSegment wire = new WireSegment(
            new Vector3d(halfHeightContact).sub(0, 0, 1),
            new Vector3d(halfHeightContact).add(0, 0, 1)
        );

        ContactResult positiveRight = PantographContactGeometry.findContact(
            COLLECTOR_BASE,
            skewUp,
            RIGHT,
            List.of(wire)
        );
        ContactResult negativeRight = PantographContactGeometry.findContact(
            COLLECTOR_BASE,
            skewUp,
            new Vector3d(RIGHT).negate(),
            List.of(wire)
        );

        assertEquals(positiveRight, negativeRight);
    }

    @Test
    void nanWireEndpointReturnsNoContact() {
        WireSegment wire = new WireSegment(
            new Vector3d(Double.NaN, 2, -1),
            new Vector3d(0, 2, 1)
        );

        ContactResult result = PantographContactGeometry.findContact(
            COLLECTOR_BASE,
            UP,
            RIGHT,
            List.of(wire)
        );

        assertEquals(ContactResult.none(), result);
    }

    @Test
    void infiniteCollectorInputReturnsNoContact() {
        ContactResult result = PantographContactGeometry.findContact(
            new Vector3d(Double.POSITIVE_INFINITY, 0, 0),
            UP,
            RIGHT,
            List.of(new WireSegment(new Vector3d(0, 2, -1), new Vector3d(0, 2, 1)))
        );

        assertEquals(ContactResult.none(), result);
    }

    @Test
    void smallFiniteUpAxisPreservesScaleInvariantContact() {
        Vector3d nearZeroUp = new Vector3d(0, 5e-9, 0);
        WireSegment wire = new WireSegment(
            new Vector3d(0, nearZeroUp.y / 2, -1),
            new Vector3d(0, nearZeroUp.y / 2, 1)
        );

        ContactResult result = PantographContactGeometry.findContact(
            COLLECTOR_BASE,
            nearZeroUp,
            RIGHT,
            List.of(wire)
        );

        assertTrue(result.touching());
        assertEquals(PantographContactGeometry.MAX_HEIGHT / 2, result.height(), EPSILON);
    }

    @Test
    void smallFiniteRightAxisPreservesScaleInvariantContact() {
        Vector3d nearZeroRight = new Vector3d(5e-9, 0, 0);
        WireSegment wire = new WireSegment(
            new Vector3d(0, 2, -1),
            new Vector3d(0, 2, 1)
        );

        ContactResult result = PantographContactGeometry.findContact(
            COLLECTOR_BASE,
            UP,
            nearZeroRight,
            List.of(wire)
        );

        assertTrue(result.touching());
        assertEquals(2, result.height(), EPSILON);
    }

    @Test
    void largeFiniteGeometryAvoidsIntermediateOverflow() {
        double largeFiniteComponent = 9e153;
        Vector3d largeRight = new Vector3d(largeFiniteComponent, largeFiniteComponent, 0);
        Vector3d largeUp = new Vector3d(largeFiniteComponent, -largeFiniteComponent, 0);
        WireSegment wire = new WireSegment(
            new Vector3d(0, 0, -1),
            new Vector3d(0, 0, 1)
        );

        ContactResult result = PantographContactGeometry.findContact(
            COLLECTOR_BASE,
            largeUp,
            largeRight,
            List.of(wire)
        );

        assertTrue(result.touching());
        assertEquals(0, result.height(), EPSILON);
    }

    @Test
    void slopedWireReturnsNearestValidHeight() {
        WireSegment wire = new WireSegment(
            new Vector3d(0, 1, -1),
            new Vector3d(0, 3, 1)
        );

        ContactResult result = PantographContactGeometry.findContact(
            COLLECTOR_BASE,
            UP,
            RIGHT,
            List.of(wire)
        );

        assertTrue(result.touching());
        assertEquals(2, result.height(), EPSILON);
    }

    @Test
    void uniformlyScalingValidGeometryPreservesContact() {
        double scale = 1e-4;
        WireSegment unscaledWire = new WireSegment(
            new Vector3d(0, 2, -1),
            new Vector3d(0, 2, 1)
        );
        WireSegment scaledWire = new WireSegment(
            new Vector3d(unscaledWire.start()).mul(scale),
            new Vector3d(unscaledWire.end()).mul(scale)
        );

        ContactResult unscaled = PantographContactGeometry.findContact(
            COLLECTOR_BASE,
            UP,
            RIGHT,
            List.of(unscaledWire)
        );
        ContactResult scaled = PantographContactGeometry.findContact(
            new Vector3d(COLLECTOR_BASE).mul(scale),
            new Vector3d(UP).mul(scale),
            new Vector3d(RIGHT).mul(scale),
            List.of(scaledWire)
        );

        assertTrue(unscaled.touching());
        assertTrue(scaled.touching());
        assertEquals(unscaled.height(), scaled.height(), EPSILON);
    }

    @Test
    void reversingWireEndpointsDoesNotChangeContact() {
        Vector3d start = new Vector3d(0, 1, -1);
        Vector3d end = new Vector3d(0, 3, 1);

        ContactResult forward = PantographContactGeometry.findContact(
            COLLECTOR_BASE,
            UP,
            RIGHT,
            List.of(new WireSegment(new Vector3d(start), new Vector3d(end)))
        );
        ContactResult reversed = PantographContactGeometry.findContact(
            COLLECTOR_BASE,
            UP,
            RIGHT,
            List.of(new WireSegment(new Vector3d(end), new Vector3d(start)))
        );

        assertEquals(forward.touching(), reversed.touching());
        assertEquals(forward.height(), reversed.height(), EPSILON);
    }

    @Test
    void wireOutsideCollectorWidthReturnsNoContact() {
        double outsideX = PantographContactGeometry.MAX_WIDTH / 2 + 0.01;
        WireSegment wire = new WireSegment(
            new Vector3d(outsideX, 2, -1),
            new Vector3d(outsideX, 2, 1)
        );

        ContactResult result = PantographContactGeometry.findContact(
            COLLECTOR_BASE,
            UP,
            RIGHT,
            List.of(wire)
        );

        assertFalse(result.touching());
        assertEquals(ContactResult.none(), result);
    }

    @Test
    void wireAboveMaximumReachReturnsNoContact() {
        double aboveReach = PantographContactGeometry.MAX_HEIGHT + 0.01;
        WireSegment wire = new WireSegment(
            new Vector3d(0, aboveReach, -1),
            new Vector3d(0, aboveReach, 1)
        );

        ContactResult result = PantographContactGeometry.findContact(
            COLLECTOR_BASE,
            UP,
            RIGHT,
            List.of(wire)
        );

        assertFalse(result.touching());
        assertEquals(ContactResult.none(), result);
    }

    @Test
    void exactTopHeightIsContactAndCarriesWinner() {
        WireSegment wire = new WireSegment(
            new Vector3d(0, PantographContactGeometry.MAX_HEIGHT, -1),
            new Vector3d(0, PantographContactGeometry.MAX_HEIGHT, 1)
        );

        PantographContactGeometry.ContactDiagnostics diagnostics =
            PantographContactGeometry.findContactWithDiagnostics(
                COLLECTOR_BASE,
                UP,
                RIGHT,
                List.of(wire)
            );
        ContactResult result = diagnostics.result();

        assertTrue(result.touching());
        assertEquals(PantographContactGeometry.MAX_HEIGHT, result.height());
        assertSame(wire, result.winningSegment());
        assertTrue(diagnostics.exactFallbackUsed());
    }

    @Test
    void rotatedCollectorPlaneStillIntersects() {
        Vector3d rotatedRight = new Vector3d(0, 0, PantographContactGeometry.MAX_WIDTH / 2);
        WireSegment wire = new WireSegment(
            new Vector3d(-1, 2, 0),
            new Vector3d(1, 2, 0)
        );

        ContactResult result = PantographContactGeometry.findContact(
            COLLECTOR_BASE,
            UP,
            rotatedRight,
            List.of(wire)
        );

        assertTrue(result.touching());
        assertEquals(2, result.height(), EPSILON);
    }

    @Test
    void zeroLengthWireSegmentRejectsGeometry() {
        WireSegment validWire = new WireSegment(
            new Vector3d(0, 2, -1),
            new Vector3d(0, 2, 1)
        );
        Vector3d point = new Vector3d(0, 1, 0);
        WireSegment zeroLengthWire = new WireSegment(new Vector3d(point), new Vector3d(point));

        ContactResult result = PantographContactGeometry.findContact(
            COLLECTOR_BASE,
            UP,
            RIGHT,
            List.of(validWire, zeroLengthWire)
        );

        assertEquals(ContactResult.none(), result);
    }

    @Test
    void nearestOfTwoWiresWins() {
        WireSegment fartherWire = new WireSegment(
            new Vector3d(0, 3, -1),
            new Vector3d(0, 3, 1)
        );
        WireSegment nearerWire = new WireSegment(
            new Vector3d(0, 1.5, -1),
            new Vector3d(0, 1.5, 1)
        );

        ContactResult result = PantographContactGeometry.findContact(
            COLLECTOR_BASE,
            UP,
            RIGHT,
            List.of(fartherWire, nearerWire)
        );

        assertTrue(result.touching());
        assertEquals(1.5, result.height(), EPSILON);
        assertSame(nearerWire, result.winningSegment());
    }

    @Test
    void exactSkewCollectorCornersAreInvariantToWireAndRightOrientation() {
        assertBoundaryContacts(
            new double[][] {
                { 0, 0 },
                { 1, 0 },
                { 0, 1 },
                { 1, 1 }
            }
        );
    }

    @Test
    void exactSkewCollectorEdgesAreInvariantToWireAndRightOrientation() {
        assertBoundaryContacts(
            new double[][] {
                { 0, 0.375 },
                { 1, 0.375 },
                { 0.5, 0 },
                { 0.5, 1 }
            }
        );
    }

    @Test
    void pointsJustInsideSkewCollectorBoundsRemainContacts() {
        double inside = 1e-10;
        assertBoundaryContacts(
            new double[][] {
                { inside, inside },
                { 1 - inside, inside },
                { inside, 1 - inside },
                { 1 - inside, 1 - inside }
            }
        );
    }

    @Test
    void pointsGenuinelyOutsideSkewCollectorBoundsRemainRejected() {
        Vector3d skewUp = new Vector3d(0.3, 1.1, 0.7);
        Vector3d skewRight = new Vector3d(0.8, -0.2, 0.4);
        Vector3d normal = new Vector3d(skewRight).cross(skewUp).normalize();
        double outside = 1e-9;
        double[][] collectorCoordinates = {
            { -outside, 0.5 },
            { 1 + outside, 0.5 },
            { 0.5, -outside },
            { 0.5, 1 + outside }
        };

        for (double rightSign : new double[] { 1, -1 }) {
            Vector3d orientedRight = new Vector3d(skewRight).mul(rightSign);
            for (double[] coordinates : collectorCoordinates) {
                Vector3d point = collectorPoint(skewUp, orientedRight, coordinates[0], coordinates[1]);
                Vector3d first = new Vector3d(point).sub(normal);
                Vector3d second = new Vector3d(point).add(normal);
                for (boolean reversed : new boolean[] { false, true }) {
                    WireSegment wire = reversed
                        ? new WireSegment(second, first)
                        : new WireSegment(first, second);
                    ContactResult result = PantographContactGeometry.findContact(
                        COLLECTOR_BASE,
                        skewUp,
                        orientedRight,
                        List.of(wire)
                    );

                    assertEquals(
                        ContactResult.none(),
                        result,
                        "coordinates=" + coordinates[0] + "," + coordinates[1]
                            + ", rightSign=" + rightSign + ", reversed=" + reversed
                    );
                }
            }
        }
    }

    private static void assertConditionedFrameContact(
        Vector3d right,
        double angle,
        boolean expectedTouching
    ) {
        Vector3d up = new Vector3d(Math.cos(angle), Math.sin(angle), 0);
        Vector3d point = new Vector3d(up).mul(0.5);
        ContactResult result = PantographContactGeometry.findContact(
            new Vector3d(),
            up,
            right,
            List.of(new WireSegment(
                new Vector3d(point).sub(0, 0, 1),
                new Vector3d(point).add(0, 0, 1)
            ))
        );
        assertEquals(expectedTouching, result.touching(), "angle=" + angle);
    }

    private static void assertBoundaryContacts(double[][] collectorCoordinates) {
        Vector3d skewUp = new Vector3d(0.5, 2, 0);
        Vector3d skewRight = new Vector3d(1, -0.25, 0);
        Vector3d normal = new Vector3d(skewRight).cross(skewUp).normalize();

        for (double rightSign : new double[] { 1, -1 }) {
            Vector3d orientedRight = new Vector3d(skewRight).mul(rightSign);
            for (double[] coordinates : collectorCoordinates) {
                Vector3d point = collectorPoint(skewUp, orientedRight, coordinates[0], coordinates[1]);
                Vector3d first = new Vector3d(point).sub(normal);
                Vector3d second = new Vector3d(point).add(normal);
                for (boolean reversed : new boolean[] { false, true }) {
                    WireSegment wire = reversed
                        ? new WireSegment(second, first)
                        : new WireSegment(first, second);
                    ContactResult result = PantographContactGeometry.findContact(
                        COLLECTOR_BASE,
                        skewUp,
                        orientedRight,
                        List.of(wire)
                    );

                    String context = "coordinates=" + coordinates[0] + "," + coordinates[1]
                        + ", rightSign=" + rightSign + ", reversed=" + reversed;
                    assertTrue(result.touching(), context);
                    assertEquals(coordinates[1] * PantographContactGeometry.MAX_HEIGHT, result.height(), EPSILON, context);
                }
            }
        }
    }

    private static Vector3d collectorPoint(
        Vector3d upVector,
        Vector3d rightVector,
        double widthPosition,
        double heightPosition
    ) {
        return new Vector3d(COLLECTOR_BASE)
            .sub(rightVector)
            .add(new Vector3d(rightVector).mul(2 * widthPosition))
            .add(new Vector3d(upVector).mul(heightPosition));
    }
}
