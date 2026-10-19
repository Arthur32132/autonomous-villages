package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

/** Flattens grass into a footpath with a real shovel, cell by cell, from a door toward the centre. */
final class PathJob extends Job {
    private final ArrayDeque<BlockPos> cells;
    private Fetch fetch;
    private Approach approach;
    private int paved;
    PathJob(Worker w,List<BlockPos> cells){super(w,"прокладывает тропинку");this.cells=new ArrayDeque<>(cells);}
    @Override String kind(){return "path";}
    @Override BlockPos target(){return cells.peek();}
    @Override boolean keeps(ItemStack s){return s.is(ItemTags.SHOVELS);}
    @Override boolean tick(){
        int shovel=Supplies.find(bot(),s->s.is(ItemTags.SHOVELS)&&Supplies.usable(s));
        if(shovel<0){
            if(fetch==null)fetch=new Fetch(List.of(new Need(s->s.is(ItemTags.SHOVELS)&&Supplies.usable(s),1,"лопата")));
            var state=fetch.tick();
            if(state==Approach.State.FAILED)return fail("нет лопаты");
            return true;
        }
        while(!cells.isEmpty()&&!pavable(v,cells.peek()))cells.poll();
        if(cells.isEmpty())return false;
        if(approach==null)approach=new Approach(cells.peek(),4.0,false);
        var state=approach.tick();
        if(state==Approach.State.MOVING)return true;
        BlockPos cell=cells.poll();approach=null;
        if(state==Approach.State.FAILED)return paved>0||!cells.isEmpty();
        if(!v.state.policy.allows("build",cell))return true;
        bot().inventory().select(shovel);
        v.level.setBlock(cell,Blocks.DIRT_PATH.defaultBlockState(),3);
        v.level.playSound(null,cell,SoundEvents.SHOVEL_FLATTEN,SoundSource.BLOCKS,1f,1f);
        body().swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        body().getMainHandItem().hurtAndBreak(1,body(),EquipmentSlot.MAINHAND);
        paved++;v.chronicle.add("paths",1);
        return !cells.isEmpty();
    }
    static boolean pavable(Village v,BlockPos p){
        var s=v.level.getBlockState(p);
        return (s.is(Blocks.GRASS_BLOCK)||s.is(Blocks.DIRT)||s.is(Blocks.COARSE_DIRT)||s.is(Blocks.PODZOL))&&Construction.clear(v.level.getBlockState(p.above()));
    }
    static final int PLAZA=3;
    /** Ground in a round patch around the bell, so the meeting place reads as a village square; nearest first. */
    static List<BlockPos> plaza(Village v,int limit){
        BlockPos hub=v.meetingPoint();var out=new ArrayList<BlockPos>();
        for(int dx=-PLAZA;dx<=PLAZA;dx++)for(int dz=-PLAZA;dz<=PLAZA;dz++){
            if(!inPlaza(dx,dz))continue;
            int x=hub.getX()+dx,z=hub.getZ()+dz;
            if(!v.level.hasChunkAt(new BlockPos(x,hub.getY(),z)))continue;
            var p=new BlockPos(x,v.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z)-1,z);
            // The bell may hang on a post: the square lies on the ground below it, not on a hilltop nearby.
            if(p.getY()>hub.getY()||p.getY()<hub.getY()-4||!v.state.contains(p)||v.town.protects(p)||!v.state.policy.allows("build",p)
                    ||Lighting.unreachable(v,p)||!pavable(v,p))continue;
            out.add(p);
        }
        out.sort(Comparator.comparingDouble(p->p.distSqr(hub)));
        return out.size()>limit?out.subList(0,limit):out;
    }
    /** A disc of radius {@link #PLAZA}, a little fuller than a strict circle so its edge is not ragged. */
    static boolean inPlaza(int dx,int dz){return dx*dx+dz*dz<=PLAZA*PLAZA+1;}
    @Override void stop(){if(fetch!=null)fetch.cancel();if(approach!=null)approach.cancel();}
}
