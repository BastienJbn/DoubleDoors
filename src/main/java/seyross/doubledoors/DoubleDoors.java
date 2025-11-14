package seyross.doubledoors;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.BlockState;
import net.minecraft.block.Block;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class DoubleDoors implements ClientModInitializer {

    private static class PendingInteraction {
        BlockPos targetPos;
        Vec3d hitPos;
        Direction side;
        Hand hand;
        int ticks;
    }

    private PendingInteraction pending;

    @Override
    public void onInitializeClient() {
        // Detect door clicks
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClient())
                return ActionResult.PASS;

            BlockPos pos = hitResult.getBlockPos();
            BlockState state = world.getBlockState(pos);
            Block block = state.getBlock();

            // Only doors block
            if (!(block instanceof DoorBlock))
                return ActionResult.PASS;

            // Sneak + freehand → open only one door
            if (player.isSneaking() && player.getMainHandStack().isEmpty())
                return ActionResult.PASS;

            // Find paired door
            BlockPos otherDoor = findPairedDoor(state, pos, world);
            if (otherDoor == null)
                return ActionResult.PASS;

            // Schedule packet next tick
            scheduleInteraction(player, otherDoor, hand);

            return ActionResult.PASS;
        });

        // Perform scheduled packet
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (pending != null) {
                performPendingInteraction(client);
            }
        });
    }

    private BlockPos findPairedDoor(BlockState state, BlockPos pos, net.minecraft.world.World world) {
        Direction facing = state.get(DoorBlock.FACING);

        // Normalize to bottom half
        BlockPos bottomPos;
        BlockState bottomState;

        if (state.get(DoorBlock.HALF) == DoubleBlockHalf.UPPER) {
            bottomPos = pos.down();
            bottomState = world.getBlockState(bottomPos);
            if (!(bottomState.getBlock() instanceof DoorBlock)) return null;
        } else {
            bottomPos = pos;
            bottomState = state;
        }

        boolean clickedOpen = bottomState.get(DoorBlock.OPEN);

        // Check left/right neighbors perpendicular to facing
        Direction left = facing.rotateYClockwise();
        Direction right = facing.rotateYCounterclockwise();

        BlockPos leftPos = bottomPos.offset(left);
        BlockPos rightPos = bottomPos.offset(right);

        BlockState leftState = world.getBlockState(leftPos);
        BlockState rightState = world.getBlockState(rightPos);

        if (leftState.getBlock() instanceof DoorBlock
                && leftState.getBlock() == bottomState.getBlock()
                && leftState.get(DoorBlock.OPEN) == clickedOpen) {
            return leftPos;
        }

        if (rightState.getBlock() instanceof DoorBlock
                && rightState.getBlock() == bottomState.getBlock()
                && rightState.get(DoorBlock.OPEN) == clickedOpen) {
            return rightPos;
        }

        return null;
    }

    private void scheduleInteraction(PlayerEntity player, BlockPos pos, Hand hand) {
        Vec3d eyePos = player.getCameraPosVec(1.0f);
        Vec3d doorCenter = Vec3d.ofCenter(pos);

        Vec3d delta = doorCenter.subtract(eyePos);
        double maxReach = 5.0; // server max interaction distance

        if (delta.length() > maxReach) {
            delta = delta.normalize().multiply(maxReach);
        }

        Vec3d hitVec = eyePos.add(delta);

        PendingInteraction pi = new PendingInteraction();
        pi.targetPos = pos;
        pi.hitPos = hitVec;
        pi.side = Direction.UP;
        pi.hand = hand;
        pi.ticks = 1;

        pending = pi;
    }

    private void performPendingInteraction(MinecraftClient client) {
        pending.ticks--;
        if (pending.ticks > 0) return;

        PlayerInteractBlockC2SPacket packet = new PlayerInteractBlockC2SPacket(
                pending.hand,
                new BlockHitResult(pending.hitPos, pending.side, pending.targetPos, false),
                0
        );

        client.getNetworkHandler().sendPacket(packet);

        pending = null;
    }
}
