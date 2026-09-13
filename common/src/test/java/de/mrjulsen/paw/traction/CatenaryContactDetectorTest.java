package de.mrjulsen.paw.traction;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import de.mrjulsen.wires.WireCollision.WireBlockCollision;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

class CatenaryContactDetectorTest {
    private static final double EPSILON = 1e-9;

    @Test
    void commonDetectorBytecodeDoesNotLinkClientOnlyClasses() throws IOException {
        String constantPool = new String(detectorClassBytes(), StandardCharsets.ISO_8859_1);

        assertFalse(constantPool.contains("WireClientNetwork"));
        assertFalse(constantPool.contains("net/minecraft/client"));
    }

    @Test
    void commonDetectorLoadsWhenClientClassesAreUnavailable() throws IOException {
        byte[] classBytes = detectorClassBytes();
        String detectorName = CatenaryContactDetector.class.getName();
        String clientSourceName = detectorName + "$ClientCollisionSource";
        ClassLoader serverOnlyLoader = new ClassLoader(CatenaryContactDetector.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals(clientSourceName) || name.contains("WireClientNetwork")
                    || name.startsWith("net.minecraft.client.")) {
                    throw new ClassNotFoundException("client class excluded: " + name);
                }
                if (!name.equals(detectorName)) {
                    return super.loadClass(name, resolve);
                }
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    if (loaded == null) {
                        loaded = defineClass(name, classBytes, 0, classBytes.length);
                    }
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
            }
        };

        Class<?> loaded = assertDoesNotThrow(
            () -> Class.forName(detectorName, true, serverOnlyLoader)
        );

        assertSame(serverOnlyLoader, loaded.getClassLoader());
    }

    @Test
    void flattensCollisionsFromEveryIntersectedBlockBeforeSolving() {
        Object lowerSource = new Object();
        BlockPos lowerBlock = new BlockPos(0, 1, 0);
        BlockPos upperBlock = new BlockPos(0, 2, 0);
        Map<BlockPos, List<CollisionData>> collisions = Map.of(
            lowerBlock, List.of(collision("lower", lowerSource, lowerBlock, 0)),
            upperBlock, List.of(collision("upper", new Object(), upperBlock, 0))
        );
        CollisionSource source = pos -> collisions.getOrDefault(pos, List.of());

        PantographContactGeometry.ContactResult result = findContact(source);

        assertTrue(result.touching());
        assertEquals(0.75, result.height(), EPSILON);
        assertSame(lowerSource, result.winningSegment().source());
    }

    @Test
    void duplicateStableIdentityPreservesFirstEncounteredCollisionAndSource() {
        Object firstSource = new Object();
        BlockPos block = new BlockPos(0, 1, 0);
        CollisionSource source = pos -> pos.equals(block)
            ? List.of(
                collision(new String("same-wire-collision"), firstSource, block, 0.5),
                collision(new String("same-wire-collision"), new Object(), block, 0)
            )
            : List.of();

        PantographContactGeometry.ContactResult result = findContact(source);

        assertTrue(result.touching());
        assertEquals(1.25, result.height(), EPSILON);
        assertSame(firstSource, result.winningSegment().source());
    }

    @Test
    void malformedDuplicatePermutationsFailClosedBeforeIdentityDedupe() {
        BlockPos block = new BlockPos(0, 1, 0);
        CollisionData valid = collision("same-wire-collision", new Object(), block, 0);
        CollisionData malformed = new CollisionData(
            "same-wire-collision",
            block,
            new Vector3d(0.5, Double.NaN, 0),
            new Vector3d(0.5, 0, 1),
            new Object()
        );

        for (List<CollisionData> permutation : List.of(
            List.of(valid, malformed),
            List.of(malformed, valid)
        )) {
            CollisionSource source = pos -> pos.equals(block) ? permutation : List.of();

            PantographContactGeometry.ContactResult result = findContact(source);

            assertEquals(PantographContactGeometry.ContactResult.none(), result);
        }
    }

    @Test
    void entireConsumedBatchIsValidatedBeforeIdentityDedupeBegins() {
        BlockPos block = new BlockPos(0, 1, 0);
        AtomicInteger hashCalls = new AtomicInteger();
        Object identity = new Object() {
            @Override
            public int hashCode() {
                hashCalls.incrementAndGet();
                return 1;
            }
        };
        CollisionData valid = collision(identity, new Object(), block, 0);
        CollisionData malformed = new CollisionData(
            identity,
            block,
            new Vector3d(Double.NaN, 0, 0),
            new Vector3d(0.5, 0, 1),
            new Object()
        );
        CollisionSource source = pos -> pos.equals(block)
            ? List.of(valid, malformed)
            : List.of();

        PantographContactGeometry.ContactResult result = findContact(source);

        assertEquals(PantographContactGeometry.ContactResult.none(), result);
        assertEquals(0, hashCalls.get(), "dedupe must wait until whole-batch validation succeeds");
    }

    @Test
    void duplicateHeavyCollisionBatchFailsClosedWithBoundedConsumption() {
        BlockPos block = new BlockPos(0, 1, 0);
        CollisionData duplicate = collision("duplicate", new Object(), block, 0);
        AtomicInteger consumed = new AtomicInteger();
        Iterable<CollisionData> duplicateHeavy = () -> new Iterator<>() {
            private static final int AVAILABLE = 200_000;
            private int remaining = AVAILABLE;

            @Override
            public boolean hasNext() {
                return remaining > 0;
            }

            @Override
            public CollisionData next() {
                remaining--;
                consumed.incrementAndGet();
                return duplicate;
            }
        };
        CollisionSource source = pos -> pos.equals(block) ? duplicateHeavy : List.of();

        PantographContactGeometry.ContactResult result = assertTimeoutPreemptively(
            Duration.ofSeconds(1),
            () -> findContact(source)
        );

        assertEquals(PantographContactGeometry.ContactResult.none(), result);
        assertEquals(
            CatenaryContactDetector.MAX_TOTAL_COLLISION_DATA,
            consumed.get(),
            "detector must not consume beyond its total collision budget"
        );
    }

    @Test
    void totalCollisionBudgetAppliesAcrossQueriedBlocksWithoutOffByOne() {
        BlockPos lowerBlock = new BlockPos(0, 1, 0);
        BlockPos upperBlock = new BlockPos(0, 2, 0);
        int halfBudget = (int) (CatenaryContactDetector.MAX_TOTAL_COLLISION_DATA / 2);
        CollisionData lower = collision("lower", new Object(), lowerBlock, 0);
        CollisionData upper = collision("upper", new Object(), upperBlock, 0);
        CollisionSource atLimit = pos -> {
            if (pos.equals(lowerBlock)) {
                return Collections.nCopies(halfBudget, lower);
            }
            if (pos.equals(upperBlock)) {
                return Collections.nCopies(halfBudget, upper);
            }
            return List.of();
        };
        CollisionSource overLimit = pos -> {
            if (pos.equals(lowerBlock)) {
                return Collections.nCopies(halfBudget, lower);
            }
            if (pos.equals(upperBlock)) {
                return Collections.nCopies(halfBudget + 1, upper);
            }
            return List.of();
        };

        PantographContactGeometry.ContactResult accepted = findContact(atLimit);
        PantographContactGeometry.ContactResult rejected = findContact(overLimit);

        assertTrue(accepted.touching(), "the exact aggregate budget must remain valid");
        assertEquals(PantographContactGeometry.ContactResult.none(), rejected);
    }

    @Test
    void convertsBlockRelativeEndpointsDirectlyIntoCollectorLocalCoordinatesAtWorldBorder() {
        Object source = new Object();
        for (int anchorCoordinate : new int[] { 0, 29_999_999, -30_000_000 }) {
            BlockPos anchor = new BlockPos(anchorCoordinate, 64, anchorCoordinate);
            BlockPos collisionBlock = new BlockPos(anchorCoordinate + 1, 65, anchorCoordinate - 1);
            CollisionData collision = new CollisionData(
                "wire-collision",
                collisionBlock,
                new Vector3d(0.125, 0.25, 0.375),
                new Vector3d(0.625, 0.75, 0.875),
                source
            );

            PantographContactGeometry.WireSegment segment =
                CatenaryContactDetector.toLocalSegment(anchor, collision);

            assertEquals(new Vector3d(1.125, 1.25, -0.625), segment.start(),
                "anchor=" + anchorCoordinate);
            assertEquals(new Vector3d(1.625, 1.75, -0.125), segment.end(),
                "anchor=" + anchorCoordinate);
            assertSame(source, segment.source());
        }
    }

    @Test
    void missingCollisionSourceReturnsNoContactWithoutThrowing() {
        PantographContactGeometry.ContactResult result = assertDoesNotThrow(() -> findContact(null));

        assertEquals(PantographContactGeometry.ContactResult.none(), result);
    }

    @Test
    void missingCollisionDataReturnsNoContactWithoutThrowing() {
        CollisionSource source = pos -> null;

        PantographContactGeometry.ContactResult result = assertDoesNotThrow(() -> findContact(source));

        assertEquals(PantographContactGeometry.ContactResult.none(), result);
    }

    @Test
    void productionAdapterBoundsNonTerminatingCollisionIterable() {
        BlockPos block = new BlockPos(0, 1, 0);
        AtomicInteger consumed = new AtomicInteger();
        WireBlockCollision repeated = new WireBlockCollision(
            block,
            new Vector3f(0.5f, 0, 0),
            new Vector3f(0.5f, 0, 1)
        );
        Iterable<WireBlockCollision> nonTerminating = () -> new Iterator<>() {
            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public WireBlockCollision next() {
                if (consumed.get() >= 2_000) {
                    throw new AssertionError("adapter consumed an effectively non-terminating iterable");
                }
                consumed.incrementAndGet();
                return repeated;
            }
        };
        CollisionSource source = CatenaryContactDetector.adaptCollisionLookup(pos -> nonTerminating);

        PantographContactGeometry.ContactResult result = assertTimeoutPreemptively(
            Duration.ofSeconds(1),
            () -> findContact(source)
        );

        assertEquals(PantographContactGeometry.ContactResult.none(), result);
        assertEquals(
            CatenaryContactDetector.MAX_COLLISIONS_PER_BLOCK,
            consumed.get(),
            "adapter must not consume beyond the per-block collision budget"
        );
    }

    @Test
    void productionAdapterAcceptsExactBudgetsAndRejectsFirstOverLimitEntry() {
        BlockPos block = new BlockPos(0, 1, 0);
        int limit = (int) CatenaryContactDetector.MAX_COLLISIONS_PER_BLOCK;
        CollisionSource atLimit = CatenaryContactDetector.adaptCollisionLookup(
            pos -> wireCollisionWithRepeatedBlocks(block, limit)
        );
        CollisionSource overLimit = CatenaryContactDetector.adaptCollisionLookup(
            pos -> wireCollisionWithRepeatedBlocks(block, limit + 1)
        );

        assertEquals(limit, iterableSize(atLimit.collisionsInBlock(block)));
        assertTrue(overLimit.collisionsInBlock(block) == null);
    }

    @Test
    void productionAdapterFailsClosedForNonFiniteEndpoint() {
        BlockPos block = new BlockPos(0, 1, 0);
        WireBlockCollision malformed = new WireBlockCollision(
            block,
            new Vector3f(0.5f, Float.NaN, 0),
            new Vector3f(0.5f, 0, 1)
        );
        CollisionSource source = CatenaryContactDetector.adaptCollisionLookup(
            pos -> pos.equals(block) ? List.of(malformed) : List.of()
        );

        PantographContactGeometry.ContactResult result = assertDoesNotThrow(
            () -> findContact(source)
        );

        assertEquals(PantographContactGeometry.ContactResult.none(), result);
    }

    @Test
    void collisionWithoutStableIdentityReturnsNoContact() {
        BlockPos block = new BlockPos(0, 1, 0);
        CollisionSource source = pos -> pos.equals(block)
            ? List.of(collision(null, new Object(), block, 0))
            : List.of();

        PantographContactGeometry.ContactResult result = findContact(source);

        assertEquals(PantographContactGeometry.ContactResult.none(), result);
    }

    @Test
    void injectableSideSelectorRoutesClientAndServerSources() {
        BlockPos clientBlock = new BlockPos(0, 1, 0);
        BlockPos serverBlock = new BlockPos(0, 2, 0);
        Object clientSourceIdentity = new Object();
        Object serverSourceIdentity = new Object();
        CollisionSource clientSource = pos -> pos.equals(clientBlock)
            ? List.of(collision("client", clientSourceIdentity, clientBlock, 0))
            : List.of();
        CollisionSource serverSource = pos -> pos.equals(serverBlock)
            ? List.of(collision("server", serverSourceIdentity, serverBlock, 0))
            : List.of();
        CollisionSourceSelector selector = clientSide -> clientSide ? clientSource : serverSource;

        PantographContactGeometry.ContactResult clientResult = findContact(true, selector);
        PantographContactGeometry.ContactResult serverResult = findContact(false, selector);

        assertSame(clientSourceIdentity, clientResult.winningSegment().source());
        assertSame(serverSourceIdentity, serverResult.winningSegment().source());
    }

    @Test
    void invalidSweepsDoNotInvokeCollisionSourceSelector() {
        Vector3d position = new Vector3d(0.5, 0.25, 0.5);
        Vector3d validUp = new Vector3d(0, PantographContactGeometry.MAX_HEIGHT, 0);
        Vector3d validRight = new Vector3d(PantographContactGeometry.MAX_WIDTH / 2, 0, 0);
        List<Vector3d[]> invalidSweeps = List.of(
            new Vector3d[] {
                position,
                new Vector3d(0, Double.NaN, 0),
                validRight
            },
            new Vector3d[] {
                position,
                new Vector3d(1, 0, 0),
                new Vector3d(1, 0, 0)
            },
            new Vector3d[] {
                new Vector3d(Level.MAX_LEVEL_SIZE, 64.25, 0.5),
                validUp,
                validRight
            },
            new Vector3d[] {
                position,
                new Vector3d(0, 1_000, 0),
                validRight
            },
            new Vector3d[] {
                position,
                new Vector3d(0, Double.MAX_VALUE, 0),
                validRight
            }
        );

        for (Vector3d[] sweep : invalidSweeps) {
            AtomicInteger selections = new AtomicInteger();
            CollisionSourceSelector selector = clientSide -> {
                selections.incrementAndGet();
                return pos -> List.of();
            };

            PantographContactGeometry.ContactResult result = CatenaryContactDetector.findContact(
                sweep[0],
                sweep[1],
                sweep[2],
                false,
                selector
            );

            assertEquals(PantographContactGeometry.ContactResult.none(), result);
            assertEquals(0, selections.get(), "invalid sweep index=" + invalidSweeps.indexOf(sweep));
        }
    }

    @Test
    void unavailableSelectedSourceReturnsNoContactWithoutThrowing() {
        CollisionSourceSelector selector = clientSide -> {
            throw new IllegalStateException("network unavailable");
        };

        PantographContactGeometry.ContactResult result = assertDoesNotThrow(
            () -> findContact(false, selector)
        );

        assertEquals(PantographContactGeometry.ContactResult.none(), result);
    }

    @Test
    void unavailableCollisionDataLookupReturnsNoContactWithoutThrowing() {
        CollisionSource source = pos -> {
            throw new IllegalStateException("chunk data unavailable");
        };

        PantographContactGeometry.ContactResult result = assertDoesNotThrow(() -> findContact(source));

        assertEquals(PantographContactGeometry.ContactResult.none(), result);
    }

    @Test
    void queriesOnlyBlocksIntersectedByTheCollectorSweep() {
        List<BlockPos> queried = new ArrayList<>();
        CollisionSource source = pos -> {
            queried.add(pos);
            return List.of();
        };

        CatenaryContactDetector.findContact(
            new Vector3d(0.5, 0.25, 0.5),
            new Vector3d(1, 3, 0),
            new Vector3d(1, 0, 0),
            source
        );

        assertFalse(queried.contains(new BlockPos(-1, 3, 0)));
        assertTrue(queried.contains(new BlockPos(1, 1, 0)));
    }

    @Test
    void nonExactPowerOfTwoAxisNormalizationIsRejectedConservatively() {
        Vector3d exactlyScalable = new Vector3d(0x1.0p18, 0x1.0p-1056, 0);
        Vector3d roundedSubnormal = new Vector3d(0x1.0p18, 0x1.8p-1056, 0);

        assertTrue(CatenaryContactDetector.axisCanBeNormalizedExactly(exactlyScalable));
        assertFalse(CatenaryContactDetector.axisCanBeNormalizedExactly(roundedSubnormal));
    }

    @Test
    void wellSeparatedTriangleSatUsesOnlyFilteredFastPath() {
        CatenaryContactDetector.SatDiagnostics diagnostics =
            CatenaryContactDetector.triangleIntersectsBlockWithDiagnostics(
                new Vector3d(3, 0.25, 0.25),
                new Vector3d(3, 0.75, 0.25),
                new Vector3d(3, 0.25, 0.75),
                new BlockPos(0, 0, 0)
            );

        assertFalse(diagnostics.intersects());
        assertTrue(diagnostics.fastSignCount() > 0);
        assertEquals(0, diagnostics.exactSignCount());
    }

    @Test
    void exactClosedTriangleFaceUsesExactPredicateFallback() {
        CatenaryContactDetector.SatDiagnostics diagnostics =
            CatenaryContactDetector.triangleIntersectsBlockWithDiagnostics(
                new Vector3d(1, 0.25, 0.25),
                new Vector3d(1, 0.75, 0.25),
                new Vector3d(1, 0.25, 0.75),
                new BlockPos(0, 0, 0)
            );

        assertTrue(diagnostics.intersects());
        assertTrue(diagnostics.exactSignCount() > 0);

        double outsideX = Math.nextUp(1.0);
        CatenaryContactDetector.SatDiagnostics outside =
            CatenaryContactDetector.triangleIntersectsBlockWithDiagnostics(
                new Vector3d(outsideX, 0.25, 0.25),
                new Vector3d(outsideX, 0.75, 0.25),
                new Vector3d(outsideX, 0.25, 0.75),
                new BlockPos(0, 0, 0)
            );

        assertFalse(outside.intersects(), "one representable step outside must stay separated");
        assertTrue(outside.exactSignCount() > 0);
    }

    @Test
    void exactWorldLimitCollectorBaseCornerIsNotOmitted() {
        Vector3d collectorBase = new Vector3d(
            -29_999_984.0,
            83.792865141819,
            29_999_981.0
        );
        double yaw = 2.2;
        Vector3d right = new Vector3d(
            PantographContactGeometry.MAX_WIDTH / 2 * Math.cos(yaw),
            0,
            PantographContactGeometry.MAX_WIDTH / 2 * Math.sin(yaw)
        );
        BlockPos touchingCorner = new BlockPos(-29_999_985, 83, 29_999_980);

        List<BlockPos> queried = queriedBlocks(
            collectorBase,
            new Vector3d(0, PantographContactGeometry.MAX_HEIGHT, 0),
            right
        );

        assertTrue(queried.contains(touchingCorner), "exact closed corner must be queried");
    }

    @Test
    void seededExactWorldLimitTriangleBoundariesAreNeverOmitted() {
        Random random = new Random(0x6A09_E667_F3BC_C909L);
        Vector3d up = new Vector3d(0, PantographContactGeometry.MAX_HEIGHT, 0);

        for (int sample = 0; sample < 96; sample++) {
            int blockX = (sample & 1) == 0
                ? Level.MAX_LEVEL_SIZE - 24 - random.nextInt(8)
                : -Level.MAX_LEVEL_SIZE + 16 + random.nextInt(8);
            int blockZ = (sample & 2) == 0
                ? Level.MAX_LEVEL_SIZE - 24 - random.nextInt(8)
                : -Level.MAX_LEVEL_SIZE + 16 + random.nextInt(8);
            int blockY = 64 + random.nextInt(32);
            BlockPos touchingBlock = new BlockPos(blockX, blockY, blockZ);
            Vector3d touchingCorner = new Vector3d(
                blockX + ((sample & 4) == 0 ? 0.0 : 1.0),
                blockY + 0.125 * (1 + random.nextInt(7)),
                blockZ + ((sample & 8) == 0 ? 0.0 : 1.0)
            );
            double yaw = -Math.PI + 2 * Math.PI * random.nextDouble();
            Vector3d right = new Vector3d(
                PantographContactGeometry.MAX_WIDTH / 2 * Math.cos(yaw),
                0,
                PantographContactGeometry.MAX_WIDTH / 2 * Math.sin(yaw)
            );

            // The exact point is respectively on triangle 1's base edge,
            // triangle 2's upper edge, and their shared diagonal.
            for (double upFraction : new double[] { 0.0, 1.0, 0.5 }) {
                Vector3d collectorBase = new Vector3d(touchingCorner)
                    .sub(new Vector3d(up).mul(upFraction));
                Vector3d left = new Vector3d(collectorBase).sub(right);
                Vector3d rightPoint = new Vector3d(collectorBase).add(right);
                Vector3d upperLeft = new Vector3d(left).add(up);
                Vector3d upperRight = new Vector3d(rightPoint).add(up);
                List<Vector3d[]> touchingTriangles = new ArrayList<>();
                if (upFraction != 1.0) {
                    touchingTriangles.add(new Vector3d[] { left, rightPoint, upperLeft });
                }
                if (upFraction != 0.0) {
                    touchingTriangles.add(new Vector3d[] { upperLeft, rightPoint, upperRight });
                }
                for (Vector3d[] triangle : touchingTriangles) {
                    assertTrue(
                        CatenaryContactDetector.triangleIntersectsBlockWithDiagnostics(
                            triangle[0], triangle[1], triangle[2], touchingBlock
                        ).intersects(),
                        "direct triangle sample=" + sample + ", upFraction=" + upFraction
                    );
                    assertTrue(
                        CatenaryContactDetector.triangleIntersectsBlockWithDiagnostics(
                            triangle[2], triangle[1], triangle[0], touchingBlock
                        ).intersects(),
                        "reversed triangle sample=" + sample + ", upFraction=" + upFraction
                    );
                }

                List<BlockPos> queried = queriedBlocks(collectorBase, up, right);

                assertTrue(
                    queried.contains(touchingBlock),
                    "sample=" + sample + ", upFraction=" + upFraction
                        + ", yaw=" + yaw + ", block=" + touchingBlock
                );
            }
        }
    }

    @Test
    void worldScaleSatDoesNotFalselyIncludeMirroredSeparatedBlocks() {
        Vector3d positivePosition = new Vector3d(
            29_999_990.16135967,
            1.365370392240671,
            0.5
        );
        Vector3d positiveRight = new Vector3d(
            -0.504987320957739,
            -1.143454330383127,
            0
        );
        Vector3d positiveUp = new Vector3d(
            -3.293148471503406,
            1.4543634843582884,
            0
        );
        assertSeparatedAdjacentBlockIsNotQueried(
            positivePosition,
            positiveUp,
            positiveRight,
            new BlockPos(29_999_990, 0, 0)
        );
        assertSeparatedAdjacentBlockIsNotQueried(
            new Vector3d(-positivePosition.x, positivePosition.y, positivePosition.z),
            new Vector3d(-positiveUp.x, positiveUp.y, positiveUp.z),
            new Vector3d(-positiveRight.x, positiveRight.y, positiveRight.z),
            new BlockPos(-29_999_991, 0, 0)
        );
    }

    @Test
    void worldLimitMinimumFacesPreserveExactNextUpAndNextDownPolicy() {
        for (int sign : new int[] { -1, 1 }) {
            double exactFace = sign * (Level.MAX_LEVEL_SIZE - 5.0);
            int adjacentX = (int) exactFace - 1;

            assertTrue(queriedBlocksForMinimumXFace(exactFace).stream()
                .anyMatch(pos -> pos.getX() == adjacentX), "exact face=" + exactFace);
            assertTrue(queriedBlocksForMinimumXFace(Math.nextDown(exactFace)).stream()
                .anyMatch(pos -> pos.getX() == adjacentX), "nextDown face=" + exactFace);
            assertFalse(queriedBlocksForMinimumXFace(Math.nextUp(exactFace)).stream()
                .anyMatch(pos -> pos.getX() == adjacentX), "nextUp face=" + exactFace);
        }
    }

    @Test
    void worldLimitMaximumFacesPreserveExactNextUpAndNextDownPolicy() {
        for (int sign : new int[] { -1, 1 }) {
            double exactFace = sign * (Level.MAX_LEVEL_SIZE - 5.0);
            int adjacentX = (int) exactFace;

            assertTrue(queriedBlocksForMaximumXFace(exactFace).stream()
                .anyMatch(pos -> pos.getX() == adjacentX), "exact face=" + exactFace);
            assertTrue(queriedBlocksForMaximumXFace(Math.nextUp(exactFace)).stream()
                .anyMatch(pos -> pos.getX() == adjacentX), "nextUp face=" + exactFace);
            assertFalse(queriedBlocksForMaximumXFace(Math.nextDown(exactFace)).stream()
                .anyMatch(pos -> pos.getX() == adjacentX), "nextDown face=" + exactFace);
        }
    }

    @Test
    void exactCollectorMinimumXFaceQueriesLowerAdjacentBlock() {
        BlockPos lowerAdjacent = new BlockPos(-1, 1, 0);
        Object collisionSource = new Object();
        List<BlockPos> queried = new ArrayList<>();
        CollisionSource source = pos -> {
            queried.add(pos);
            return pos.equals(lowerAdjacent)
                ? List.of(new CollisionData(
                    "x-min-face",
                    lowerAdjacent,
                    new Vector3d(1, 0, 0),
                    new Vector3d(1, 0, 1),
                    collisionSource
                ))
                : List.of();
        };

        PantographContactGeometry.ContactResult result = CatenaryContactDetector.findContact(
            new Vector3d(PantographContactGeometry.MAX_WIDTH / 2, 0.25, 0.5),
            new Vector3d(0, PantographContactGeometry.MAX_HEIGHT, 0),
            new Vector3d(PantographContactGeometry.MAX_WIDTH / 2, 0, 0),
            source
        );

        assertTrue(result.touching());
        assertSame(collisionSource, result.winningSegment().source());
        assertTrue(queried.contains(lowerAdjacent));
        assertEquals(queried.stream().distinct().count(), queried.size());
    }

    @Test
    void exactCollectorMinimumYFaceQueriesLowerAdjacentBlock() {
        BlockPos lowerAdjacent = new BlockPos(0, 0, 0);
        Object collisionSource = new Object();
        List<BlockPos> queried = new ArrayList<>();
        CollisionSource source = pos -> {
            queried.add(pos);
            return pos.equals(lowerAdjacent)
                ? List.of(new CollisionData(
                    "y-min-face",
                    lowerAdjacent,
                    new Vector3d(0.5, 1, 0),
                    new Vector3d(0.5, 1, 1),
                    collisionSource
                ))
                : List.of();
        };

        PantographContactGeometry.ContactResult result = CatenaryContactDetector.findContact(
            new Vector3d(0.5, 1, 0.5),
            new Vector3d(0, PantographContactGeometry.MAX_HEIGHT, 0),
            new Vector3d(PantographContactGeometry.MAX_WIDTH / 2, 0, 0),
            source
        );

        assertTrue(result.touching());
        assertSame(collisionSource, result.winningSegment().source());
        assertTrue(queried.contains(lowerAdjacent));
        assertEquals(queried.stream().distinct().count(), queried.size());
    }

    @Test
    void extremeFiniteSweepVectorFailsClosedPromptlyBeforeLookup() {
        List<BlockPos> queried = new ArrayList<>();
        CollisionSource source = pos -> {
            queried.add(pos);
            return List.of();
        };

        PantographContactGeometry.ContactResult result = assertTimeoutPreemptively(
            Duration.ofSeconds(1),
            () -> CatenaryContactDetector.findContact(
                new Vector3d(0.5, 0.25, 0.5),
                new Vector3d(0, Double.MAX_VALUE, 0),
                new Vector3d(PantographContactGeometry.MAX_WIDTH / 2, 0, 0),
                source
            )
        );

        assertEquals(PantographContactGeometry.ContactResult.none(), result);
        assertTrue(queried.isEmpty());
    }

    @Test
    void oversizedFiniteSweepFailsClosedBeforeLookup() {
        List<BlockPos> queried = new ArrayList<>();
        CollisionSource source = pos -> {
            queried.add(pos);
            return List.of();
        };

        PantographContactGeometry.ContactResult result = assertTimeoutPreemptively(
            Duration.ofSeconds(1),
            () -> CatenaryContactDetector.findContact(
                new Vector3d(0.5, 0.25, 0.5),
                new Vector3d(0, 1_000, 0),
                new Vector3d(PantographContactGeometry.MAX_WIDTH / 2, 0, 0),
                source
            )
        );

        assertEquals(PantographContactGeometry.ContactResult.none(), result);
        assertTrue(queried.isEmpty());
    }

    @Test
    void unsupportedHorizontalCoordinateFailsClosedPromptlyBeforeLookup() {
        List<BlockPos> queried = new ArrayList<>();
        CollisionSource source = pos -> {
            queried.add(pos);
            return List.of();
        };

        PantographContactGeometry.ContactResult result = assertTimeoutPreemptively(
            Duration.ofSeconds(1),
            () -> CatenaryContactDetector.findContact(
                new Vector3d(Integer.MAX_VALUE, 64.25, 0.5),
                new Vector3d(0, PantographContactGeometry.MAX_HEIGHT, 0),
                new Vector3d(PantographContactGeometry.MAX_WIDTH / 2, 0, 0),
                source
            )
        );

        assertEquals(PantographContactGeometry.ContactResult.none(), result);
        assertTrue(queried.isEmpty());
    }

    @Test
    void detectorDoesNotMutateCallerVectors() {
        Vector3d worldPosition = new Vector3d(0.5, 0.25, 0.5);
        Vector3d up = new Vector3d(0, PantographContactGeometry.MAX_HEIGHT, 0);
        Vector3d right = new Vector3d(PantographContactGeometry.MAX_WIDTH / 2, 0, 0);
        Vector3d in = new Vector3d(0.5, 0, 0);
        Vector3d out = new Vector3d(0.5, 0, 1);
        Vector3d worldPositionBefore = new Vector3d(worldPosition);
        Vector3d upBefore = new Vector3d(up);
        Vector3d rightBefore = new Vector3d(right);
        Vector3d inBefore = new Vector3d(in);
        Vector3d outBefore = new Vector3d(out);
        BlockPos block = new BlockPos(0, 1, 0);
        CollisionSource source = pos -> pos.equals(block)
            ? List.of(new CollisionData("wire", block, in, out, new Object()))
            : List.of();

        CatenaryContactDetector.findContact(worldPosition, up, right, source);

        assertEquals(worldPositionBefore, worldPosition);
        assertEquals(upBefore, up);
        assertEquals(rightBefore, right);
        assertEquals(inBefore, in);
        assertEquals(outBefore, out);
    }

    private static List<BlockPos> queriedBlocksForMinimumXFace(double face) {
        return queriedBlocks(
            new Vector3d(face + PantographContactGeometry.MAX_WIDTH / 2, 0.25, 0.5),
            new Vector3d(0, PantographContactGeometry.MAX_HEIGHT, 0),
            new Vector3d(PantographContactGeometry.MAX_WIDTH / 2, 0, 0)
        );
    }

    private static List<BlockPos> queriedBlocksForMaximumXFace(double face) {
        return queriedBlocks(
            new Vector3d(face - PantographContactGeometry.MAX_WIDTH / 2, 0.25, 0.5),
            new Vector3d(0, PantographContactGeometry.MAX_HEIGHT, 0),
            new Vector3d(PantographContactGeometry.MAX_WIDTH / 2, 0, 0)
        );
    }

    private static List<BlockPos> queriedBlocks(
        Vector3d position,
        Vector3d up,
        Vector3d right
    ) {
        List<BlockPos> queried = new ArrayList<>();
        CatenaryContactDetector.findContact(position, up, right, pos -> {
            queried.add(pos);
            return List.of();
        });
        assertEquals(queried.stream().distinct().count(), queried.size());
        return queried;
    }

    private static void assertSeparatedAdjacentBlockIsNotQueried(
        Vector3d position,
        Vector3d up,
        Vector3d right,
        BlockPos separatedBlock
    ) {
        List<BlockPos> queried = new ArrayList<>();
        CollisionSource source = pos -> {
            queried.add(pos);
            return List.of();
        };

        CatenaryContactDetector.findContact(position, up, right, source);

        assertFalse(queried.contains(separatedBlock), "separated block=" + separatedBlock);
        assertEquals(queried.stream().distinct().count(), queried.size());
    }

    private static List<WireBlockCollision> wireCollisionWithRepeatedBlocks(BlockPos block, int count) {
        WireBlockCollision repeated = new WireBlockCollision(
            block,
            new Vector3f(0.5f, 0, 0),
            new Vector3f(0.5f, 0, 1)
        );
        return Collections.nCopies(count, repeated);
    }

    private static int iterableSize(Iterable<?> iterable) {
        if (iterable == null) {
            return 0;
        }
        int size = 0;
        for (Object ignored : iterable) {
            size++;
        }
        return size;
    }

    private static byte[] detectorClassBytes() throws IOException {
        try (InputStream stream = CatenaryContactDetector.class
            .getResourceAsStream("CatenaryContactDetector.class")) {
            assertTrue(stream != null, "compiled detector class should be available");
            return stream.readAllBytes();
        }
    }

    private static PantographContactGeometry.ContactResult findContact(
        boolean clientSide,
        CollisionSourceSelector selector
    ) {
        return CatenaryContactDetector.findContact(
            new Vector3d(0.5, 0.25, 0.5),
            new Vector3d(0, PantographContactGeometry.MAX_HEIGHT, 0),
            new Vector3d(PantographContactGeometry.MAX_WIDTH / 2, 0, 0),
            clientSide,
            selector
        );
    }

    private static PantographContactGeometry.ContactResult findContact(CollisionSource source) {
        return CatenaryContactDetector.findContact(
            new Vector3d(0.5, 0.25, 0.5),
            new Vector3d(0, PantographContactGeometry.MAX_HEIGHT, 0),
            new Vector3d(PantographContactGeometry.MAX_WIDTH / 2, 0, 0),
            source
        );
    }

    private static CollisionData collision(
        Object identity,
        Object source,
        BlockPos blockPos,
        double relativeY
    ) {
        return new CollisionData(
            identity,
            blockPos,
            new Vector3d(0.5, relativeY, 0),
            new Vector3d(0.5, relativeY, 1),
            source
        );
    }
}
