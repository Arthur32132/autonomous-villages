package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Night watch for an armed guard: walks a loop of homes around the meeting point and waits a little
 * at each stop. Fights are started by the worker's own threat check, not by this job.
 */
final class PatrolJob extends Job {
    private final List<BlockPos> stops;
    private int next;
    private Walk walk;
    private long waitUntil;
    PatrolJob(Worker w){
        super(w,"ночной дозор");
        var hub=v.meetingPoint();var homes=new ArrayList<>(v.index.beds);
        homes.sort(Comparator.comparingDouble(p->Math.atan2(p.getZ()-hub.getZ(),p.getX()-hub.getX())));
        var loop=new ArrayList<BlockPos>();loop.add(hub);
        // Every few homes around the centre, so one round passes all sides of the village.
        int step=Math.max(1,homes.size()/6);
        for(int i=Math.floorMod(w.id.hashCode(),step);i<homes.size();i+=step)loop.add(homes.get(i));
        stops=loop;next=Math.floorMod(w.id.hashCode(),stops.size());
    }
    @Override String kind(){return "patrol";}
    @Override int timeLimit(){return 13_000;}
    @Override boolean tick(){
        if(!Worker.nightRestWindow(v.level.getOverworldClockTime(),v.level.isDarkOutside()))return false;
        if(body().getHealth()<10){w.cooldown("patrol",2400);return fail("ранен, уходит с дозора");}
        if(now()<waitUntil)return true;
        if(walk==null)walk=new Walk(Vec3.atBottomCenterOf(stops.get(next)),4);
        switch(walk.tick()){
            case MOVING->{return true;}
            case FAILED->{}
            case ARRIVED->waitUntil=now()+100;
        }
        walk=null;next=(next+1)%stops.size();
        return true;
    }
    @Override void stop(){if(walk!=null)walk.cancel();}
}
