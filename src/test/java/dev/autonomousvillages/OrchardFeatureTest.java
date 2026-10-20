package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import static dev.autonomousvillages.OrchardFeature.*;

import com.google.gson.Gson;
import java.util.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class OrchardFeatureTest {
    /** A flat grass field: the first open cell is y=64 everywhere unless the test marks the column otherwise. */
    static final class Field implements OrchardFeature.Ground {
        final Set<Long> busy=new HashSet<>(),bare=new HashSet<>(),unplantable=new HashSet<>(),unloaded=new HashSet<>();
        final Map<Long,Integer> tops=new HashMap<>();
        int looked;
        static long col(int x,int z){return BlockPos.asLong(x,0,z);}
        @Override public int top(int x,int z){looked++;return unloaded.contains(col(x,z))?Integer.MIN_VALUE:tops.getOrDefault(col(x,z),64);}
        @Override public boolean ground(BlockPos c){return !bare.contains(col(c.getX(),c.getZ()));}
        @Override public boolean plantable(BlockPos c){return ground(c)&&!unplantable.contains(col(c.getX(),c.getZ()));}
        @Override public boolean busy(BlockPos c){return busy.contains(col(c.getX(),c.getZ()));}
        static void mark(Set<Long> set,int x0,int z0,int x1,int z1){for(int x=x0;x<=x1;x++)for(int z=z0;z<=z1;z++)set.add(col(x,z));}
    }
    static final BlockPos HUB=new BlockPos(0,64,0);
    static final int MIN=-40,MAX=40;
    static BlockPos site(Field f){return chooseSite(MIN,MIN,MAX,MAX,HUB,f,SEARCH_BUDGET);}
    /** The square and its crowns lie inside the bounds, off the plaza, and nothing busy is under them. */
    static void assertFree(Field f,BlockPos site){
        assertNotNull(site);
        assertTrue(site.getX()-MARGIN>=MIN&&site.getZ()-MARGIN>=MIN&&site.getX()+SIZE-1+MARGIN<=MAX&&site.getZ()+SIZE-1+MARGIN<=MAX,"inside the bounds: "+site);
        assertTrue(Math.hypot(site.getX()+SIZE/2-HUB.getX(),site.getZ()+SIZE/2-HUB.getZ())>=MIN_HUB,"off the plaza");
        for(int x=site.getX()-MARGIN;x<site.getX()+SIZE+MARGIN;x++)for(int z=site.getZ()-MARGIN;z<site.getZ()+SIZE+MARGIN;z++)
            assertFalse(f.busy.contains(Field.col(x,z)),"busy ground under the orchard at "+x+","+z);
        for(BlockPos t:grid(site))assertFalse(f.unplantable.contains(Field.col(t.getX(),t.getZ())),"a tree cell nothing grows on: "+t);
    }

    @Test void gridIsThreeRowsOfThreeFourBlocksApartInsideTheSquare(){
        var corner=new BlockPos(10,64,20);
        var cells=grid(corner);
        assertEquals(9,cells.size());
        assertEquals(9,new HashSet<>(cells).size());
        assertEquals(corner,cells.getFirst());
        assertTrue(cells.contains(new BlockPos(18,64,28)));
        for(BlockPos c:cells){
            assertEquals(0,(c.getX()-corner.getX())%SPACING);assertEquals(0,(c.getZ()-corner.getZ())%SPACING);
            assertTrue(c.getX()>=10&&c.getX()<10+SIZE&&c.getZ()>=20&&c.getZ()<20+SIZE&&c.getY()==64);
            for(BlockPos o:cells)if(o!=c)assertTrue(Math.max(Math.abs(o.getX()-c.getX()),Math.abs(o.getZ()-c.getZ()))>=SPACING,"trees too close");
        }
    }

    @Test void openFieldGetsAnOrchardAtTheEdgeOfTheVillage(){
        var f=new Field();
        BlockPos site=site(f);
        assertFree(f,site);
        assertEquals(64,site.getY());
        assertEquals(0,edgeGap(site.getX(),site.getZ(),MIN,MIN,MAX,MAX),"the crowns reach the edge, not the middle of the village");
        // Of the edge sites the one nearest the bell: the middle of the west side, first in a tie.
        assertEquals(new BlockPos(-38,64,-4),site);
    }

    @Test void candidatesStayInsideTheBoundsOffThePlazaBestFirst(){
        var list=candidates(MIN,MIN,MAX,MAX,HUB);
        assertFalse(list.isEmpty());
        double last=-1;
        for(BlockPos c:list){
            assertTrue(c.getX()-MARGIN>=MIN&&c.getX()+SIZE-1+MARGIN<=MAX&&c.getZ()-MARGIN>=MIN&&c.getZ()+SIZE-1+MARGIN<=MAX);
            double d=Math.hypot(c.getX()+SIZE/2,c.getZ()+SIZE/2);
            assertTrue(d>=MIN_HUB&&d<=MAX_HUB);
            assertTrue(edgeGap(c.getX(),c.getZ(),MIN,MIN,MAX,MAX)<=EDGE_BAND);
            double score=score(c,MIN,MIN,MAX,MAX,HUB);assertTrue(score>=last);last=score;
        }
        // A village too small for an orchard away from its bell has no candidates at all.
        assertTrue(candidates(-12,-12,12,12,HUB).isEmpty());
    }

    @Test void siteKeepsOffPathsFieldsPlansAndWater(){
        var f=new Field();
        // A street along the whole west side, a field in the south, water and a plan in the east.
        Field.mark(f.busy,-31,MIN,-30,MAX);
        Field.mark(f.busy,MIN,30,MAX,31);
        Field.mark(f.busy,34,-10,36,10);
        Field.mark(f.busy,26,-30,27,-20);
        BlockPos site=site(f);
        assertFree(f,site);
        assertNotEquals(new BlockPos(-38,64,-4),site);
        // A path one block off the crowns is fine: the trees leave it free.
        var g=new Field();Field.mark(g.busy,-25,MIN,-25,MAX);
        assertEquals(new BlockPos(-38,64,-4),site(g));
    }

    @Test void treeCellsMustTakeASaplingAndTheSquareBeFlatNaturalGround(){
        var f=new Field();
        f.unplantable.add(Field.col(-38,-4));
        BlockPos site=site(f);
        assertFree(f,site);assertNotEquals(new BlockPos(-38,64,-4),site);
        // A paved or built cell between the trees spoils the square; outside it, under the crowns, it does not.
        var g=new Field();g.bare.add(Field.col(-35,-1));
        assertNotEquals(new BlockPos(-38,64,-4),site(g));
        var h=new Field();h.bare.add(Field.col(-39,-1));
        assertEquals(new BlockPos(-38,64,-4),site(h));
        // Two blocks of slope are an orchard on a hillside; three are a cliff.
        var hill=new Field();hill.tops.put(Field.col(-30,4),66);
        assertEquals(new BlockPos(-38,64,-4),site(hill));
        var cliff=new Field();cliff.tops.put(Field.col(-30,4),67);
        assertNotEquals(new BlockPos(-38,64,-4),site(cliff));
        // Far above or below the bell is no village edge.
        var high=new Field();
        for(int x=MIN;x<=MAX;x++)for(int z=MIN;z<=MAX;z++)high.tops.put(Field.col(x,z),64+MAX_RISE+1);
        assertNull(site(high));
    }

    @Test void noSiteWhereNothingFitsIsLoadedOrTheBudgetIsSpent(){
        var full=new Field();Field.mark(full.busy,MIN,MIN,MAX,MAX);
        assertNull(site(full));
        var dark=new Field();Field.mark(dark.unloaded,MIN-4,MIN-4,MAX+4,MAX+4);
        assertNull(site(dark));
        var small=new Field();Field.mark(small.busy,MIN,MIN,MAX,MAX);
        assertNull(chooseSite(MIN,MIN,MAX,MAX,HUB,small,300));
        assertTrue(small.looked<300+2*(SIZE+2*MARGIN)*(SIZE+2*MARGIN),"the search stops near its budget: "+small.looked);
    }

    @Test void roundOncePerGameDayInWorkingHours(){
        assertTrue(careDue(-1,0));
        assertFalse(careDue(5,5));
        assertTrue(careDue(5,6));
        assertTrue(careDue(5,3),"a clock set back is another day");
        assertFalse(workingHours(500));
        assertTrue(workingHours(FROM_HOUR));
        assertTrue(workingHours(TO_HOUR-1));
        assertFalse(workingHours(TO_HOUR),"the evening gathering");
        assertFalse(workingHours(18000),"night");
    }

    @Test void onlySaplingsBeyondTheReserveAndSpareBoneMeal(){
        int reserve=CompostJob.SAPLING_RESERVE;
        assertEquals(4,spareSaplings(reserve+4,reserve+4));
        assertEquals(5,spareSaplings(5,reserve+20),"the reserve is of any kind of sapling");
        assertEquals(0,spareSaplings(reserve,reserve));
        assertEquals(0,spareSaplings(0,50));
        assertEquals(1,spareSaplings(30,reserve+1));
        assertTrue(spareSaplings(reserve+3,reserve+3)<MIN_SAPLINGS,"no site for three saplings");
        assertEquals(0,spareMeal(0));
        assertEquals(0,spareMeal(BONE_MEAL_RESERVE));
        assertEquals(1,spareMeal(BONE_MEAL_RESERVE+1));
        assertEquals(MEAL_PER_DAY,spareMeal(100));
    }

    @Test void orchardTreeLookupCoversTrunkAndCrownOnly(){
        var trunks=List.of(new BlockPos(10,64,10).asLong(),new BlockPos(14,64,10).asLong());
        assertTrue(nearTrunk(trunks,new BlockPos(10,64,10)),"the trunk's base");
        assertTrue(nearTrunk(trunks,new BlockPos(10,70,10)),"up the trunk");
        assertTrue(nearTrunk(trunks,new BlockPos(17,67,13)),"the crown of the second tree");
        assertFalse(nearTrunk(trunks,new BlockPos(18,67,10)),"beyond the crown");
        assertFalse(nearTrunk(trunks,new BlockPos(10,62,10)),"under the ground");
        assertFalse(nearTrunk(trunks,new BlockPos(10,64+TREE_HEIGHT+1,10)),"above the tree");
        assertFalse(nearTrunk(List.of(),new BlockPos(10,64,10)));
    }

    @Test void stateSurvivesTheSaveAndIsSanitized(){
        var s=new OrchardFeature.State();
        s.site=new BlockPos(-38,64,-4).asLong();s.trees.add(new BlockPos(-38,64,-4).asLong());s.careDay=3;s.apples=2;s.announced=true;
        var gson=new Gson();
        var back=gson.fromJson(gson.toJson(s),OrchardFeature.State.class).sane();
        assertEquals(s.site,back.site);assertEquals(s.trees,back.trees);assertEquals(3,back.careDay);assertEquals(2,back.apples);assertTrue(back.announced);assertFalse(back.firstApple);
        var empty=gson.fromJson("{\"trees\":null}",OrchardFeature.State.class).sane();
        assertNotNull(empty.trees);assertNull(empty.site);assertEquals(-1,empty.careDay);
        var many=new OrchardFeature.State();for(long i=0;i<12;i++)many.trees.add(i);
        assertEquals(ROWS*ROWS,many.sane().trees.size());
    }
}
