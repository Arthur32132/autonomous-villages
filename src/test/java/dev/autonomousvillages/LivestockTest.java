package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LivestockTest {
    @BeforeAll static void bootstrapRegistries(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}
    static final LivestockFeature.Kind COW=LivestockFeature.Kind.COW,CHICKEN=LivestockFeature.Kind.CHICKEN;

    @Test void breedingStopsAtTheHerdCap(){
        assertEquals(8,COW.cap);assertEquals(10,CHICKEN.cap);
        assertTrue(LivestockFeature.mayBreed(7,2,COW.cap,true,8,6));
        // Eight cows in the yard (calves included): no more breeding.
        assertFalse(LivestockFeature.mayBreed(8,6,COW.cap,true,20,0));
        assertTrue(LivestockFeature.mayBreed(9,2,CHICKEN.cap,true,6,4));
        assertFalse(LivestockFeature.mayBreed(10,2,CHICKEN.cap,true,6,4));
        // A pair is needed, the kind's cooldown must be over, and the feed must cover a pair beyond the reserve.
        assertFalse(LivestockFeature.mayBreed(3,1,COW.cap,true,8,6));
        assertFalse(LivestockFeature.mayBreed(3,2,COW.cap,false,8,6));
        assertFalse(LivestockFeature.mayBreed(3,2,COW.cap,true,7,6));
    }
    @Test void wheatIsBreadFirst(){
        assertEquals(6,LivestockFeature.feedReserve(COW,0));
        assertEquals(6,LivestockFeature.feedReserve(COW,LivestockFeature.BREAD_PLENTY-1));
        assertEquals(0,LivestockFeature.feedReserve(COW,LivestockFeature.BREAD_PLENTY));
        assertEquals(4,LivestockFeature.feedReserve(CHICKEN,100));
        assertFalse(LivestockFeature.spareFeed(5,4));
        assertTrue(LivestockFeature.spareFeed(6,4));
    }
    @Test void breedingCooldownPerKind(){
        assertTrue(LivestockFeature.ready(100,0,LivestockFeature.BREED_COOLDOWN));
        assertFalse(LivestockFeature.ready(100,100+LivestockFeature.BREED_COOLDOWN,LivestockFeature.BREED_COOLDOWN));
        assertTrue(LivestockFeature.ready(100+LivestockFeature.BREED_COOLDOWN,100+LivestockFeature.BREED_COOLDOWN,LivestockFeature.BREED_COOLDOWN));
        // A world whose clock is far behind the saved one (another save, a reset) is not locked out.
        assertTrue(LivestockFeature.ready(100,1_000_000,LivestockFeature.BREED_COOLDOWN));
    }

    @Test void cullingTakesOnlyTheSurplusWhenMealsAreShortOncePerDay(){
        // Nine cows, all adults, four meals in the store, nothing culled today: one goes.
        assertTrue(LivestockFeature.mayCull(9,9,9,COW.cap,4,5,4));
        // Only a surplus: eight cows are the herd, not meat.
        assertFalse(LivestockFeature.mayCull(8,8,8,COW.cap,0,5,4));
        assertFalse(LivestockFeature.mayCull(10,10,10,CHICKEN.cap,0,5,4));
        assertTrue(LivestockFeature.mayCull(11,11,11,CHICKEN.cap,0,5,4));
        // Only while meals are short.
        assertFalse(LivestockFeature.mayCull(12,12,12,COW.cap,LivestockFeature.MEALS_SHORT,5,4));
        assertTrue(LivestockFeature.mayCull(12,12,12,COW.cap,LivestockFeature.MEALS_SHORT-1,5,4));
        // Once a game day.
        assertFalse(LivestockFeature.mayCull(12,12,12,COW.cap,0,5,5));
        // Never below two adults: nine cows of which seven are calves leave nobody to cull.
        assertFalse(LivestockFeature.mayCull(9,2,2,COW.cap,0,5,4));
        assertTrue(LivestockFeature.mayCull(9,3,3,COW.cap,0,5,4));
        // Named, leashed or courting adults are not culled: none left that may be.
        assertFalse(LivestockFeature.mayCull(12,12,0,COW.cap,0,5,4));
        // The on-the-spot recheck uses the same surplus rule.
        assertTrue(LivestockFeature.surplus(9,3,1,COW.cap));
        assertFalse(LivestockFeature.surplus(9,2,1,COW.cap));
    }

    record Spot(double x,double z){}
    static double apart(Spot a,Spot b){return (a.x()-b.x())*(a.x()-b.x())+(a.z()-b.z())*(a.z()-b.z());}
    @Test void breedingPairIsTheHerdJobPair(){
        Spot nearWorker=new Spot(-9,0),first=new Spot(0,0),mate=new Spot(3,0),far=new Spot(40,0);
        var byDistance=List.of(nearWorker,first,mate,far);
        var pair=LivestockJob.pair(byDistance,LivestockTest::apart);
        assertEquals(List.of(first,mate),pair);
        // The same choice the sheep herder makes, and the mate stands within vanilla breeding range of the first.
        assertEquals(HerdJob.pairStart(byDistance,LivestockTest::apart),pair.get(0));
        assertEquals(HerdJob.partner(first,byDistance,LivestockTest::apart),pair.get(1));
        assertTrue(apart(pair.get(0),pair.get(1))<=HerdJob.BREEDING_RANGE*HerdJob.BREEDING_RANGE);
        // Once the first is fed it leaves the candidates; its mate is still the one found for it.
        assertEquals(mate,HerdJob.partner(first,List.of(nearWorker,mate,far),LivestockTest::apart));
        // Asking again gives the same pair: nothing depends on call order.
        assertEquals(pair,LivestockJob.pair(byDistance,LivestockTest::apart));
        assertEquals(List.of(),LivestockJob.pair(List.of(nearWorker,far),LivestockTest::apart));
        assertEquals(List.of(),LivestockJob.pair(List.of(first),LivestockTest::apart));
    }

    @Test void eggRoundOnceADayByDayWhenThereAreEggs(){
        assertTrue(LivestockFeature.eggRoundDue(5,4,3000,2));
        assertFalse(LivestockFeature.eggRoundDue(5,5,3000,2));
        assertFalse(LivestockFeature.eggRoundDue(5,4,500,2));
        assertFalse(LivestockFeature.eggRoundDue(5,4,LivestockFeature.WORK_UNTIL,2));
        assertTrue(LivestockFeature.eggRoundDue(5,4,LivestockFeature.WORK_FROM,1));
        assertFalse(LivestockFeature.eggRoundDue(5,4,3000,0));
        // The first day of a world and a clock set back still get their round.
        assertTrue(LivestockFeature.eggRoundDue(0,new LivestockFeature.State().eggDay,3000,1));
        assertTrue(LivestockFeature.eggRoundDue(3,7,3000,1));
    }

    @Test void birthsAreToldAtMostOnceADay(){
        assertTrue(LivestockFeature.lineDue(2,new LivestockFeature.State().lineDay));
        assertFalse(LivestockFeature.lineDue(2,2));
        assertTrue(LivestockFeature.lineDue(3,2));
        // A calf born 100 ticks after the pair was fed is theirs; an older one is not; adults never are.
        assertTrue(LivestockFeature.bornSince(-23900,1100,1000));
        assertFalse(LivestockFeature.bornSince(-23000,1100,1000));
        assertFalse(LivestockFeature.bornSince(0,1100,1000));
        assertFalse(LivestockFeature.bornSince(-23900,900,1000));
    }

    @Test void yardIsTheVillageAndSixteenBlocksAround(){
        var s=new VillageState();s.x=0;s.y=64;s.z=0;s.radius=32;
        assertTrue(LivestockFeature.inYard(s,new BlockPos(32+LivestockFeature.YARD,64,0)));
        assertFalse(LivestockFeature.inYard(s,new BlockPos(32+LivestockFeature.YARD+1,64,0)));
        s.bounds=new VillageState.Bounds(-10,-20,30,40);
        assertTrue(LivestockFeature.inYard(s,new BlockPos(46,70,56)));
        assertFalse(LivestockFeature.inYard(s,new BlockPos(47,70,0)));
        assertFalse(LivestockFeature.inYard(s,new BlockPos(0,70,-37)));
        assertFalse(LivestockFeature.inYard(s,new BlockPos(0,-65,0)));
        var box=LivestockFeature.yardBox(s);
        assertEquals(-26,box.minX);assertEquals(47,box.maxX);assertEquals(-36,box.minZ);assertEquals(57,box.maxZ);
    }

    @Test void heavierWeaponsHitHarder(){
        assertTrue(LivestockJob.damage(new ItemStack(Items.IRON_AXE))>LivestockJob.damage(new ItemStack(Items.WOODEN_SWORD)));
        assertTrue(LivestockJob.damage(new ItemStack(Items.WOODEN_SWORD))>=4);
        assertEquals(1f,LivestockJob.damage(new ItemStack(Items.WHEAT)));
        // A chicken (4 health) falls to one blow of any sword; a cow (10) to two of a stone axe.
        assertTrue(LivestockJob.damage(new ItemStack(Items.WOODEN_SWORD))>=4);
        assertTrue(2*LivestockJob.damage(new ItemStack(Items.STONE_AXE))>=10);
    }

    @Test void savedStateRoundTrips(){
        var g=new com.google.gson.Gson();
        var s=new LivestockFeature.State();s.readyAt.put("COW",123L);s.cullDay=4;s.pendingKind="CHICKEN";s.pendingAt=99;
        var back=g.fromJson(g.toJson(s),LivestockFeature.State.class);
        assertEquals(123L,back.readyAt.get("COW"));assertEquals(4,back.cullDay);assertEquals("CHICKEN",back.pendingKind);assertEquals(99,back.pendingAt);
        assertEquals(Long.MIN_VALUE,back.eggDay);
    }
}
