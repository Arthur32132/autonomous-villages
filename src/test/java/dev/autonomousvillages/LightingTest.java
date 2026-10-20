package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class LightingTest {
    /** A flat world of grass at y=63 (ground up to 63, first open cell 64) with blocks added by the test. */
    static final class World implements Lighting.Cells{
        final Set<BlockPos> solid=new HashSet<>(),air=new HashSet<>(),soil=new HashSet<>(),paths=new HashSet<>(),plan=new HashSet<>();
        final Map<BlockPos,Direction> doors=new HashMap<>();
        final Map<Long,Integer> tops=new HashMap<>();
        @Override public int top(int x,int z){return tops.getOrDefault(BlockPos.asLong(x,0,z),64);}
        @Override public boolean solid(BlockPos p){return !air.contains(p)&&(solid.contains(p)||p.getY()<=63);}
        @Override public boolean ground(BlockPos p){return soil.contains(p)||p.getY()==63&&!air.contains(p)&&!solid.contains(p);}
        @Override public boolean path(BlockPos p){return paths.contains(p);}
        @Override public boolean planned(BlockPos p){return plan.contains(p);}
        @Override public Direction door(BlockPos p){return doors.get(p);}
        /** A 5x5 house on the ground: walls up to y=66, a flat plank roof at y=67 that is part of the plan. */
        void house(int x0,int z0){
            for(int x=x0;x<x0+5;x++)for(int z=z0;z<z0+5;z++){
                BlockPos roof=new BlockPos(x,67,z);solid.add(roof);plan.add(roof);tops.put(BlockPos.asLong(x,0,z),68);
                boolean wall=x==x0||x==x0+4||z==z0||z==z0+4;
                for(int y=64;y<=66;y++)if(wall){solid.add(new BlockPos(x,y,z));plan.add(new BlockPos(x,y,z));}
            }
        }
    }

    @Test void gridSamplesStayOffRoofs(){
        var w=new World();w.house(0,0);
        // The heightmap stops on the roof: that is no place for a torch or a lamp post.
        assertFalse(Lighting.groundSample(w,new BlockPos(2,68,2)));
        assertTrue(Lighting.groundSample(w,new BlockPos(8,64,8)));
        // A roof the plan does not know (a sod roof over a room) is still hollow underneath.
        var sod=new BlockPos(20,66,20);w.soil.add(sod);w.air.add(sod.below());w.tops.put(BlockPos.asLong(20,0,20),67);
        assertFalse(Lighting.groundSample(w,sod.above()));
        // A roof sample moves to open ground beside the house, never stays up there.
        var p=Lighting.groundNear(w,4,2);
        assertNotNull(p);assertEquals(64,p.getY());assertEquals(5,p.getX());
        assertNull(Lighting.groundNear(w,2,2));
    }
    @Test void gridSamplesStepOffPathsAndUnloadedColumns(){
        var w=new World();
        w.paths.add(new BlockPos(9,63,9));
        var p=Lighting.groundNear(w,9,9);
        assertNotNull(p);assertNotEquals(new BlockPos(9,64,9),p);assertEquals(64,p.getY());
        assertFalse(w.path(p.below()));
        w.tops.put(BlockPos.asLong(15,0,3),Integer.MIN_VALUE);
        assertEquals(new BlockPos(16,64,3),Lighting.groundNear(w,15,3));
        // A plan's floor or a planned cell is not open ground either.
        w.plan.add(new BlockPos(30,63,30));
        assertFalse(Lighting.groundSample(w,new BlockPos(30,64,30)));
    }
    @Test void lampPostsKeepPathsDoorsAndAlleysFree(){
        var w=new World();
        assertFalse(Lighting.postInTheWay(w,new BlockPos(0,64,0)));
        w.paths.add(new BlockPos(0,63,0));
        assertTrue(Lighting.postInTheWay(w,new BlockPos(0,64,0)),"on a path");
        // A door facing south at (10,64,10): walkers step out to z=11 and on to z=12.
        w.doors.put(new BlockPos(10,64,10),Direction.SOUTH);w.doors.put(new BlockPos(10,65,10),Direction.SOUTH);
        assertTrue(Lighting.postInTheWay(w,new BlockPos(10,64,12)),"two cells in front of the door");
        assertTrue(Lighting.postInTheWay(w,new BlockPos(10,64,8)),"two cells behind the door");
        assertTrue(Lighting.postInTheWay(w,new BlockPos(11,64,11)),"right beside the door");
        assertFalse(Lighting.postInTheWay(w,new BlockPos(12,64,10)),"two cells along the wall is fine");
        assertFalse(Lighting.postInTheWay(w,new BlockPos(10,64,13)));
        // A door a step lower still counts.
        w.doors.put(new BlockPos(30,63,30),Direction.EAST);
        assertTrue(Lighting.postInTheWay(w,new BlockPos(32,64,30)));
        // One-block gap between two walls: a fence there closes the alley.
        w.solid.add(new BlockPos(50,64,49));w.solid.add(new BlockPos(50,65,51));
        assertTrue(Lighting.postInTheWay(w,new BlockPos(50,64,50)),"one-wide gap");
        assertFalse(Lighting.postInTheWay(w,new BlockPos(51,64,49)),"open on one side");
        w.solid.add(new BlockPos(60,64,60));
        assertFalse(Lighting.postInTheWay(w,new BlockPos(61,64,60)),"a wall on one side only");
    }
    @Test void lightCapGrowsWithTheVillageAndStaysUnderTheRecordLimit(){
        assertEquals(160,Lighting.lightCap(48*48,10),"a small village keeps the old allowance");
        int town=Lighting.lightCap(120L*120,30);
        assertTrue(town>160,"a grown village lights beyond the old fixed 160: "+town);
        assertTrue(town>=120*120/36,"at least one light per grid cell of the footprint");
        assertTrue(Lighting.lightCap(120L*120,40)>town,"more residents, more rooms and doorsteps");
        assertTrue(Lighting.lightCap(4097L*4097,1024)<1024,"under the 1024 lights the save keeps");
        assertTrue(Lighting.lightCap(0,0)>=160);
    }
    @Test void roomScanTakesAFewAnchorsInTurnAndSkipsLitOnes(){
        var scan=new Lighting.RoomScan();
        var anchors=new ArrayList<BlockPos>();for(int i=0;i<7;i++)anchors.add(new BlockPos(i,64,0));
        assertTrue(scan.stale(0));
        scan.anchors(anchors,0);assertFalse(scan.stale(199));assertTrue(scan.stale(200));
        assertEquals(anchors.subList(0,3),scan.next(3,0));
        assertEquals(anchors.subList(3,6),scan.next(3,0));
        assertEquals(List.of(anchors.get(6),anchors.get(0),anchors.get(1)),scan.next(3,0),"wraps round");
        // Anchors found lit are skipped until their mark runs out.
        scan.fine(anchors.get(2),100);scan.fine(anchors.get(3),100);
        assertEquals(List.of(anchors.get(4),anchors.get(5),anchors.get(6)),scan.next(3,50));
        for(var a:anchors)scan.fine(a,100);
        assertTrue(scan.next(3,50).isEmpty(),"a lit village scans nothing");
        assertEquals(3,scan.next(3,100).size(),"and looks again once the marks run out");
        // A shrunken list does not break the cursor.
        scan.anchors(anchors.subList(0,2),300);
        assertEquals(2,scan.next(5,300).size());
        scan.anchors(List.of(),400);assertTrue(scan.next(3,400).isEmpty());
    }
    @Test void aTorchComesOffAPostOnlyWhenItHasSomewhereToGo(){
        assertFalse(LanternJob.room(0,0,0),"bag and store full: the torch stays on the post");
        assertFalse(LanternJob.room(0,128,0),"full torch stacks take no more");
        assertTrue(LanternJob.room(0,10,0),"joins a torch stack in the bag");
        assertTrue(LanternJob.room(1,0,0));
        assertTrue(LanternJob.room(0,0,1));
    }
}
