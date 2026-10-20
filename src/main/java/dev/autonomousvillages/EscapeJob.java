package dev.autonomousvillages;

import dev.navigationai.api.NavigationPolicy;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * A worker that keeps failing to walk anywhere from the same spot is trapped (a pit left by a tree,
 * a ledge, a flooded hole). It digs only as far as it must: by day to the nearest open ground around,
 * at night to the nearest house, the bell last; the library's break permission still protects
 * buildings, farmland and private chests.
 */
final class EscapeJob extends Job {
    static final NavigationPolicy DIG=new NavigationPolicy(true,true,true,true,32_000,1024,32,200,3);
    /** A place to dig to and how close is out. */
    record Goal(BlockPos pos,double radius){}
    private ArrayDeque<Goal> goals;
    private Walk walk;
    EscapeJob(Worker w){super(w,"выбирается из ловушки");}
    @Override String kind(){return "escape";}
    @Override String activity(){return "mine";}
    @Override int timeLimit(){return 2400;}
    @Override boolean tick(){
        if(goals==null){
            BlockPos from=body().blockPosition();boolean night=w.hiding();
            var houses=v.index.beds.stream().filter(b->b.distSqr(from)<=48*48).toList();
            goals=new ArrayDeque<>(goals(from,night,night?List.of():openGround(from),houses,v.meetingPoint()));
        }
        if(walk==null){
            Goal goal=goals.poll();
            if(goal==null)return fail("не выбраться");
            walk=new Walk(v.feetPosition(goal.pos()),goal.radius(),DIG);
        }
        return switch(walk.tick()){
            case MOVING->true;
            // That way is shut: the next goal, the bell last.
            case FAILED->{walk=null;yield true;}
            default->false;
        };
    }
    @Override void stop(){if(walk!=null)walk.cancel();}

    /**
     * Where to dig to, in order: by day the two nearest open cells, then the nearest house; at night the two
     * nearest houses; the bell always last. At most four goals, so the job ends within its time limit.
     */
    static List<Goal> goals(BlockPos from,boolean night,List<BlockPos> open,List<BlockPos> houses,BlockPos hub){
        var near=Comparator.comparingDouble((BlockPos p)->p.distSqr(from));
        var out=new ArrayList<Goal>();
        if(!night)open.stream().sorted(near).limit(2).forEach(p->out.add(new Goal(p,1.5)));
        houses.stream().sorted(near).limit(night?2:1).forEach(p->out.add(new Goal(p,3)));
        out.add(new Goal(hub,6));
        return out;
    }
    /** Open walkable ground a few blocks away: the surface, out of the pit, with room around it. */
    private List<BlockPos> openGround(BlockPos from){
        var out=new ArrayList<BlockPos>();
        for(int dx=-10;dx<=10;dx+=2)for(int dz=-10;dz<=10;dz+=2){
            if(dx*dx+dz*dz<9)continue;
            int x=from.getX()+dx,z=from.getZ()+dz;
            if(!v.level.hasChunkAt(new BlockPos(x,from.getY(),z)))continue;
            BlockPos p=new BlockPos(x,v.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z),z);
            if(Math.abs(p.getY()-from.getY())>8||Lighting.unreachable(v,p)||!v.safeFeet(p))continue;
            int room=0;for(Direction d:Direction.Plane.HORIZONTAL)if(v.level.hasChunkAt(p.relative(d))&&v.safeFeet(p.relative(d)))room++;
            if(room>=3)out.add(p);
        }
        return out;
    }
}
