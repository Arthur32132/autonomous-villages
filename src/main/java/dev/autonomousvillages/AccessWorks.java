package dev.autonomousvillages;

import dev.navigationai.api.*;
import net.minecraft.core.*;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.Vec3;

/** A real paid staircase, not extra reach or instant felling, gives a woodcutter access to high logs. */
final class AccessWorks {
    static boolean tree(Resident r){
        if(r.v.state.accessBlocks.size()>=2048)return false;
        if(r.treeBase==null||r.target.getY()-r.treeBase.getY()<4)return false;
        var b=r.bot();var v=r.v;int height=Math.min(12,r.target.getY()-r.treeBase.getY()-3);
        Direction side=Direction.EAST;
        double dx=b.entity().getX()-r.treeBase.getX(),dz=b.entity().getZ()-r.treeBase.getZ();
        if(Math.abs(dx)>Math.abs(dz))side=dx>0?Direction.EAST:Direction.WEST;else side=dz>0?Direction.SOUTH:Direction.NORTH;
        BlockPos underBranch=new BlockPos(r.target.getX(),r.treeBase.getY(),r.target.getZ());
        BlockPos reachableStep=null;
        for(int h=0;h<height;h++){
            BlockPos p=underBranch.relative(side,height-h).above(h);
            boolean blockedHead=false;
            for(int head:new int[]{1,2,3}){
                BlockPos leaves=p.above(head);
                if(v.level.getBlockState(leaves).is(net.minecraft.tags.BlockTags.LEAVES)){
                    blockedHead=true;
                    if(b.entity().getEyePosition().distanceToSqr(Vec3.atCenterOf(leaves))>=19)continue;
                    var hit=v.level.clip(new net.minecraft.world.level.ClipContext(b.entity().getEyePosition(),Vec3.atCenterOf(leaves),net.minecraft.world.level.ClipContext.Block.OUTLINE,net.minecraft.world.level.ClipContext.Fluid.NONE,b.entity()));
                    if(hit.getType()==net.minecraft.world.phys.HitResult.Type.BLOCK&&hit.getBlockPos().equals(leaves)){
                        r.clearingLeaves=true;r.mining=b.miner().breakBlock(leaves);r.status="расчищает проход над подмостями";return true;
                    }
                }
            }
            if(blockedHead&&reachableStep!=null&&b.entity().position().distanceToSqr(v.feetPosition(reachableStep))>.36){
                r.lastApproach=reachableStep;r.navigation=b.navigator().goTo(new NavigationGoal.PositionGoal(v.feetPosition(reachableStep),.15),Resident.TRAVEL);
                r.status="поднимается к листве над следующей ступенью";return true;
            }
            if(v.level.getBlockState(p).isFaceSturdy(v.level,p,Direction.UP)){
                if(!blockedHead&&v.safeFeet(p.above()))reachableStep=p.above();
                continue;
            }
            if(!Construction.clear(v.level.getBlockState(p))||v.town.protects(p)||!v.canWork(p))return false;
            int slot=Supplies.find(b,s->s.is(ItemTags.PLANKS));if(slot<0)return false;
            var material=(BlockItem)b.inventory().snapshot().slots().get(slot).getItem();
            var step=new Construction.Step(p,material.getBlock().defaultBlockState(),material);
            if(b.entity().getEyePosition().distanceToSqr(Vec3.atCenterOf(p))<=19&&!Construction.intersectsActor(v,b,step)){
                if(Construction.place(v,b,step)){v.state.accessBlocks.add(p.asLong());v.runtime.save();r.status="строит подмости к верхушке дерева";return true;}
            }
            BlockPos stand=v.stand(p,b.entity().position(),4.2,false);
            if(stand!=null){r.lastApproach=stand;r.navigation=b.navigator().goTo(new NavigationGoal.PositionGoal(v.feetPosition(stand),.15),Resident.TRAVEL);
                r.status="поднимается по подмостям";return true;}
            return false;
        }
        if(reachableStep!=null&&b.entity().position().distanceToSqr(v.feetPosition(reachableStep))>.36){
            r.lastApproach=reachableStep;r.navigation=b.navigator().goTo(new NavigationGoal.PositionGoal(v.feetPosition(reachableStep),.15),Resident.TRAVEL);return true;
        }
        return false;
    }
}
