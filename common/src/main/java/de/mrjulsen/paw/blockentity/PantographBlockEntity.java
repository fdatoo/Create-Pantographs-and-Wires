package de.mrjulsen.paw.blockentity;

import java.util.List;
import java.util.function.UnaryOperator;

import org.joml.Vector3d;
import org.joml.Vector3f;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.utility.AnimationTickHolder;
import com.simibubi.create.foundation.utility.animation.LerpedFloat;
import com.simibubi.create.foundation.utility.animation.LerpedFloat.Chaser;

import de.mrjulsen.paw.traction.CatenaryContactDetector;
import de.mrjulsen.paw.traction.PantographContactGeometry;
import de.mrjulsen.paw.traction.PantographContactGeometry.ContactResult;
import de.mrjulsen.paw.traction.PantographContactGeometry.WireSegment;
import de.mrjulsen.paw.util.Const;
import de.mrjulsen.wires.debug.WireDebugRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.molang.MolangParser;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.core.animation.AnimatableManager.ControllerRegistrar;
import software.bernie.geckolib.util.GeckoLibUtil;

public class PantographBlockEntity extends SmartBlockEntity implements GeoBlockEntity  {

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
	private static final RawAnimation ANIM_WIRE_CONTACT = RawAnimation.begin().thenPlayAndHold("wire_contact");
	private static final RawAnimation ANIM_EXPAND = RawAnimation.begin().thenPlay("expand").thenPlayAndHold("wire_contact");
	private static final RawAnimation ANIM_COLLAPSE = RawAnimation.begin().thenPlayAndHold("collapse");    

    public static final String NBT_EXPANDABLE = "IsExpandable";
    
    public static final double MAX_HEIGHT_PIXELS = PantographContactGeometry.MAX_HEIGHT / Const.PIXEL;
    public static final double MIN_HEIGHT_PIXELS = 13D + Const.PIXEL;
    public static final double MIN_HEIGHT = Const.PIXEL * MIN_HEIGHT_PIXELS;
    public static final double FORWARD_OFFSET = Const.PIXEL * 4;
    public static final double DELTA_HEIGHT = PantographContactGeometry.MAX_HEIGHT - MIN_HEIGHT;
    public static final double DELTA_HEIGHT_PIXELS = MAX_HEIGHT_PIXELS - MIN_HEIGHT_PIXELS;
    public static final double ARM_LENGTH = 36;
    public static final double ARM_LENGTH_DOUBLE_POW = 2 * Math.pow(ARM_LENGTH, 2);
    public static final double BASE_ANGLE = Math.toDegrees(Math.acos((ARM_LENGTH_DOUBLE_POW - Math.pow(1.5, 2)) / ARM_LENGTH_DOUBLE_POW));
    public static final double START_ANGLE = Math.toDegrees(Math.acos((ARM_LENGTH_DOUBLE_POW - Math.pow(((0.04) * DELTA_HEIGHT_PIXELS), 2)) / ARM_LENGTH_DOUBLE_POW));
    public static final Vector3d BASE_UP_VECTOR = new Vector3d(0, 1, 0).normalize().mul(PantographContactGeometry.MAX_HEIGHT);
    public static final Vector3d BASE_RIGHT_VECTOR = new Vector3d(1, 0, 0).normalize().mul(PantographContactGeometry.MAX_WIDTH / 2d);
    public static final Vector3d BASE_FORWARD_VECTOR = new Vector3d(0, 0, 1).normalize().mul(FORWARD_OFFSET);

    // Client only, unsaved
    private Vector3d currentPos;
    private UnaryOperator<Vector3d> rotationFunc = v -> v;
    private double catenaryWireHeight = DELTA_HEIGHT;
    private final LerpedFloat animationTransition = LerpedFloat.linear().startWithValue(catenaryWireHeight);
 
    // state
    private boolean expanded = false;
    private boolean stateChanged = false;
    private boolean touchingWire = false;

    // properties
    private boolean expandable = false;

    // Debug
    public Vector3f debug_wireCollisionA = new Vector3f();
    public Vector3f debug_wireCollisionB = new Vector3f();
    public double debug_hitHeight = 0;

    public PantographBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public Vector3d getCurrentPos() {
        return currentPos == null ? null : new Vector3d(currentPos);
    }

    public Vector3d rotate(Vector3d vec) {
        return rotatedSnapshot(vec, rotationFunc);
    }

    public void toggleExpandable() {
        setExpandable(!isExpandable());
    }

    public void setExpandable(boolean b) {
        this.expandable = b;
        notifyUpdate();
    }

    public boolean isExpandable() {
        return this.expandable;
    }

    protected void setExpanded(boolean b) {        
        this.stateChanged = this.expanded != b;
        this.expanded = b;
    }

    public boolean isExpanded() {
        return this.expanded;
    }

    /** Whether the collector geometry is currently touching a live wire, independent of {@link #isExpandable()}. */
    public boolean isTouchingWire() {
        return this.touchingWire;
    }


    @Override
    protected void write(CompoundTag tag, boolean clientPacket) {
        super.write(tag, clientPacket);
        tag.putBoolean(NBT_EXPANDABLE, isExpandable());
    }

