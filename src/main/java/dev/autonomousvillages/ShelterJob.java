package dev.autonomousvillages;

import java.util.Comparator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.phys.Vec3;

/**
 * A resident without a bed spends the night indoors: on the floor of a village house, under its roof,
 * standing still until morning instead of wandering where zombies walk.
 */
final class ShelterJob extends Job {
    final BlockPos cell;
    private Walk walk;
    private boolean holding;
    private ShelterJob(Worker w,BlockPos cell){super(w,"ночует под крышей");this.cell=cell;}
    /** Null when no free indoor cell is known near the homes. */
    static ShelterJob find(Worker w){
        var v=w.v;BlockPos from=w.body().blockPosition();
        BlockPos best=null;double score=Double.MAX_VALUE;
        for(BlockPos bed:v.index.beds){
            if(bed.distSqr(from)>64*64)continue;
            for(BlockPos q:BlockPos.betweenClosed(bed.offset(-4,-1,-4),bed.offset(4,1,4))){
                BlockPos p=q.immutable();
                if(!indoor(v,p)||!v.ready(p,w.id))continue;
                double d=p.distSqr(from);
                if(d<score){score=d;best=p;}
            }
        }
        return best==null?null:new ShelterJob(w,best);
    }
    static boolean indoor(Village v,BlockPos p){
        return v.town.protects(p.below())&&v.level.hasChunkAt(p)&&!v.level.canSeeSky(p)&&!v.level.canSeeSky(p.above())
                &&!(v.level.getBlockState(p).getBlock() instanceof BedBlock)&&!(v.level.getBlockState(p.below()).getBlock() instanceof BedBlock)
                &&v.safeFeet(p);
    }
    @Override String kind(){return "shelter";}
    @Override BlockPos target(){return cell;}
    @Override int timeLimit(){return 14_000;}
    @Override boolean tick(){
        if(!Worker.nightRestWindow(v.level.getOverworldClockTime(),v.level.isDarkOutside()))return false;
        Vec3 point=v.feetPosition(cell);
        if(body().position().distanceToSqr(point)<=1.2){
            // Manual zero input keeps the native brain from walking the body back outside.
            if(!holding){bot().actions().setMovement(0,0,false,false,false);holding=true;status="ночует под крышей";}
            return true;
        }
        if(holding){bot().actions().stop();holding=false;}
        if(walk==null)walk=new Walk(point,.6);
        return switch(walk.tick()){
            case FAILED->{w.cooldown("shelter",1200);yield fail("не дойти до укрытия");}
            case ARRIVED->{walk=null;yield true;}
            default->{status="идёт в укрытие";yield true;}
        };
    }
    @Override void stop(){if(walk!=null)walk.cancel();if(holding)bot().actions().stop();holding=false;}
}
