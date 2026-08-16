package de.mrjulsen.paw.traction;

import java.math.BigDecimal;
import java.math.MathContext;

import org.joml.Vector3d;

/** Pure, collector-local geometry used to find catenary contact with a pantograph collector. */
public final class PantographContactGeometry {
    public static final double MAX_HEIGHT = 3.6D;
    public static final double MAX_WIDTH = 2.5D;

    /**
     * Domain policy for the collector's two-axis frame. The estimate is the
     * 2-norm condition of the normalized right/up basis; more ill-conditioned
     * frames cannot reliably resolve collector boundaries and fail closed.
     */
    public static final double MAX_FRAME_CONDITION = 1.0e4;

    private static final double UNIT_ROUNDOFF = 0x1.0p-53;
    private static final double DETERMINANT_GAMMA =
        24 * UNIT_ROUNDOFF / (1 - 24 * UNIT_ROUNDOFF);
    private static final MathContext QUOTIENT_CONTEXT = MathContext.DECIMAL128;
    // Subnormal axes cannot preserve relative direction through ordinary JVM arithmetic.
    private static final double MIN_NORMAL_AXIS_COMPONENT = Double.MIN_NORMAL;

    private PantographContactGeometry() {}

    /**
     * Solves {@code Q + tD = sR + hU} directly in collector-local coordinates.
     * The collector base and every wire endpoint must share the same nearby
     * anchor. Contact is the exact closed domain s in [-1,1], h/t in [0,1].
     */
    public static ContactResult findContact(
        Vector3d collectorBase,
        Vector3d upVector,
        Vector3d rightVector,
        Iterable<WireSegment> segments
    ) {
        return findContact(collectorBase, upVector, rightVector, segments, new ExactUsage());
    }

    static ContactDiagnostics findContactWithDiagnostics(
        Vector3d collectorBase,
        Vector3d upVector,
        Vector3d rightVector,
        Iterable<WireSegment> segments
    ) {
        ExactUsage exactUsage = new ExactUsage();
        ContactResult result = findContact(collectorBase, upVector, rightVector, segments, exactUsage);
        return new ContactDiagnostics(result, exactUsage.used());
    }

    private static ContactResult findContact(
        Vector3d collectorBase,
        Vector3d upVector,
        Vector3d rightVector,
        Iterable<WireSegment> segments,
        ExactUsage exactUsage
    ) {
        Vec3 base = Vec3.snapshot(collectorBase);
        Vec3 up = Vec3.snapshot(upVector);
        Vec3 right = Vec3.snapshot(rightVector);
        if (!base.isFinite() || !isValidFrame(up, right) || segments == null) {
            return ContactResult.none();
        }

        double nearestHeightParameter = 1;
        WireSegment winningSegment = null;
        RobustQuotient winningHeight = null;
        for (WireSegment segment : segments) {
            if (segment == null || !segment.isFinite() || segment.isZeroLength()) {
                return ContactResult.none();
            }

            Intersection intersection = intersect(base, up, right, segment, exactUsage);
            if (intersection.invalid()) {
                return ContactResult.none();
            }
            if (!intersection.touching()) {
                continue;
            }

            double heightParameter = intersection.heightParameter();
            boolean isNearest = winningSegment == null
                || intersection.height().isStrictlyLessThan(winningHeight, exactUsage);
            if (exactUsage.invalid()) {
                return ContactResult.none();
            }
            if (isNearest) {
                nearestHeightParameter = heightParameter;
                winningSegment = segment;
                winningHeight = intersection.height();
            }
        }

        if (winningSegment == null) {
            return ContactResult.none();
        }
        return new ContactResult(true, nearestHeightParameter * MAX_HEIGHT, winningSegment);
    }

    /** Validates a frame using the same fail-closed policy as {@link #findContact}. */
    public static boolean isValidCollectorFrame(Vector3d upVector, Vector3d rightVector) {
        return isValidFrame(Vec3.snapshot(upVector), Vec3.snapshot(rightVector));
    }

