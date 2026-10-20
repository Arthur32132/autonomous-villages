package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.phys.Vec3;

/**
 * Night watch for an armed guard: from the watchtower's lookout and the bell, a round of outdoor points
 * (doorsteps, lamp posts, the palisade's gates) with a short wait at each. Doors stay shut: no stop is inside
 * a house. Fights are started by the worker's own threat check, not by this job.
 */
final class PatrolJob extends Job {
    /** Outdoor stops of one guard's round besides the lookout, the bell and the gates. */
    static final int MAX_STOPS=8;
    private final Round round;
    private final BlockPos lookout;
    private final int watch;
    private Walk walk;
    private long waitUntil;
    PatrolJob(Worker w){
        super(w,"ночной дозор");
        lookout=v.town.lookout();
        BlockPos hub=v.meetingPoint();int seed=Math.floorMod(w.id.hashCode(),1<<16);
        var gates=new ArrayList<BlockPos>();for(BlockPos g:v.town.gates())gates.add(inside(v,g,hub));
        var stops=route(hub,lookout,outdoorPoints(v),gates,seed);
        // A finished watchtower is the first stop: the lookout over the whole village.
        round=new Round(stops,lookout!=null?0:seed);
        watch=nightLeft(v.level.getOverworldClockTime());
    }
    /** Ticks until the rest window ends at 1000 the next morning: 13500 from dusk, a whole day when dark at dawn. */
    static int nightLeft(long clock){return (int)Math.floorMod(999-clock,24_000L)+1;}
    /**
     * The round: the lookout first when the tower stands, then the bell, then the outdoor points ordered by their
     * angle around the bell, so one round passes every side of the village. Each guard takes an even share of at
     * most MAX_STOPS points and two of the gates, both chosen by the seed.
     */
    static List<BlockPos> route(BlockPos hub,BlockPos lookout,List<BlockPos> points,List<BlockPos> gates,int seed){
        var around=new ArrayList<BlockPos>(new LinkedHashSet<>(points));around.remove(hub);if(lookout!=null)around.remove(lookout);
        around.sort(Comparator.comparingLong(BlockPos::asLong));around.sort(Comparator.comparingDouble(p->angle(hub,p)));
        var chosen=new LinkedHashSet<BlockPos>();
        int step=Math.max(1,(around.size()+MAX_STOPS-1)/MAX_STOPS);
        for(int i=Math.floorMod(seed,step);i<around.size();i+=step)chosen.add(around.get(i));
        var gate=new ArrayList<BlockPos>(new LinkedHashSet<>(gates));gate.sort(Comparator.comparingLong(BlockPos::asLong));
        for(int i=Math.floorMod(seed,Math.max(1,gate.size())),n=0;n<Math.min(2,gate.size());n++,i=(i+Math.max(1,gate.size()/2))%gate.size())chosen.add(gate.get(i));
        chosen.remove(hub);if(lookout!=null)chosen.remove(lookout);
        var ring=new ArrayList<>(chosen);ring.sort(Comparator.comparingDouble(p->angle(hub,p)));
        var loop=new ArrayList<BlockPos>();
        if(lookout!=null)loop.add(lookout);
        loop.add(hub);loop.addAll(ring);
        return loop;
    }
    private static double angle(BlockPos hub,BlockPos p){return Math.atan2(p.getZ()-hub.getZ(),p.getX()-hub.getX());}
    /** Where the watch stands outdoors: the open step outside each door and the foot of each lamp post, in loaded chunks. */
    private static List<BlockPos> outdoorPoints(Village v){
        var out=new ArrayList<BlockPos>();
        for(BlockPos door:v.town.doors()){
            if(!v.level.hasChunkAt(door))continue;
            var state=v.level.getBlockState(door);
            if(!(state.getBlock() instanceof DoorBlock))continue;
            Direction facing=state.getValue(DoorBlock.FACING);
            // One or two steps out: past a porch roof, where the sky is open.
            search:for(Direction d:new Direction[]{facing,facing.getOpposite()})for(int k=1;k<=2;k++){
                BlockPos step=door.relative(d,k);
                if(v.level.hasChunkAt(step)&&v.level.canSeeSky(step)&&v.safeFeet(step)){out.add(step);break search;}
            }
        }
        for(long light:v.state.lights){
            BlockPos torch=BlockPos.of(light),post=torch.below();
            if(v.level.hasChunkAt(post)&&v.level.getBlockState(post).is(BlockTags.FENCES)&&v.level.canSeeSky(torch))out.add(post);
        }
        return out;
    }
    /** The ground beside a gate on the bell's side, so the guard does not walk through it; the gate itself if none is free. */
    private static BlockPos inside(Village v,BlockPos gate,BlockPos hub){
        BlockPos best=gate;double d=Double.MAX_VALUE;
        if(!v.level.hasChunkAt(gate))return gate;
        for(Direction side:Direction.Plane.HORIZONTAL){BlockPos p=gate.relative(side);if(v.level.hasChunkAt(p)&&v.safeFeet(p)&&p.distSqr(hub)<d){d=p.distSqr(hub);best=p;}}
        return best;
    }

    /** The order of the stops and which of them tonight's routes could not reach; no world access, so tests drive it. */
    static final class Round {
        final List<BlockPos> stops;
        final Map<BlockPos,Integer> misses=new HashMap<>();
        int next;
        Round(List<BlockPos> stops,int first){this.stops=new ArrayList<>(stops);next=stops.isEmpty()?0:Math.floorMod(first,stops.size());}
        BlockPos current(){return stops.isEmpty()?null:stops.get(next);}
        void arrived(){if(!stops.isEmpty())next=(next+1)%stops.size();}
        /** A stop is dropped for the night after its second failed route; the round goes on with the next one. */
        void missed(){
            BlockPos stop=current();if(stop==null)return;
            if(misses.merge(stop,1,Integer::sum)>=2){stops.remove(next);if(next>=stops.size())next=0;}
            else arrived();
        }
    }

    @Override String kind(){return "patrol";}
    /** The whole night and a little more; the watch itself ends at dawn, so a guard never "fails" for the morning. */
    @Override int timeLimit(){return watch+600;}
    @Override boolean tick(){
        if(!Worker.nightRestWindow(v.level.getOverworldClockTime(),v.level.isDarkOutside())||now()-started>=watch)return false;
        if(body().getHealth()<10){w.cooldown("patrol",2400);return fail("ранен, уходит с дозора");}
        if(now()<waitUntil)return true;
        BlockPos stop=round.current();
        // Every post out of reach tonight: the guard is most likely stuck, and asking for routes till dawn does not help.
        if(stop==null){w.cooldown("patrol",Math.max(200,watch-(now()-started)));return fail("не дойти ни до одного поста");}
        boolean onTower=stop.equals(lookout);
        if(walk==null)walk=new Walk(Vec3.atBottomCenterOf(stop),onTower?1.5:3);
        switch(walk.tick()){
            case MOVING->{return true;}
            case FAILED->round.missed();
            case ARRIVED->{waitUntil=now()+(onTower?600:100);status=onTower?"дозор на вышке":"ночной дозор";round.arrived();}
        }
        walk=null;
        return true;
    }
    @Override void stop(){if(walk!=null)walk.cancel();}
}
