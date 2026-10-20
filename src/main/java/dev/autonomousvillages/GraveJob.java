package dev.autonomousvillages;

import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
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
            var text=entity.getFrontText().setMessage(0,Component.literal("Здесь покоится")).setMessage(1,Component.literal(NameplateJob.fit(grave.name)))
                    .setMessage(2,Component.literal(grave.role==null?"":NameplateJob.fit(grave.role))).setMessage(3,Component.literal("† день "+grave.day));
            entity.setText(text,true);entity.setChanged();v.level.sendBlockUpdated(spot,state,state,3);
        }
        v.state.graves.remove(grave);v.chronicle.add("graves",1);v.chronicle.event("На кладбище поставили табличку: "+grave.name);
        return false;
    }
    @Override void stop(){if(fetch!=null)fetch.cancel();if(approach!=null)approach.cancel();}

    /** How long a failed search for a cemetery plot waits before the next one: it scans hundreds of columns. */
    static final int SEARCH_COOLDOWN=6000;
    private static final Map<Village,Long> retry=new WeakHashMap<>();
    /**
     * The next free grave: the cemetery is on open natural ground 14-24 blocks from the bell and away from
     * homes, filled in rows of four, two blocks apart; once it is full or built over, a new one is chosen. A plot
     * that is only out of loaded chunks or blocked for a while is waited for: moving it scattered the graves.
     */
    static BlockPos spot(Village v){
        Long corner=v.state.cemetery!=null&&v.state.contains(BlockPos.of(v.state.cemetery))?v.state.cemetery:null;
        var cemetery=new Cemetery(corner,retry.getOrDefault(v,0L));
        BlockPos p=cemetery.next(v.level.getGameTime(),origin->free(v,origin),()->chooseCemetery(v,corner==null?null:BlockPos.of(corner)));
        v.state.cemetery=cemetery.corner;
        if(cemetery.retryAt>0)retry.put(v,cemetery.retryAt);else retry.remove(v);
        return p;
    }
    /** What a plot offers: a free grave, nothing for now (unloaded, or the places left are blocked for a while), or nothing for good. */
    record Room(BlockPos spot,boolean later){
        static final Room FULL=new Room(null,false),LATER=new Room(null,true);
    }
    /** Where the graves go and when a failed search may run again; no world access, so tests drive it. */
    static final class Cemetery {
        Long corner;
        long retryAt;
        Cemetery(Long corner,long retryAt){this.corner=corner;this.retryAt=retryAt;}
        /** A grave in the plot while it has room, else in a newly chosen plot; a search that found nothing waits for the cooldown. */
        BlockPos next(long now,Function<BlockPos,Room> free,Supplier<BlockPos> choose){
            if(corner!=null){Room room=free.apply(BlockPos.of(corner));if(room.spot()!=null||room.later())return room.spot();}
            if(now<retryAt)return null;
            BlockPos chosen=choose.get();BlockPos p=chosen==null?null:free.apply(chosen).spot();
            if(p==null){retryAt=now+SEARCH_COOLDOWN;return null;}
            corner=chosen.asLong();retryAt=0;
            return p;
        }
    }
    /** The first free grave of a plot, row by row; FULL only when every place is loaded and taken or unfit ground. */
    private static Room free(Village v,BlockPos origin){
        boolean later=false;
        for(int row=0;row<4;row++)for(int col=0;col<4;col++){
            BlockPos p=surface(v,origin.getX()+2*col,origin.getZ()+2*row);
            if(p==null){later=true;continue;}
            if(v.level.getBlockState(p).getBlock() instanceof StandingSignBlock||!fits(v,p))continue;
            if(Lighting.unreachable(v,p)){later=true;continue;}
            return new Room(p,false);
        }
        return later?Room.LATER:Room.FULL;
    }
    /** The best plot for a cemetery; not over an old one, whose graves would end up between the new ones. */
    private static BlockPos chooseCemetery(Village v,BlockPos old){
        BlockPos hub=v.meetingPoint();BlockPos best=null;double bestScore=Double.MAX_VALUE;
        for(int dx=-24;dx<=24;dx+=3)for(int dz=-24;dz<=24;dz+=3){
            double d=Math.sqrt(dx*dx+dz*dz);if(d<14||d>24)continue;
            BlockPos corner=new BlockPos(hub.getX()+dx,hub.getY(),hub.getZ()+dz);
            if(!v.level.hasChunkAt(corner)||old!=null&&Math.abs(corner.getX()-old.getX())<8&&Math.abs(corner.getZ()-old.getZ())<8)continue;
            // The whole 7×7 plot must be free ground for the rows to come, with no grave of an earlier cemetery in it.
            int good=0;
            for(int x=0;x<7&&good>=0;x++)for(int z=0;z<7&&good>=0;z++){
                BlockPos p=surface(v,corner.getX()+x,corner.getZ()+z);
                if(p!=null&&v.level.getBlockState(p).getBlock() instanceof StandingSignBlock)good=-1;
                else if(x%2==0&&z%2==0&&p!=null&&fits(v,p)&&!Lighting.unreachable(v,p))good++;
            }
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
        if(!v.state.contains(p)||!v.canWork(p)||v.town.protects(p)||!v.state.policy.allows("build",p))return false;
        if(!Construction.clear(v.level.getBlockState(p))||!v.level.getBlockState(p).getFluidState().isEmpty())return false;
        var ground=v.level.getBlockState(p.below());
        if(!VillageIndex.naturalSoil(ground)&&!ground.is(Blocks.SAND)&&!ground.is(Blocks.RED_SAND))return false;
        return Math.abs(p.getY()-v.state.y)<=6&&v.index.beds.stream().noneMatch(b->b.distSqr(p)<10*10);
    }
}