    private static boolean isValidFrame(Vec3 up, Vec3 right) {
        if (!up.isFinite() || !right.isFinite()
            || up.maxAbsComponent() < MIN_NORMAL_AXIS_COMPONENT
            || right.maxAbsComponent() < MIN_NORMAL_AXIS_COMPONENT) {
            return false;
        }

        double upLength = up.norm();
        double rightLength = right.norm();
        if (!Double.isFinite(upLength) || !Double.isFinite(rightLength)
            || upLength == 0 || rightLength == 0) {
            return false;
        }

        double cosine = Math.abs(
            (up.x() / upLength) * (right.x() / rightLength)
                + (up.y() / upLength) * (right.y() / rightLength)
                + (up.z() / upLength) * (right.z() / rightLength)
        );
        double conditionSquared = MAX_FRAME_CONDITION * MAX_FRAME_CONDITION;
        double maximumCosine = (conditionSquared - 1) / (conditionSquared + 1);
        return Double.isFinite(cosine) && cosine <= maximumCosine;
    }

    private static Intersection intersect(
        Vec3 base,
        Vec3 up,
        Vec3 right,
        WireSegment segment,
        ExactUsage exactUsage
    ) {
        Vec3 start = segment.startSnapshot();
        Vec3 direction = segment.endSnapshot().subtract(start);
        Vec3 q = start.subtract(base);
        if (!direction.isFinite()
            || direction.maxAbsComponent() < MIN_NORMAL_AXIS_COMPONENT
            || !q.isFinite()) {
            return Intersection.invalidResult();
        }

        DeterminantVectors vectors = DeterminantVectors.scaled(right, up, direction, q);
        if (vectors == null) {
            return Intersection.invalidResult();
        }
        right = vectors.right();
        up = vectors.up();
        direction = vectors.direction();
        q = vectors.q();

        int exactUsageCheckpoint = exactUsage.fallbackCount();

        // Cramer's rule for Q + tD = sR + hU. Every vector has the same
        // exact power-of-two scale, so every determinant has the same cubic scale.
        Determinant denominator = Determinant.of(right, up, direction);
        int denominatorSign = denominator.sign(exactUsage);
        if (denominatorSign == 0) {
            return Intersection.invalidResult();
        }

        Determinant widthNumerator = Determinant.of(q, up, direction);
        Determinant heightNumerator = Determinant.of(right, q, direction);
        Determinant wireNumerator = Determinant.of(right, up, q).negated();

        boolean insideCollector = insideMinusOneToOne(
            widthNumerator,
            denominator,
            denominatorSign,
            exactUsage
        ) && insideZeroToOne(heightNumerator, denominator, denominatorSign, exactUsage)
            && insideZeroToOne(wireNumerator, denominator, denominatorSign, exactUsage);
        if (exactUsage.invalid()) {
            return Intersection.invalidResult();
        }
        if (!insideCollector) {
            return Intersection.miss();
        }

        boolean exactNeeded = exactUsage.fallbackCount() > exactUsageCheckpoint;
        double heightParameter = quotient(heightNumerator, denominator, exactNeeded);
        if (!Double.isFinite(heightParameter)) {
            return Intersection.invalidResult();
        }

        // Clamping is reporting only: exact predicates above have already proved
        // that the binary-double geometry lies in the closed collector domain.
        heightParameter = Math.max(0, Math.min(1, heightParameter));
        return Intersection.hit(
            heightParameter,
            new RobustQuotient(heightNumerator, denominator, denominatorSign)
        );
    }

    private static boolean insideZeroToOne(
        Determinant numerator,
        Determinant denominator,
        int denominatorSign,
        ExactUsage exactUsage
    ) {
        int lowerSign = numerator.sign(exactUsage);
        int upperSign = denominator.minus(numerator).sign(exactUsage);
        return lowerSign * denominatorSign >= 0 && upperSign * denominatorSign >= 0;
    }

    private static boolean insideMinusOneToOne(
        Determinant numerator,
        Determinant denominator,
        int denominatorSign,
        ExactUsage exactUsage
    ) {
        int lowerSign = numerator.plus(denominator).sign(exactUsage);
        int upperSign = denominator.minus(numerator).sign(exactUsage);
        return lowerSign * denominatorSign >= 0 && upperSign * denominatorSign >= 0;
    }

    private static double quotient(Determinant numerator, Determinant denominator, boolean exactNeeded) {
        double quotient = numerator.estimate() / denominator.estimate();
        if (!exactNeeded) {
            return Double.isFinite(quotient) ? quotient : Double.NaN;
        }
        return numerator.exact()
            .divide(denominator.exact(), QUOTIENT_CONTEXT)
            .doubleValue();
    }

    public record ContactResult(boolean touching, double height, WireSegment winningSegment) {
        public static ContactResult none() {
            return new ContactResult(false, 0, null);
        }
    }

    record ContactDiagnostics(ContactResult result, boolean exactFallbackUsed) {}

