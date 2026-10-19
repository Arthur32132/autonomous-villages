package dev.autonomousvillages;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SignItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * A resident who died gets a sign with the name and the day at the village cemetery: a quiet patch
 * of open ground at the edge of the village, graves in rows two blocks apart, facing the bell.
 */
final class GraveJob extends Job {
    private final VillageState.Grave grave;
    private final BlockPos spot;
    private Fetch fetch;
    private Approach approach;
    GraveJob(Worker w,VillageState.Grave grave,BlockPos spot){super(w,"ставит табличку в память о жителе");this.grave=grave;this.spot=spot.immutable();}
    @Override String kind(){return "grave";}
    @Override BlockPos target(){return spot;}
    @Override boolean keeps(ItemStack s){return s.is(ItemTags.SIGNS);}
    @Override boolean tick(){
        if(!w.has(s->s.is(ItemTags.SIGNS))){
            if(fetch==null)fetch=new Fetch(List.of(new Need(s->s.is(ItemTags.SIGNS),1,"табличка")));
            var fetched=fetch.tick();
            if(fetched==Approach.State.FAILED)return fail("нет таблички");
            if(fetched==Approach.State.MOVING)return true;
        }
        if(approach==null)approach=new Approach(spot,4.0,false);
        var reach=approach.tick();
        if(reach==Approach.State.MOVING)return true;
        if(reach==Approach.State.FAILED){v.rejected.put(spot.asLong(),now()+2400);return fail("не подойти к кладбищу");}
        int slot=Supplies.find(bot(),s->s.is(ItemTags.SIGNS));
        if(slot<0||!(w.bag().get(slot).getItem() instanceof SignItem sign)||!(sign.getBlock() instanceof StandingSignBlock))return fail("нет таблички");
        // The text faces the bell, as if put up by someone coming from the village.
        BlockPos hub=v.meetingPoint();
        float yaw=(float)Math.toDegrees(Math.atan2(-(spot.getX()-hub.getX()),spot.getZ()-hub.getZ()));
        var state=sign.getBlock().defaultBlockState().setValue(StandingSignBlock.ROTATION,RotationSegment.convertToSegment(yaw+180f));
        if(!Construction.place(v,bot(),new Construction.Step(spot,state,sign)))return fail("не удалось поставить");
        if(v.level.getBlockEntity(spot) instanceof SignBlockEntity entity){
            var text=entity.getFrontText().setMessage(0,Component.literal("Здесь покоится")).setMessage(1,Component.literal(grave.name))
                    .setMessage(2,Component.literal(grave.role==null?"":grave.role)).setMessage(3,Component.literal("† день "+grave.day));
            entity.setText(text,true);entity.setChanged();v.level.sendBlockUpdated(spot,state,state,3);
        }
        v.state.graves.remove(grave);v.chronicle.add("graves",1);v.chronicle.event("На кладбище поставили табличку: "+grave.name);
        return false;
    }
    @Override void stop(){if(fetch!=null)fetch.cancel();if(approach!=null)approach.cancel();}

    /**
     * The next free grave: the cemetery is chosen once, on open natural ground 14-24 blocks from the
     * bell and away from homes, and filled in rows of four, two blocks apart.
     */
    static BlockPos spot(Village v){
        if(v.state.cemetery==null||!v.state.contains(BlockPos.of(v.state.cemetery))){
            BlockPos chosen=chooseCemetery(v);v.state.cemetery=chosen==null?null:chosen.asLong();
        }
        if(v.state.cemetery==null)return null;
        BlockPos origin=BlockPos.of(v.state.cemetery);
        for(int row=0;row<4;row++)for(int col=0;col<4;col++){
            BlockPos p=surface(v,origin.getX()+2*col,origin.getZ()+2*row);
            if(p!=null&&v.level.getBlockState(p).getBlock() instanceof StandingSignBlock)continue;
            if(p!=null&&fits(v,p))return p;
        }
        return null;
    }
    private static BlockPos chooseCemetery(Village v){
        BlockPos hub=v.meetingPoint();BlockPos best=null;double bestScore=Double.MAX_VALUE;
        for(int dx=-24;dx<=24;dx+=3)for(int dz=-24;dz<=24;dz+=3){
            double d=Math.sqrt(dx*dx+dz*dz);if(d<14||d>24)continue;
            BlockPos corner=new BlockPos(hub.getX()+dx,hub.getY(),hub.getZ()+dz);
            if(!v.level.hasChunkAt(corner))continue;
            // The whole 7×7 plot must be free ground for the rows to come.
            int good=0;
            for(int x=0;x<7;x+=2)for(int z=0;z<7;z+=2){BlockPos p=surface(v,corner.getX()+x,corner.getZ()+z);if(p!=null&&fits(v,p))good++;}
            if(good<14)continue;
            double score=Math.abs(d-18)+(16-good)*2;
            if(score<bestScore){bestScore=score;best=corner;}
        }
        return best;
    }
    private static BlockPos surface(Village v,int x,int z){
        BlockPos column=new BlockPos(x,v.state.y,z);
        if(!v.level.hasChunkAt(column))return null;
        return new BlockPos(x,v.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z),z);
    }
    private static boolean fits(Village v,BlockPos p){
        if(!v.state.contains(p)||!v.canWork(p)||v.town.protects(p)||!v.state.policy.allows("build",p)||Lighting.unreachable(v,p))return false;
        if(!Construction.clear(v.level.getBlockState(p))||!v.level.getBlockState(p).getFluidState().isEmpty())return false;
        var ground=v.level.getBlockState(p.below());
        if(!VillageIndex.naturalSoil(ground)&&!ground.is(Blocks.SAND)&&!ground.is(Blocks.RED_SAND))return false;
        return Math.abs(p.getY()-v.state.y)<=6&&v.index.beds.stream().noneMatch(b->b.distSqr(p)<10*10);
    }
}
