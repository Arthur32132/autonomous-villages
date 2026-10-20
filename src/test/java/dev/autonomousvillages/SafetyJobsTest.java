package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.function.Function;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SafetyJobsTest {
    @BeforeAll static void bootstrapRegistries(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}

    /** Solid cells and doors (a door answers for both halves); a column is open to the sky above anything solid. */
    private static final class World implements ShelterJob.Cells {
        final Set<BlockPos> solid=new HashSet<>(),doors=new HashSet<>();
        @Override public boolean solid(BlockPos p){return solid.contains(p);}
        @Override public boolean door(BlockPos p){return doors.contains(p)||doors.contains(p.below());}
        @Override public boolean sky(BlockPos p){for(int y=p.getY()+1;y<100;y++){BlockPos q=new BlockPos(p.getX(),y,p.getZ());if(solid.contains(q)||door(q))return false;}return true;}
        void fill(int x0,int y0,int z0,int x1,int y1,int z1){for(int x=x0;x<=x1;x++)for(int y=y0;y<=y1;y++)for(int z=z0;z<=z1;z++)solid.add(new BlockPos(x,y,z));}
    }
    /** A 7×7 house (5×5 room) on a floor at 63, roof at 66, a door in the north wall and a covered porch in front of it. */
    private static World house(boolean door,boolean roof){
        var w=new World();
        w.fill(-4,63,-4,10,63,10);
        for(int x=0;x<=6;x++)for(int z=0;z<=6;z++)if(x==0||x==6||z==0||z==6)w.fill(x,64,z,x,65,z);
        w.solid.remove(new BlockPos(3,64,0));w.solid.remove(new BlockPos(3,65,0));
        if(door)w.doors.add(new BlockPos(3,64,0));
        if(roof)w.fill(0,66,0,6,66,6);
        w.fill(1,66,-2,5,66,-1);
        return w;
    }
    private static final BlockPos CENTRE=new BlockPos(3,64,3);

    @Test void onlyCellsInsideWallsAndUnderARoofAreIndoors(){
        var w=house(true,true);
        assertTrue(ShelterJob.enclosed(w,CENTRE));
        assertTrue(ShelterJob.enclosed(w,new BlockPos(1,64,1)));
        assertFalse(ShelterJob.enclosed(w,new BlockPos(3,64,0)),"the doorway");
        assertFalse(ShelterJob.enclosed(w,new BlockPos(3,64,1)),"the step inside the door");
        assertFalse(ShelterJob.enclosed(w,new BlockPos(3,64,-1)),"the doorstep under the porch roof");
        assertFalse(ShelterJob.enclosed(w,new BlockPos(1,64,-1)),"the covered porch, open in front");
        assertFalse(ShelterJob.enclosed(w,new BlockPos(3,64,-6)),"open ground");
        assertFalse(ShelterJob.enclosed(house(true,false),CENTRE),"a house without a roof");
        assertFalse(ShelterJob.enclosed(house(false,true),CENTRE),"an open doorway lets the sky in");
        // A canopy far overhead hides the sky but is no roof.
        var trees=house(true,false);trees.fill(0,75,0,6,75,6);
        assertFalse(ShelterJob.enclosed(trees,CENTRE));
    }
    @Test void deeperCellsAreChosenOverOnesByTheWallAndTheDoor(){
        var w=house(true,true);var doors=List.of(new BlockPos(3,64,0));
        int centre=ShelterJob.depth(w,CENTRE,doors),corner=ShelterJob.depth(w,new BlockPos(1,64,1),doors),byDoor=ShelterJob.depth(w,new BlockPos(2,64,1),doors);
        assertTrue(centre>corner);assertTrue(corner>=byDoor);assertTrue(centre>byDoor);
        // From the doorstep the centre is two steps farther than the cell by the door, and still the better shelter.
        BlockPos from=new BlockPos(3,64,-1);
        assertTrue(ShelterJob.score(CENTRE.distSqr(from),centre)<ShelterJob.score(new BlockPos(2,64,1).distSqr(from),byDoor));
    }
    @Test void houseFloorCellsAreTheEmptyCellsAbovePlannedBlocks(){
        var b=new TownBuilding("house",BlockPos.ZERO);
        for(int x=0;x<3;x++)for(int z=0;z<3;z++)b.add(new BlockPos(x,63,z),Blocks.COBBLESTONE.defaultBlockState());
        b.add(new BlockPos(0,64,0),Blocks.COBBLESTONE.defaultBlockState());
        var floor=ShelterJob.floor(b);
        assertEquals(9,floor.size());
        assertFalse(floor.contains(new BlockPos(0,64,0)));
        assertEquals(new BlockPos(0,65,0),floor.getLast());
        assertTrue(floor.subList(0,8).stream().allMatch(p->p.getY()==64));
    }

    @Test void patrolWalksOutdoorPointsAroundTheBellFromTheTower(){
        BlockPos hub=new BlockPos(0,64,0),lookout=new BlockPos(1,69,1);
        var points=List.of(new BlockPos(10,64,0),new BlockPos(0,64,10),new BlockPos(-10,64,0),new BlockPos(0,64,-10),hub,new BlockPos(10,64,0));
        var gates=List.of(new BlockPos(20,64,20));
        var loop=PatrolJob.route(hub,lookout,points,gates,7);
        assertEquals(lookout,loop.get(0));assertEquals(hub,loop.get(1));
        assertEquals(List.of(new BlockPos(0,64,-10),new BlockPos(10,64,0),new BlockPos(20,64,20),new BlockPos(0,64,10),new BlockPos(-10,64,0)),loop.subList(2,loop.size()));
        assertEquals(hub,PatrolJob.route(hub,null,points,gates,7).getFirst());
    }
    @Test void eachGuardTakesAShareOfAManyStopVillage(){
        BlockPos hub=BlockPos.ZERO;var points=new ArrayList<BlockPos>();
        for(int i=0;i<30;i++)points.add(new BlockPos((int)Math.round(20*Math.cos(i*Math.PI/15)),64,(int)Math.round(20*Math.sin(i*Math.PI/15))));
        var gates=List.of(new BlockPos(30,64,0),new BlockPos(-30,64,0),new BlockPos(0,64,30),new BlockPos(0,64,-30));
        var a=PatrolJob.route(hub,null,points,gates,0);var b=PatrolJob.route(hub,null,points,gates,1);
        assertTrue(a.size()<=1+PatrolJob.MAX_STOPS+2);
        assertEquals(2,a.stream().filter(gates::contains).count());
        assertNotEquals(a,b);
        for(int i=2;i<a.size();i++)assertTrue(Math.atan2(a.get(i-1).getZ(),a.get(i-1).getX())<=Math.atan2(a.get(i).getZ(),a.get(i).getX()));
    }
    @Test void aStopNoRouteReachesIsDroppedAfterTwoMisses(){
        BlockPos a=new BlockPos(1,0,0),b=new BlockPos(2,0,0),c=new BlockPos(3,0,0);
        var round=new PatrolJob.Round(List.of(a,b,c),0);
        round.arrived();assertEquals(b,round.current());
        round.missed();assertEquals(c,round.current());assertEquals(3,round.stops.size());
        round.arrived();round.arrived();assertEquals(b,round.current());
        round.missed();assertEquals(List.of(a,c),round.stops);assertEquals(c,round.current());
        round.missed();round.arrived();round.missed();assertEquals(List.of(a),round.stops);assertEquals(a,round.current());
        round.missed();round.missed();assertNull(round.current());
        round.missed();round.arrived();assertNull(round.current());
    }
    @Test void theWatchLastsTheWholeNight(){
        assertEquals(13_500,PatrolJob.nightLeft(11_500));
        assertEquals(13_500,PatrolJob.nightLeft(24_000*5+11_500));
        assertEquals(500,PatrolJob.nightLeft(500));
        assertEquals(24_000,PatrolJob.nightLeft(1_000),"dark at dawn: the watch goes on while it stays dark");
        // The old 13000-tick limit ended every dusk patrol before the morning.
        assertTrue(PatrolJob.nightLeft(11_500)>13_000);
        for(long clock=11_500;clock<25_000;clock+=250){
            long end=clock+PatrolJob.nightLeft(clock);
            assertTrue(Worker.nightRestWindow(end-1,false));assertFalse(Worker.nightRestWindow(end,false));
        }
    }

    /** A plot with one free grave, every other plot full. */
    private static Function<BlockPos,GraveJob.Room> only(BlockPos plot,BlockPos grave){return o->o.equals(plot)?new GraveJob.Room(grave,false):GraveJob.Room.FULL;}
    @Test void aFullCemeteryIsReplacedAndAFailedSearchWaits(){
        BlockPos old=new BlockPos(20,64,0),fresh=new BlockPos(0,64,20),grave=new BlockPos(2,64,20);
        int[] searches={0};
        var c=new GraveJob.Cemetery(old.asLong(),0);
        assertEquals(grave,c.next(100,only(fresh,grave),()->{searches[0]++;return fresh;}));
        assertEquals(fresh.asLong(),c.corner);assertEquals(1,searches[0]);
        // The new plot has room: no search.
        assertEquals(grave,c.next(200,only(fresh,grave),()->{searches[0]++;return null;}));assertEquals(1,searches[0]);
        // Full and nowhere else to go: one search, then none until the cooldown is over.
        assertNull(c.next(300,o->GraveJob.Room.FULL,()->{searches[0]++;return null;}));assertEquals(2,searches[0]);assertEquals(fresh.asLong(),c.corner);
        assertNull(c.next(300+GraveJob.SEARCH_COOLDOWN-1,o->GraveJob.Room.FULL,()->{searches[0]++;return null;}));assertEquals(2,searches[0]);
        assertEquals(grave,c.next(300+GraveJob.SEARCH_COOLDOWN,only(old,grave),()->{searches[0]++;return old;}));assertEquals(3,searches[0]);
        assertEquals(0,c.retryAt);
        // No cemetery yet and a search finding a plot with no free place waits too.
        var none=new GraveJob.Cemetery(null,0);
        assertNull(none.next(10,o->GraveJob.Room.FULL,()->fresh));assertNull(none.corner);assertEquals(10+GraveJob.SEARCH_COOLDOWN,none.retryAt);
    }
    @Test void aCemeteryOnlyUnloadedOrBlockedForAWhileStays(){
        BlockPos plot=new BlockPos(20,64,0),grave=new BlockPos(22,64,0);int[] searches={0};
        var c=new GraveJob.Cemetery(plot.asLong(),0);
        assertNull(c.next(100,o->GraveJob.Room.LATER,()->{searches[0]++;return new BlockPos(0,64,20);}));
        assertEquals(0,searches[0]);assertEquals(plot.asLong(),c.corner);assertEquals(0,c.retryAt);
        // Back in loaded chunks: the same plot goes on filling.
        assertEquals(grave,c.next(200,only(plot,grave),()->{searches[0]++;return null;}));assertEquals(0,searches[0]);
    }

    /** Cells by what they are; anything not listed is loaded powder snow, open to building and empty. */
    private static final class Snowfield implements HazardJob.Snow {
        final Set<BlockPos> unloaded=new HashSet<>(),packed=new HashSet<>(),closed=new HashSet<>(),occupied=new HashSet<>();
        @Override public boolean loaded(BlockPos p){return !unloaded.contains(p);}
        @Override public boolean powder(BlockPos p){return !packed.contains(p);}
        @Override public boolean allowed(BlockPos p){return !closed.contains(p);}
        @Override public boolean occupied(BlockPos p){return occupied.contains(p);}
    }
    @Test void snowIsPackedOnlyWhereItIsSafeAndEveryRefusalIsRemembered(){
        BlockPos free=new BlockPos(0,64,0),far=new BlockPos(1,64,0),done=new BlockPos(2,64,0),zone=new BlockPos(3,64,0),child=new BlockPos(4,64,0);
        var w=new Snowfield();w.unloaded.add(far);w.packed.add(done);w.closed.add(zone);w.occupied.add(child);
        var hazards=new LinkedHashSet<>(List.of(free,far,done,zone,child));var rejected=new HashMap<Long,Long>();long now=1000;
        assertEquals(HazardJob.Verdict.PACK,HazardJob.verdict(w,free,true));
        assertTrue(HazardJob.remember(HazardJob.Verdict.PACK,free,hazards,rejected,now));
        assertTrue(hazards.contains(free));assertFalse(rejected.containsKey(free.asLong()));
        // An unloaded cell is left for a while instead of being offered again on the next planner call.
        assertEquals(HazardJob.Verdict.UNLOADED,HazardJob.verdict(w,far,false));
        assertFalse(HazardJob.remember(HazardJob.verdict(w,far,false),far,hazards,rejected,now));
        assertEquals(now+1200,rejected.get(far.asLong()));assertTrue(hazards.contains(far));
        assertEquals(HazardJob.Verdict.GONE,HazardJob.verdict(w,done,false));
        assertFalse(HazardJob.remember(HazardJob.verdict(w,done,false),done,hazards,rejected,now));
        assertFalse(hazards.contains(done));assertFalse(rejected.containsKey(done.asLong()));
        assertEquals(HazardJob.Verdict.CLOSED,HazardJob.verdict(w,zone,false));
        assertFalse(HazardJob.remember(HazardJob.verdict(w,zone,false),zone,hazards,rejected,now));
        assertEquals(now+24_000,rejected.get(zone.asLong()));
        // Someone standing in the snow: walked up to, but never packed over; tried again shortly.
        assertEquals(HazardJob.Verdict.PACK,HazardJob.verdict(w,child,false));
        assertEquals(HazardJob.Verdict.OCCUPIED,HazardJob.verdict(w,child,true));
        assertFalse(HazardJob.remember(HazardJob.Verdict.OCCUPIED,child,hazards,rejected,now));
        assertEquals(now+200,rejected.get(child.asLong()));assertTrue(hazards.contains(child));
        // A shorter wait never cuts a longer rejection short.
        rejected.put(far.asLong(),now+5000);HazardJob.remember(HazardJob.Verdict.UNLOADED,far,hazards,rejected,now);
        assertEquals(now+5000,rejected.get(far.asLong()));
        // An unloaded cell never reads as gone: the index keeps it until the column is surveyed again.
        w.packed.add(far);assertEquals(HazardJob.Verdict.UNLOADED,HazardJob.verdict(w,far,true));
    }

    @Test void aTrappedWorkerDigsToTheNearestWayOutNotToTheBell(){
        BlockPos from=new BlockPos(0,60,0),hub=new BlockPos(40,64,40);
        var open=List.of(new BlockPos(6,64,0),new BlockPos(4,64,0),new BlockPos(9,64,9));
        var houses=List.of(new BlockPos(30,64,0),new BlockPos(12,64,0),new BlockPos(20,64,0));
        var day=EscapeJob.goals(from,false,open,houses,hub).stream().map(EscapeJob.Goal::pos).toList();
        assertEquals(List.of(new BlockPos(4,64,0),new BlockPos(6,64,0),new BlockPos(12,64,0),hub),day);
        var night=EscapeJob.goals(from,true,open,houses,hub).stream().map(EscapeJob.Goal::pos).toList();
        assertEquals(List.of(new BlockPos(12,64,0),new BlockPos(20,64,0),hub),night);
        assertEquals(List.of(hub),EscapeJob.goals(from,true,List.of(),List.of(),hub).stream().map(EscapeJob.Goal::pos).toList());
    }
}