    /** Immutable primitive snapshot of a wire segment in collector-local coordinates. */
    public static final class WireSegment {
        private final double startX;
        private final double startY;
        private final double startZ;
        private final double endX;
        private final double endY;
        private final double endZ;
        private final Object source;

        public WireSegment(Vector3d start, Vector3d end) {
            this(start, end, null);
        }

        public WireSegment(Vector3d start, Vector3d end, Object source) {
            this(start.x, start.y, start.z, end.x, end.y, end.z, source);
        }

        public WireSegment(
            double startX,
            double startY,
            double startZ,
            double endX,
            double endY,
            double endZ
        ) {
            this(startX, startY, startZ, endX, endY, endZ, null);
        }

        public WireSegment(
            double startX,
            double startY,
            double startZ,
            double endX,
            double endY,
            double endZ,
            Object source
        ) {
            this.startX = startX;
            this.startY = startY;
            this.startZ = startZ;
            this.endX = endX;
            this.endY = endY;
            this.endZ = endZ;
            this.source = source;
        }

        public double startX() { return startX; }
        public double startY() { return startY; }
        public double startZ() { return startZ; }
        public double endX() { return endX; }
        public double endY() { return endY; }
        public double endZ() { return endZ; }
        public Object source() { return source; }

        /** Returns a copy; mutable vectors are never retained or exposed. */
        public Vector3d start() {
            return new Vector3d(startX, startY, startZ);
        }

        /** Returns a copy; mutable vectors are never retained or exposed. */
        public Vector3d end() {
            return new Vector3d(endX, endY, endZ);
        }

        private Vec3 startSnapshot() {
            return new Vec3(startX, startY, startZ);
        }

        private Vec3 endSnapshot() {
            return new Vec3(endX, endY, endZ);
        }

        private boolean isFinite() {
            return startSnapshot().isFinite() && endSnapshot().isFinite();
        }

        private boolean isZeroLength() {
            return startX == endX && startY == endY && startZ == endZ;
        }
    }

    private record Vec3(double x, double y, double z) {
        private static Vec3 snapshot(Vector3d vector) {
            return vector == null
                ? new Vec3(Double.NaN, Double.NaN, Double.NaN)
                : new Vec3(vector.x, vector.y, vector.z);
        }

        private Vec3 subtract(Vec3 other) {
            return new Vec3(x - other.x, y - other.y, z - other.z);
        }

        private boolean isFinite() {
            return Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
        }

