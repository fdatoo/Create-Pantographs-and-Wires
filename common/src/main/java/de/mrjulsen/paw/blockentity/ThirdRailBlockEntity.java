package de.mrjulsen.paw.blockentity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import de.mrjulsen.paw.block.ThirdRailBlock;
import de.mrjulsen.paw.block.property.EThirdRailShape;
import de.mrjulsen.paw.config.ModServerConfig;
import de.mrjulsen.paw.registry.ModBlocks;
import de.mrjulsen.paw.registry.ModDamageTypes;
import de.mrjulsen.paw.traction.SegmentClipping;
import de.mrjulsen.paw.traction.ThirdRailConnection;
import de.mrjulsen.paw.traction.ThirdRailCurve;
import de.mrjulsen.paw.traction.ThirdRailIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A third rail block and the rails laid from it. The block itself carries a straight piece of rail
 * along its shape; each connection is a curve to another rail block. Both are synced to clients
 * through normal block entity updates, drawn by ThirdRailRenderer, picked up by collector shoes
 * through ThirdRailIndex, and on the server they shock living entities that touch the conductor.
 */
public class ThirdRailBlockEntity extends SmartBlockEntity {
    private static final String NBT_CONNECTIONS = "Connections";
    /** How often the server checks for entities touching the conductor. */
    private static final int SHOCK_INTERVAL_TICKS = 5;
    /** Entities count as touching when their box comes this close to the conductor's centreline. */
    private static final double SHOCK_MARGIN = 0.1;

    private final Map<BlockPos, ThirdRailConnection> connections = new HashMap<>();
    private double[] blockConductor;
    private AABB blockConductorBounds;
    private EThirdRailShape blockConductorShape;
    private int shockTicker;
    private boolean cancelDrops;
    private boolean indexed;
    /** Bumped whenever the rails change, so cached client geometry knows to rebuild. */
    private int geometryVersion;

    /** Client-side render cache owned by ThirdRailRenderer; kept here so it lives and dies with the block entity. */
    public Object renderCache;
    public long renderCacheKey = Long.MIN_VALUE;

    public ThirdRailBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {}

    /** Rail data decides where geometry is built and what is refunded, so only operators may paste it onto an item. */
    @Override
    public boolean onlyOpCanSetNbt() {
        return true;
    }

    public Collection<ThirdRailConnection> getConnections() {
        return Collections.unmodifiableCollection(connections.values());
    }

    public boolean hasConnectionTo(BlockPos other) {
        return connections.containsKey(other);
    }

    public EThirdRailShape shape() {
        BlockState state = getBlockState();
        return state.hasProperty(ThirdRailBlock.SHAPE) ? state.getValue(ThirdRailBlock.SHAPE) : EThirdRailShape.Z;
    }

    /** Identifies the current rail geometry, for the renderer's cache. */
    public long renderKey() {
        return ((long) geometryVersion << 3) | shape().ordinal();
    }

    public void addConnection(ThirdRailConnection connection) {
        connections.put(connection.other(), connection);
        railsChanged();
        notifyUpdate();
    }

    public void removeConnection(BlockPos other) {
        if (connections.remove(other) != null) {
            railsChanged();
            notifyUpdate();
        }
    }

    public void setCancelDrops(boolean cancelDrops) {
        this.cancelDrops = cancelDrops;
    }

    /** Removes every rail laid from this block, at both ends, handing back the rails it used. */
    public void removeInboundConnections(boolean dropItems) {
        for (ThirdRailConnection connection : new ArrayList<>(connections.values())) {
            if (level.getBlockEntity(connection.other()) instanceof ThirdRailBlockEntity other) {
                other.removeConnection(worldPosition);
            }
            if (dropItems && !cancelDrops) {
                Vec3 mid = connection.midpoint();
                ItemEntity drop = new ItemEntity(level, mid.x, mid.y + 0.25, mid.z, new ItemStack(ModBlocks.THIRD_RAIL.get(), connection.railCost()));
                drop.setDefaultPickUpDelay();
                level.addFreshEntity(drop);
            }
        }
        connections.clear();
        railsChanged();
        notifyUpdate();
    }

    /** Moves the cover's supports to the other side of every rail laid from this block. */
    public void flipSupports() {
        for (ThirdRailConnection connection : new ArrayList<>(connections.values())) {
            ThirdRailConnection flipped = connection.withSupportsOnRight(!connection.supportsOnRight());
            connections.put(flipped.other(), flipped);
            if (level.getBlockEntity(flipped.other()) instanceof ThirdRailBlockEntity other) {
                other.addConnection(flipped.secondary());
            }
        }
        railsChanged();
        notifyUpdate();
    }

    /**
     * Whether the supports of this block's own piece stand on the right, travelling along its
     * positive axis. Follows the first rail laid from it so the piece lines up with the curve.
     */
    public boolean pieceSupportsOnRight() {
        Vec3 axis = shape().axis();
        for (ThirdRailConnection connection : connections.values()) {
            boolean leavesForward = connection.axis1().dot(axis) > 0;
            return leavesForward == connection.supportsOnRight();
        }
        return true;
    }

    /** The conductor of this block's own straight piece, in world space. */
    public double[] blockConductor() {
        EThirdRailShape shape = shape();
        if (blockConductor == null || blockConductorShape != shape) {
            Vec3 half = shape.axis().scale(0.5);
            double x = worldPosition.getX() + 0.5;
            double y = worldPosition.getY() + ThirdRailCurve.CONDUCTOR_HEIGHT;
            double z = worldPosition.getZ() + 0.5;
            blockConductor = new double[] {x - half.x, y, z - half.z, x + half.x, y, z + half.z};
            blockConductorBounds = ThirdRailConnection.bounds(blockConductor);
            blockConductorShape = shape;
        }
        return blockConductor;
    }