    @Override
    protected void read(CompoundTag tag, boolean clientPacket) {
        super.read(tag, clientPacket);
        setExpandable(tag.getBoolean(NBT_EXPANDABLE));
    }

    @Override
    public void registerControllers(ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, state -> {
            if (state.getController().getCurrentRawAnimation() == null && isExpanded()) {
                state.setAnimation(ANIM_WIRE_CONTACT);
            }
            if (stateChanged) {
                if (isExpanded()) {
                    state.setAnimation(ANIM_EXPAND);
                } else {
                    state.setAnimation(ANIM_COLLAPSE);
                }
                stateChanged = false;
            }
            return PlayState.CONTINUE;
        })
            .setAnimationSpeed(0.25f)
        );
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }


    @Override
    public AABB getRenderBoundingBox() {
        AABB aabb = new AABB(worldPosition.offset(-2, 0, -2));
        return aabb.expandTowards(4, 4, 4);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {}


    @Override
    public void tick() {
        // As Block
        this.setExpanded(this.isExpandable());
        commonTick();
    }

    public void contraptionTick() {
        // As contraption
        commonTick();
    }

    protected void commonTick() {
        super.tick();
        animationTransition.tickChaser();
    }


    public void updateContraptionValues(Vector3d worldPos, UnaryOperator<Vector3d> rotationFunc) {
        this.currentPos = worldPos == null ? null : new Vector3d(worldPos);
        this.rotationFunc = rotationFunc;

        final Vector3d currPos = this.currentPos == null
            ? new Vector3d(getBlockPos().getX(), getBlockPos().getY(), getBlockPos().getZ())
            : new Vector3d(this.currentPos);
        final Vector3d upVec = rotatedSnapshot(BASE_UP_VECTOR, this.rotationFunc);
        final Vector3d rightVec = rotatedSnapshot(BASE_RIGHT_VECTOR, this.rotationFunc);
        final Vector3d forwardVec = rotatedSnapshot(BASE_FORWARD_VECTOR, this.rotationFunc);
        currPos.add(forwardVec);

        ContactResult contact = ContactResult.none();
        BlockPos anchor = null;
        if (isFinite(currPos) && PantographContactGeometry.isValidCollectorFrame(upVec, rightVec)) {
            anchor = BlockPos.containing(currPos.x, currPos.y, currPos.z);
            contact = CatenaryContactDetector.findContact(level, currPos, upVec, rightVec);
        }
        updateDebugContact(contact, anchor);
        this.touchingWire = contact.touching();
        this.catenaryWireHeight = contact.touching() ? contact.height() : -1;
        this.setExpanded(this.expandable && this.catenaryWireHeight >= 0);
        this.catenaryWireHeight = this.catenaryWireHeight < 0 ? 0 : this.catenaryWireHeight;
        if (this.expanded) {
            animationTransition.chase(this.catenaryWireHeight, 1, Chaser.LINEAR);
        }
    }

    public void applyMolangVariables() {
        MolangParser.INSTANCE.setValue("query.height_percentage", () -> {
            double p = 1D / DELTA_HEIGHT * animationTransition.getValue(AnimationTickHolder.getPartialTicks(level));
            return p;
        });
        MolangParser.INSTANCE.setValue("query.func", () -> {
            double p = MolangParser.INSTANCE.getVariable("query.height_percentage").get();
            return getArmAngle(p);
        });
        MolangParser.INSTANCE.setMemoizedValue("query.head_rotation", () -> {
            return 0;
        });
    }

    public static final double getArmAngle(double heightPercentage) {
        return Math.toDegrees(Math.acos((ARM_LENGTH_DOUBLE_POW - Math.pow(((heightPercentage + 0.04) * DELTA_HEIGHT_PIXELS), 2)) / ARM_LENGTH_DOUBLE_POW));
    }

    private static Vector3d rotatedSnapshot(Vector3d vector, UnaryOperator<Vector3d> rotation) {
        Vector3d input = new Vector3d(vector);
        Vector3d rotated = rotation == null ? input : rotation.apply(input);
        return rotated == null
            ? new Vector3d(Double.NaN, Double.NaN, Double.NaN)
            : new Vector3d(rotated);
    }

    private static boolean isFinite(Vector3d vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    private void updateDebugContact(
        ContactResult contact,
        BlockPos anchor
    ) {
        if (!WireDebugRenderer.enabled()) {
            return;
        }

        debug_hitHeight = 0;
        debug_wireCollisionA = new Vector3f();
        debug_wireCollisionB = new Vector3f();
        if (!contact.touching() || contact.height() >= PantographContactGeometry.MAX_HEIGHT
            || anchor == null || contact.winningSegment() == null) {
            return;
        }

        WireSegment segment = contact.winningSegment();
        debug_hitHeight = contact.height();
        debug_wireCollisionA = new Vector3f(
            (float) (anchor.getX() + segment.startX()),
            (float) (anchor.getY() + segment.startY()),
            (float) (anchor.getZ() + segment.startZ())
        );
        debug_wireCollisionB = new Vector3f(
            (float) (anchor.getX() + segment.endX()),
            (float) (anchor.getY() + segment.endY()),
            (float) (anchor.getZ() + segment.endZ())
        );
    }
}