        private double maxAbsComponent() {
            return Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z)));
        }

        private double norm() {
            return Math.hypot(x, Math.hypot(y, z));
        }

        private Vec3 scaleByPowerOfTwo(int exponent) {
            return new Vec3(
                Math.scalb(x, exponent),
                Math.scalb(y, exponent),
                Math.scalb(z, exponent)
            );
        }

        private boolean hasSameBits(Vec3 other) {
            return Double.doubleToRawLongBits(x) == Double.doubleToRawLongBits(other.x)
                && Double.doubleToRawLongBits(y) == Double.doubleToRawLongBits(other.y)
                && Double.doubleToRawLongBits(z) == Double.doubleToRawLongBits(other.z);
        }
    }

    private record DeterminantVectors(Vec3 right, Vec3 up, Vec3 direction, Vec3 q) {
        private static DeterminantVectors scaled(Vec3 right, Vec3 up, Vec3 direction, Vec3 q) {
            double largestComponent = Math.max(
                Math.max(right.maxAbsComponent(), up.maxAbsComponent()),
                Math.max(direction.maxAbsComponent(), q.maxAbsComponent())
            );
            if (!Double.isFinite(largestComponent) || largestComponent == 0) {
                return null;
            }

            int scalingExponent = -Math.getExponent(largestComponent);
            Vec3 scaledRight = right.scaleByPowerOfTwo(scalingExponent);
            Vec3 scaledUp = up.scaleByPowerOfTwo(scalingExponent);
            Vec3 scaledDirection = direction.scaleByPowerOfTwo(scalingExponent);
            Vec3 scaledQ = q.scaleByPowerOfTwo(scalingExponent);
            if (!isExactNonzeroScale(right, scaledRight, scalingExponent)
                || !isExactNonzeroScale(up, scaledUp, scalingExponent)
                || !isExactNonzeroScale(direction, scaledDirection, scalingExponent)
                || !isExactScale(q, scaledQ, scalingExponent)) {
                return null;
            }
            return new DeterminantVectors(scaledRight, scaledUp, scaledDirection, scaledQ);
        }

        private static boolean isExactNonzeroScale(Vec3 original, Vec3 scaled, int exponent) {
            return scaled.maxAbsComponent() != 0 && isExactScale(original, scaled, exponent);
        }

        private static boolean isExactScale(Vec3 original, Vec3 scaled, int exponent) {
            return scaled.isFinite()
                && scaled.scaleByPowerOfTwo(-exponent).hasSameBits(original);
        }
    }

    /** Filtered scalar triple product over the actual binary-double values. */
    private static final class Determinant {
        private final Vec3 a;
        private final Vec3 b;
        private final Vec3 c;
        private final double estimate;
        private final double errorBound;
        private final Determinant left;
        private final Determinant right;
        private final int rightSign;
        private BigDecimal exact;

        private Determinant(Vec3 a, Vec3 b, Vec3 c, double estimate, double errorBound) {
            this.a = a;
            this.b = b;
            this.c = c;
            this.estimate = estimate;
            this.errorBound = errorBound;
            this.left = null;
            this.right = null;
            this.rightSign = 0;
        }

        private Determinant(Determinant left, Determinant right, int rightSign) {
            this.a = null;
            this.b = null;
            this.c = null;
            this.left = left;
            this.right = right;
            this.rightSign = rightSign;
            this.estimate = Math.fma(rightSign, right.estimate, left.estimate);
            double additionError = 2 * UNIT_ROUNDOFF
                * (Math.abs(left.estimate) + Math.abs(right.estimate));
            double combinedError = left.errorBound + right.errorBound + additionError;
            this.errorBound = Double.isFinite(combinedError)
                ? Math.nextUp(combinedError)
                : Double.NaN;
        }

        private static Determinant of(Vec3 a, Vec3 b, Vec3 c) {
            double p1 = a.x * b.y;
            double p2 = a.y * b.z;
            double p3 = a.z * b.x;
            double p4 = a.z * b.y;
            double p5 = a.y * b.x;
            double p6 = a.x * b.z;
            double t1 = p1 * c.z;
            double t2 = p2 * c.x;
            double t3 = p3 * c.y;
            double t4 = p4 * c.x;
            double t5 = p5 * c.z;
            double t6 = p6 * c.y;
            boolean productsRepresentable = isRepresentableTripleProduct(a.x, b.y, c.z, p1, t1)
                && isRepresentableTripleProduct(a.y, b.z, c.x, p2, t2)
                && isRepresentableTripleProduct(a.z, b.x, c.y, p3, t3)
                && isRepresentableTripleProduct(a.z, b.y, c.x, p4, t4)
                && isRepresentableTripleProduct(a.y, b.x, c.z, p5, t5)
                && isRepresentableTripleProduct(a.x, b.z, c.y, p6, t6);
            double estimate = Math.fma(p1, c.z,
                Math.fma(p2, c.x,
                    Math.fma(p3, c.y,
                        Math.fma(-p4, c.x,
                            Math.fma(-p5, c.z, -t6)))));
            double permanent = Math.abs(t1)
                + Math.abs(t2)
                + Math.abs(t3)
                + Math.abs(t4)
                + Math.abs(t5)
                + Math.abs(t6);
            double errorBound;
            if (!productsRepresentable || !Double.isFinite(permanent)) {
                errorBound = Double.NaN;
            } else if (permanent == 0) {
                errorBound = 0;
            } else if (permanent < Double.MIN_NORMAL) {
                errorBound = Double.NaN;
            } else {
                double expandedPermanent = Math.nextUp(permanent);
                double rawErrorBound = DETERMINANT_GAMMA * expandedPermanent;
                errorBound = Double.isFinite(rawErrorBound)
                    && rawErrorBound >= Double.MIN_NORMAL
                        ? Math.nextUp(rawErrorBound)
                        : Double.NaN;
            }
            return new Determinant(a, b, c, estimate, errorBound);
        }

        private static boolean isRepresentableTripleProduct(
            double first,
            double second,
            double third,
            double intermediate,
            double product
        ) {
            if (first == 0 || second == 0 || third == 0) {
                return product == 0;
            }
            return Double.isFinite(intermediate)
                && Math.abs(intermediate) >= Double.MIN_NORMAL
                && Double.isFinite(product)
                && Math.abs(product) >= Double.MIN_NORMAL;
        }

        private Determinant plus(Determinant other) {
            return new Determinant(this, other, 1);
        }

        private Determinant minus(Determinant other) {
            return new Determinant(this, other, -1);
        }

        private Determinant negated() {
            return Determinant.zero().minus(this);
        }

        private static Determinant zero() {
            Vec3 zero = new Vec3(0, 0, 0);
            return new Determinant(zero, zero, zero, 0, 0);
        }

        private int sign(ExactUsage exactUsage) {
            if (!Double.isFinite(estimate) || !Double.isFinite(errorBound)) {
                exactUsage.markInvalid();
                return 0;
            }
            if (Math.abs(estimate) > errorBound) {
                return estimate > 0 ? 1 : -1;
            }
            exactUsage.markFallback();
            return exact().signum();
        }

        private double estimate() {
            return estimate;
        }

        private double errorBound() {
            return errorBound;
        }

        private BigDecimal exact() {
            if (exact == null) {
                exact = left == null
                    ? exactDeterminant(a, b, c)
                    : left.exact().add(rightSign > 0 ? right.exact() : right.exact().negate());
            }
            return exact;
        }

        private static BigDecimal exactDeterminant(Vec3 a, Vec3 b, Vec3 c) {
            BigDecimal ax = new BigDecimal(a.x);
            BigDecimal ay = new BigDecimal(a.y);
            BigDecimal az = new BigDecimal(a.z);
            BigDecimal bx = new BigDecimal(b.x);
            BigDecimal by = new BigDecimal(b.y);
            BigDecimal bz = new BigDecimal(b.z);
            BigDecimal cx = new BigDecimal(c.x);
            BigDecimal cy = new BigDecimal(c.y);
            BigDecimal cz = new BigDecimal(c.z);

            return ax.multiply(by).multiply(cz)
                .add(ay.multiply(bz).multiply(cx))
                .add(az.multiply(bx).multiply(cy))
                .subtract(az.multiply(by).multiply(cx))
                .subtract(ay.multiply(bx).multiply(cz))
                .subtract(ax.multiply(bz).multiply(cy));
        }
    }

    private static final class ExactUsage {
        private int fallbackCount;
        private boolean invalid;

        private void markFallback() {
            fallbackCount++;
        }

        private int fallbackCount() {
            return fallbackCount;
        }

        private void markInvalid() {
            invalid = true;
        }

        private boolean invalid() {
            return invalid;
        }

        private boolean used() {
            return fallbackCount > 0;
        }
    }

    private record RobustQuotient(
        Determinant numerator,
        Determinant denominator,
        int denominatorSign
    ) {
        private boolean isStrictlyLessThan(RobustQuotient other, ExactUsage exactUsage) {
            NormalizedQuotient first = NormalizedQuotient.of(this);
            NormalizedQuotient second = NormalizedQuotient.of(other);
            if (first == null || second == null) {
                exactUsage.markInvalid();
                return false;
            }

            double firstProduct = first.numerator().estimate()
                * second.denominator().estimate();
            double secondProduct = second.numerator().estimate()
                * first.denominator().estimate();
            if (!isRepresentableProduct(
                    first.numerator().estimate(),
                    second.denominator().estimate(),
                    firstProduct
                ) || !isRepresentableProduct(
                    second.numerator().estimate(),
                    first.denominator().estimate(),
                    secondProduct
                )) {
                exactUsage.markInvalid();
                return false;
            }

            double difference = Math.fma(
                first.numerator().estimate(),
                second.denominator().estimate(),
                -secondProduct
            );
            double firstError = productError(
                first.numerator(),
                second.denominator(),
                firstProduct
            );
            double secondError = productError(
                second.numerator(),
                first.denominator(),
                secondProduct
            );
            double productMagnitude = addUpperBound(
                Math.abs(firstProduct),
                Math.abs(secondProduct)
            );
            double subtractionError = multiplyUpperBound(
                2 * UNIT_ROUNDOFF,
                productMagnitude
            );
            double error = addUpperBound(
                addUpperBound(firstError, secondError),
                subtractionError
            );
            double certifiedError = Double.isFinite(error) ? Math.nextUp(error) : Double.NaN;

            int differenceSign;
            if (!Double.isFinite(difference) || !Double.isFinite(certifiedError)) {
                exactUsage.markInvalid();
                return false;
            }
            if (Math.abs(difference) > certifiedError) {
                differenceSign = difference > 0 ? 1 : -1;
            } else {
                exactUsage.markFallback();
                differenceSign = numerator.exact().multiply(other.denominator.exact())
                    .subtract(other.numerator.exact().multiply(denominator.exact()))
                    .signum();
            }
            return differenceSign * denominatorSign * other.denominatorSign < 0;
        }

        private static boolean isRepresentableProduct(
            double first,
            double second,
            double product
        ) {
            if (!Double.isFinite(product)) {
                return false;
            }
            if (first == 0 || second == 0) {
                return product == 0;
            }
            return Math.abs(product) >= Double.MIN_NORMAL;
        }

        private static double productError(
            NormalizedDeterminant first,
            NormalizedDeterminant second,
            double product
        ) {
            double firstError = multiplyUpperBound(
                Math.abs(second.estimate()),
                first.errorBound()
            );
            double secondError = multiplyUpperBound(
                Math.abs(first.estimate()),
                second.errorBound()
            );
            double combinedInputError = multiplyUpperBound(
                first.errorBound(),
                second.errorBound()
            );
            double roundingError = multiplyUpperBound(
                2 * UNIT_ROUNDOFF,
                Math.abs(product)
            );
            return addUpperBound(
                addUpperBound(firstError, secondError),
                addUpperBound(combinedInputError, roundingError)
            );
        }

        private static double multiplyUpperBound(double first, double second) {
            if (!Double.isFinite(first) || !Double.isFinite(second)
                || first < 0 || second < 0) {
                return Double.NaN;
            }
            if (first == 0 || second == 0) {
                return 0;
            }
            double product = first * second;
            if (!Double.isFinite(product)) {
                return Double.NaN;
            }
            return product == 0 ? Double.MIN_VALUE : Math.nextUp(product);
        }

        private static double addUpperBound(double first, double second) {
            if (!Double.isFinite(first) || !Double.isFinite(second)
                || first < 0 || second < 0) {
                return Double.NaN;
            }
            if (first == 0) {
                return second;
            }
            if (second == 0) {
                return first;
            }
            double sum = first + second;
            return Double.isFinite(sum) ? Math.nextUp(sum) : Double.NaN;
        }
    }

    private record NormalizedQuotient(
        NormalizedDeterminant numerator,
        NormalizedDeterminant denominator
    ) {
        private static NormalizedQuotient of(RobustQuotient quotient) {
            double largestMagnitude = Math.max(
                Math.max(
                    Math.abs(quotient.numerator().estimate()),
                    quotient.numerator().errorBound()
                ),
                Math.max(
                    Math.abs(quotient.denominator().estimate()),
                    quotient.denominator().errorBound()
                )
            );
            if (!Double.isFinite(largestMagnitude) || largestMagnitude == 0) {
                return null;
            }

            int scalingExponent = -Math.getExponent(largestMagnitude);
            NormalizedDeterminant numerator = NormalizedDeterminant.scaled(
                quotient.numerator(),
                scalingExponent
            );
            NormalizedDeterminant denominator = NormalizedDeterminant.scaled(
                quotient.denominator(),
                scalingExponent
            );
            return numerator == null || denominator == null
                ? null
                : new NormalizedQuotient(numerator, denominator);
        }
    }

    private record NormalizedDeterminant(double estimate, double errorBound) {
        private static NormalizedDeterminant scaled(
            Determinant determinant,
            int exponent
        ) {
            double estimate = Math.scalb(determinant.estimate(), exponent);
            double errorBound = Math.scalb(determinant.errorBound(), exponent);
            if (!isExactScale(determinant.estimate(), estimate, exponent)
                || !isExactScale(determinant.errorBound(), errorBound, exponent)
                || errorBound < 0) {
                return null;
            }
            return new NormalizedDeterminant(estimate, errorBound);
        }

        private static boolean isExactScale(double original, double scaled, int exponent) {
            if (!Double.isFinite(original) || !Double.isFinite(scaled)
                || (original != 0 && scaled == 0)) {
                return false;
            }
            double roundTrip = Math.scalb(scaled, -exponent);
            return Double.isFinite(roundTrip)
                && Double.doubleToRawLongBits(roundTrip)
                    == Double.doubleToRawLongBits(original);
        }
    }

    private record Intersection(
        boolean touching,
        boolean invalid,
        double heightParameter,
        RobustQuotient height
    ) {
        private static Intersection hit(double heightParameter, RobustQuotient height) {
            return new Intersection(true, false, heightParameter, height);
        }

        private static Intersection miss() {
            return new Intersection(false, false, 0, null);
        }

        private static Intersection invalidResult() {
            return new Intersection(false, true, 0, null);
        }
    }
}
