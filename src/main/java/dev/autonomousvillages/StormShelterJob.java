package dev.autonomousvillages;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Caught outside in a thunderstorm, a resident goes to the nearest free indoor cell of a village house (found
 * as the night shelter finds it, never a doorway) and waits there, still, until the thunder stops.
 */
final class StormShelterJob extends Job {
    final BlockPos cell;
    private Walk walk;
    private boolean holding;
    private StormShelterJob(Worker w,BlockPos cell){super(w,"бежит под крышу от грозы");this.cell=cell.immutable();}
    /** Null when no free indoor cell is known near the resident. */
    static StormShelterJob find(Worker w){var night=ShelterJob.find(w);return night==null?null:new StormShelterJob(w,night.cell);}
    /** Already under a village roof: waits out the storm where it stands instead of walking out to work. */
    static StormShelterJob stay(Worker w,BlockPos here){return w.v.ready(here,w.id)?new StormShelterJob(w,here):null;}
    @Override String kind(){return "storm";}
    @Override BlockPos target(){return cell;}
    /** The thunder ends the job; a vanilla thunderstorm lasts 15600 ticks at most. */
    @Override int timeLimit(){return 18_000;}
    @Override boolean tick(){
        if(!v.level.isThundering())return false;
        if(!v.reservations.acquire(cell.asLong(),w.id,now()))return false;
        Vec3 point=v.feetPosition(cell);
        double away=body().position().distanceToSqr(point);
        if(away<=1.2||away<=6.25&&ShelterJob.indoor(v,body().blockPosition())&&!Lighting.byDoor(v,body().blockPosition())){
            // Manual zero input keeps the native brain from walking the body back out into the rain.
            if(!holding){bot().actions().setMovement(0,0,false,false,false);holding=true;}
            status="пережидает грозу под крышей";
            return true;
        }
        if(holding){bot().actions().stop();holding=false;}
        if(walk==null)walk=new Walk(point,.6);
        return switch(walk.tick()){
            case FAILED->{w.cooldown("storm",1200);yield fail("не дойти до укрытия");}
            case ARRIVED->{walk=null;yield true;}
            default->{status="бежит под крышу от грозы";yield true;}
        };
    }
    @Override void stop(){if(walk!=null)walk.cancel();if(holding)bot().actions().stop();holding=false;}
}