    private AABB blockConductorBounds() {
        blockConductor();
        return blockConductorBounds;
    }

    /** A box around every conductor this block knows about. */
    public AABB conductorExtent() {
        AABB extent = blockConductorBounds();
        for (ThirdRailConnection connection : connections.values()) {
            extent = extent.minmax(connection.conductorBounds());
        }
        return extent;
    }

    /** Every conductor this block owns: its own piece and the rails it is the primary end of. */
    public List<double[]> ownedConductors() {
        List<double[]> conductors = new ArrayList<>();
        conductors.add(blockConductor());
        for (ThirdRailConnection connection : connections.values()) {
            if (connection.primary()) {
                conductors.add(connection.conductor());
            }
        }
        return conductors;
    }

    /**
     * Whether any conductor known to this block, secondary copies included, reaches into the query box
     * and satisfies the test. Secondary copies count so a rail whose primary end is unloaded still
     * powers shoes; both copies describe the same curve.
     */
    public boolean anyConductorNear(AABB query, Predicate<double[]> test) {
        if (blockConductorBounds().intersects(query) && test.test(blockConductor)) {
            return true;
        }
        for (ThirdRailConnection connection : connections.values()) {
            if (connection.conductorBounds().intersects(query) && test.test(connection.conductor())) {
                return true;
            }
        }
        return false;
    }

    private void railsChanged() {
        geometryVersion++;
        if (level != null && indexed) {
            ThirdRailIndex.update(level, this);
        }
    }

    @Override
    public void initialize() {
        super.initialize();
        // Indexed on both sides: the client for the traction sound, the server for electric supply.
        indexed = true;
        ThirdRailIndex.update(level, this);
    }

    @Override
    public void invalidate() {
        super.invalidate();
        if (level != null) {
            indexed = false;
            ThirdRailIndex.remove(level, this);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (!level.isClientSide && ++shockTicker >= SHOCK_INTERVAL_TICKS) {
            shockTicker = 0;
            shockTouchingEntities();
        }
    }

    @Override
    public void lazyTick() {
        super.lazyTick();
        if (!level.isClientSide) {
            validateConnections();
        }
    }

    /** Drops rails whose far end is gone, and restores the far end's copy if it went missing. */
    private void validateConnections() {
        List<BlockPos> invalid = new ArrayList<>();
        for (ThirdRailConnection connection : connections.values()) {
            if (!level.isLoaded(connection.other())) {
                continue;
            }
            BlockEntity blockEntity = level.getBlockEntity(connection.other());
            if (!(blockEntity instanceof ThirdRailBlockEntity other) || other.isRemoved()) {
                invalid.add(connection.other());
                continue;
            }
            if (!other.connections.containsKey(worldPosition)) {
                other.addConnection(connection.secondary());
            }
        }
        invalid.forEach(this::removeConnection);
    }

    private void shockTouchingEntities() {
        if (!ModServerConfig.THIRD_RAIL_ENTITY_DAMAGE.get()) {
            return;
        }
        float amount = ModServerConfig.THIRD_RAIL_DAMAGE_AMOUNT.get().floatValue();
        if (amount <= 0) {
            return;
        }
        DamageSource source = null;
        for (double[] conductor : ownedConductors()) {
            AABB area = ThirdRailConnection.bounds(conductor).inflate(1);
            for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, area, ThirdRailBlockEntity::canBeShocked)) {
                AABB box = entity.getBoundingBox().inflate(SHOCK_MARGIN);
                if (!SegmentClipping.polylineIntersectsBox(conductor, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)) {
                    continue;
                }
                if (source == null) {
                    source = ModDamageTypes.thirdRail(level);
                }
                if (entity.hurt(source, amount) && level instanceof ServerLevel serverLevel) {
                    serverLevel.sendParticles(ParticleTypes.ELECTRIC_SPARK, entity.getX(), entity.getY() + 0.3, entity.getZ(), 40, 0.35, 0.3, 0.35, 0.6);
                    serverLevel.sendParticles(ParticleTypes.END_ROD, entity.getX(), entity.getY() + 0.3, entity.getZ(), 8, 0.2, 0.2, 0.2, 0.08);
                }
            }
        }
    }

    private static boolean canBeShocked(LivingEntity entity) {
        return entity.isAlive() && !entity.isSpectator() && !(entity.getVehicle() instanceof AbstractContraptionEntity);
    }

    @Override
    protected void write(CompoundTag tag, boolean clientPacket) {
        super.write(tag, clientPacket);
        ListTag list = new ListTag();
        for (ThirdRailConnection connection : connections.values()) {
            list.add(connection.write());
        }
        tag.put(NBT_CONNECTIONS, list);
    }

    @Override
    protected void read(CompoundTag tag, boolean clientPacket) {
        super.read(tag, clientPacket);
        connections.clear();
        for (Tag entry : tag.getList(NBT_CONNECTIONS, Tag.TAG_COMPOUND)) {
            ThirdRailConnection connection = ThirdRailConnection.read(worldPosition, (CompoundTag) entry);
            if (connection != null) {
                connections.put(connection.other(), connection);
            }
        }
        railsChanged();
    }

    /** Forge culls block entity renderers by this box; the rail can reach far beyond its block. */
    public AABB getRenderBoundingBox() {
        return conductorExtent().inflate(1).minmax(new AABB(worldPosition));
    }
}
