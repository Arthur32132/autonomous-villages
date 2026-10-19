package dev.autonomousvillages;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;

/**
 * A house takes days, a bed an afternoon: while every bed is taken, a single bed in a home gets a
 * second one alongside, under the same roof and facing the same way. A bed that already has another
 * beside it counts as a pair and gets no more, so a home at most doubles.
 */
final class SpareBeds {
    private SpareBeds(){}
    /** Where the foot of the new bed goes, and its facing; null when no single bed has room beside it. */
    record Spot(BlockPos foot,Direction facing){}

    /**
     * Only beds of the village's own buildings get a pair, and only in a pocket that leads nowhere:
     * between the bed and a side wall, head to the wall, so the new bed cannot cut any way through.
     */
    static Spot spot(Village v,java.util.UUID worker){
        for(BlockPos head:v.index.beds){
            var bed=v.level.getBlockState(head);
            if(!(bed.getBlock() instanceof BedBlock)||bed.getValue(BedBlock.PART)!=BedPart.HEAD||!v.state.contains(head))continue;
            Direction facing=bed.getValue(BedBlock.FACING);BlockPos foot=head.relative(facing.getOpposite());
            if(!v.town.protects(foot))continue;
            Direction left=facing.getCounterClockWise(),right=facing.getClockWise();
            if(isBed(v,head.relative(left))||isBed(v,head.relative(right))||isBed(v,foot.relative(left))||isBed(v,foot.relative(right)))continue;
            for(Direction side:new Direction[]{left,right}){
                BlockPos newFoot=foot.relative(side),newHead=head.relative(side);
                if(!v.ready(newFoot,worker)||!wall(v,newHead.relative(facing))||!wall(v,newHead.relative(side))||!wall(v,newFoot.relative(side)))continue;
                if(fits(v,newFoot)&&fits(v,newHead))return new Spot(newFoot,facing);
            }
        }
        return null;
    }
    private static boolean isBed(Village v,BlockPos p){return v.level.getBlockState(p).getBlock() instanceof BedBlock;}
    private static boolean wall(Village v,BlockPos p){
        var s=v.level.getBlockState(p);
        return s.blocksMotion()&&!(s.getBlock() instanceof DoorBlock)&&!(s.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock)
                &&!(s.getBlock() instanceof net.minecraft.world.level.block.TrapDoorBlock);
    }
    /** Empty floor under the roof with headroom, out of every doorway, the village's to build on. */
    private static boolean fits(Village v,BlockPos p){
        if(!v.state.contains(p)||!v.canWork(p)||v.town.protects(p)||!v.state.policy.allows("build",p))return false;
        if(!v.level.getBlockState(p).isAir()||!Construction.clear(v.level.getBlockState(p.above()))||v.level.canSeeSky(p))return false;
        BlockState floor=v.level.getBlockState(p.below());
        if(!floor.isFaceSturdy(v.level,p.below(),Direction.UP)||floor.is(Blocks.FARMLAND)||floor.is(Blocks.DIRT_PATH))return false;
        for(Direction d:Direction.Plane.HORIZONTAL){
            var near=v.level.getBlockState(p.relative(d)).getBlock();
            if(near instanceof DoorBlock||near instanceof net.minecraft.world.level.block.FenceGateBlock||near instanceof net.minecraft.world.level.block.TrapDoorBlock)return false;
        }
        return true;
    }
    /** A bed the worker carries or the store holds: its colour decides the block. */
    static Item bedItem(Village v,Worker w){
        int slot=Supplies.find(w.bot(),s->s.is(ItemTags.BEDS));
        if(slot>=0)return w.bag().get(slot).getItem();
        for(var c:v.stock.chests())for(int i=0;i<c.getContainerSize();i++)if(c.getItem(i).is(ItemTags.BEDS))return c.getItem(i).getItem();
        return null;
    }
}
