package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class GatheringTest {
    @BeforeAll static void bootstrapRegistries(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}

    @Test void fuelCoversTheWholeBatch(){
        // Coal smelts 8 items, a plank or a log 1.5.
        assertEquals(2,SmeltJob.fuelFor(16,SmeltJob.COAL_BURN));
        assertEquals(1,SmeltJob.fuelFor(8,SmeltJob.COAL_BURN));
        assertEquals(2,SmeltJob.fuelFor(9,SmeltJob.COAL_BURN));
        assertEquals(11,SmeltJob.fuelFor(16,SmeltJob.WOOD_BURN));
        assertEquals(3,SmeltJob.fuelFor(4,SmeltJob.WOOD_BURN));
        assertEquals(0,SmeltJob.fuelFor(0,SmeltJob.WOOD_BURN));
        assertEquals(6,SmeltJob.smeltable(4,SmeltJob.WOOD_BURN));
        assertEquals(16,SmeltJob.smeltable(2,SmeltJob.COAL_BURN));
        assertEquals(0,SmeltJob.smeltable(3,0));
    }
    @Test void furnaceTakesOnlyWhatItsFuelSmelts(){
        // Sixteen raw iron with two coal: everything goes in.
        assertEquals(new SmeltJob.Batch(2,16),SmeltJob.batch(0,16,64,0,2,64,SmeltJob.COAL_BURN));
        // Only four planks: six items are covered, the other ten go back to the store.
        assertEquals(new SmeltJob.Batch(4,6),SmeltJob.batch(0,16,64,0,4,64,SmeltJob.WOOD_BURN));
        // Eleven planks cover the whole batch; spare fuel stays in the bag.
        assertEquals(new SmeltJob.Batch(11,16),SmeltJob.batch(0,16,64,0,20,64,SmeltJob.WOOD_BURN));
        // Five items queued and one coal in the slot: two more coal smelt the lot.
        assertEquals(new SmeltJob.Batch(2,16),SmeltJob.batch(5,16,59,1,4,63,SmeltJob.COAL_BURN));
        // Fuel already in the furnace counts; no fuel at all loads nothing.
        assertEquals(new SmeltJob.Batch(0,8),SmeltJob.batch(0,16,64,1,0,0,SmeltJob.COAL_BURN));
        assertEquals(new SmeltJob.Batch(0,0),SmeltJob.batch(0,16,64,0,0,0,0));
        // A full input slot takes nothing more, and no fuel is wasted on it.
        assertEquals(new SmeltJob.Batch(0,0),SmeltJob.batch(64,16,0,8,4,56,SmeltJob.COAL_BURN));
    }

    static int burn(Item i){return i==Items.COAL||i==Items.CHARCOAL?SmeltJob.COAL_BURN:SmeltJob.WOOD_BURN;}
    @Test void oneFuelKindIsFetchedForTheBatch(){
        // Mixed wood: the slot takes one species, so the one that smelts the most; enough of one beats more of a mix.
        assertEquals(Items.OAK_PLANKS,SmeltJob.fuelKind(Map.of(Items.OAK_PLANKS,6,Items.BIRCH_PLANKS,5),null,16,GatheringTest::burn));
        assertEquals(Items.BIRCH_PLANKS,SmeltJob.fuelKind(Map.of(Items.OAK_PLANKS,10,Items.BIRCH_PLANKS,11),null,16,GatheringTest::burn));
        // Coal and charcoal are two kinds: the one there is more of.
        assertEquals(Items.CHARCOAL,SmeltJob.fuelKind(Map.of(Items.COAL,1,Items.CHARCOAL,2),null,16,GatheringTest::burn));
        // Coal before planks when both cover the batch, planks when coal is short, whatever coal there is without planks.
        assertEquals(Items.COAL,SmeltJob.fuelKind(Map.of(Items.COAL,2,Items.OAK_PLANKS,20),null,16,GatheringTest::burn));
        assertEquals(Items.OAK_PLANKS,SmeltJob.fuelKind(Map.of(Items.COAL,1,Items.OAK_PLANKS,20),null,16,GatheringTest::burn));
        assertEquals(Items.COAL,SmeltJob.fuelKind(Map.of(Items.COAL,1),null,16,GatheringTest::burn));
        // Fuel already in the slot decides; nothing to burn is no kind.
        assertEquals(Items.SPRUCE_PLANKS,SmeltJob.fuelKind(Map.of(Items.COAL,5),Items.SPRUCE_PLANKS,16,GatheringTest::burn));
        assertNull(SmeltJob.fuelKind(Map.of(),null,16,GatheringTest::burn));
    }
    @Test void theFetchedFuelIsLoadedAndLogsLast(){
        Predicate<Item> log=i->i==Items.OAK_LOG;
        // Planks left from another job next to the fetched coal: the coal goes in.
        assertEquals(Items.COAL,SmeltJob.loadKind(Map.of(Items.COAL,2,Items.OAK_PLANKS,3),Items.COAL,16,GatheringTest::burn,log));
        // Carried logs are building material: they burn only when nothing else does.
        assertEquals(Items.COAL,SmeltJob.loadKind(Map.of(Items.COAL,1,Items.OAK_LOG,5),Items.COAL,16,GatheringTest::burn,log));
        assertEquals(Items.OAK_LOG,SmeltJob.loadKind(Map.of(Items.OAK_LOG,5),Items.COAL,16,GatheringTest::burn,log));
        // The fetched kind ran short: the kind that smelts the most.
        assertEquals(Items.OAK_PLANKS,SmeltJob.loadKind(Map.of(Items.COAL,1,Items.OAK_PLANKS,9),Items.COAL,16,GatheringTest::burn,log));
        assertNull(SmeltJob.loadKind(Map.of(),null,16,GatheringTest::burn,log));
    }

    record Spot(double x,double z){}
    static double apart(Spot a,Spot b){return (a.x()-b.x())*(a.x()-b.x())+(a.z()-b.z())*(a.z()-b.z());}
    @Test void secondSheepIsTheMateOfTheFirst(){
        Spot first=new Spot(0,0),mate=new Spot(3,0),nearWorker=new Spot(-9,0);
        // Nearest the worker (at -10) first: the far sheep has no mate in range, so the pair starts with the next one.
        var byDistance=List.of(nearWorker,first,mate);
        assertEquals(first,HerdJob.pairStart(byDistance,GatheringTest::apart));
        // The second sheep is the one closest to the first, not to the worker.
        assertEquals(mate,HerdJob.partner(first,byDistance,GatheringTest::apart));
        assertNull(HerdJob.partner(nearWorker,byDistance,GatheringTest::apart));
        assertNull(HerdJob.pairStart(List.of(new Spot(0,0),new Spot(20,0)),GatheringTest::apart));
    }

    @Test void logsTouchingABuildAreNoTree(){
        var base=new BlockPos(0,64,0);
        var logs=new HashSet<BlockPos>();for(int y=0;y<5;y++)logs.add(base.above(y));
        logs.add(base.offset(1,5,0)); // a branch, joined corner to corner
        var crafted=new HashSet<BlockPos>();
        var tree=ForestWork.treeLogs(base,base,logs::contains,crafted::contains,p->true);
        assertEquals(6,tree.size());assertEquals(base,tree.getFirst());assertEquals(base.offset(1,5,0),tree.getLast());
        // A protected log or one in a no-felling zone stays, the rest is still a tree.
        assertEquals(5,ForestWork.treeLogs(base,base,logs::contains,crafted::contains,p->!p.equals(base.offset(1,5,0))).size());
        // A log cabin wall joined to the trunk: the plank next to it means none of this is a tree.
        logs.add(base.offset(1,0,1));logs.add(base.offset(1,1,1));crafted.add(base.offset(2,1,1));
        assertTrue(ForestWork.treeLogs(base,base,logs::contains,crafted::contains,p->true).isEmpty());
        assertTrue(VillageIndex.treeLog(base,logs::contains,crafted::contains));
        assertFalse(VillageIndex.treeLog(base.offset(1,1,1),logs::contains,crafted::contains));
        assertFalse(VillageIndex.treeLog(base.offset(5,0,5),logs::contains,crafted::contains));
    }

    @Test void coherentRecipesNeedPlanksOfOneWood(){
        // affordable() checks the chosen wood with covered() against per-item counts of store and bag.
        Map<Item,Integer> mixed=Map.of(Items.OAK_PLANKS,3,Items.SPRUCE_PLANKS,3),none=Map.of();
        // Six mixed planks make no door, and without logs it is not affordable.
        var wood=CraftJob.wood(6,mixed,none);
        assertEquals(0,wood.planks());assertEquals(2,wood.logs());
        assertFalse(wood.covered(mixed,none));
        // Logs of one wood are cut on the spot; one log each of two woods is not enough.
        Map<Item,Integer> birch=Map.of(Items.BIRCH_LOG,2,Items.OAK_LOG,1),split=Map.of(Items.BIRCH_LOG,1,Items.OAK_LOG,1);
        wood=CraftJob.wood(6,mixed,birch);
        assertEquals(Items.BIRCH_LOG,wood.log());assertTrue(wood.covered(mixed,birch));
        assertFalse(CraftJob.wood(6,mixed,split).covered(mixed,split));
        // Planks of one wood are used as they are.
        Map<Item,Integer> oak=Map.of(Items.OAK_PLANKS,6,Items.SPRUCE_PLANKS,2);
        wood=CraftJob.wood(6,oak,none);
        assertEquals(Items.OAK_PLANKS,wood.plank());assertEquals(0,wood.logs());assertTrue(wood.covered(oak,none));
        // A chest takes mixed planks, and logs of any kind for the rest.
        wood=CraftJob.mixed(6,CraftJob.Wood.count(mixed,null));
        assertNull(wood.plank());assertEquals(6,wood.planks());assertTrue(wood.covered(mixed,none));
        wood=CraftJob.mixed(8,6);
        assertEquals(6,wood.planks());assertEquals(1,wood.logs());assertTrue(wood.covered(mixed,birch));assertFalse(wood.covered(mixed,none));
    }
    @Test void theCraftCountsPlanksOfOneWood(){
        // Five oak and four spruce planks in the bag: a door sees five.
        assertEquals(5,CraftJob.sameWood(Map.of(Items.OAK_PLANKS,5,Items.SPRUCE_PLANKS,4)));
        assertEquals(0,CraftJob.sameWood(Map.of()));
    }

    @Test void collectingOutlastsThePickupDelay(){
        long broke=1000,deadline=broke+200;
        // Right after the break nothing can be picked up yet: the worker still waits for the drops.
        assertTrue(Gathering.keepCollecting(false,broke+1,broke,deadline));
        assertTrue(Gathering.keepCollecting(false,broke+Gathering.COLLECT_WINDOW-1,broke,deadline));
        assertFalse(Gathering.keepCollecting(false,broke+Gathering.COLLECT_WINDOW,broke,deadline));
        assertTrue(Gathering.keepCollecting(true,broke+100,broke,deadline));
        assertFalse(Gathering.keepCollecting(true,deadline,broke,deadline));
        assertTrue(Gathering.COLLECT_WINDOW>10);
    }
    @Test void anUnreachableSheepIsLeftAfterTwoFailedRoutes(){
        var strikes=new Gathering.Strikes<UUID>(2);var sheep=new UUID(0,1);
        assertFalse(strikes.strike(sheep));assertFalse(strikes.out(sheep));
        assertTrue(strikes.strike(sheep));assertTrue(strikes.out(sheep));
        assertFalse(strikes.out(new UUID(0,2)));
    }
    @Test void wateredAnswersAreRememberedForAMinute(){
        var cache=new FarmJob.WaterCache();long site=new BlockPos(5,64,5).asLong();
        assertNull(cache.get(site,100));
        cache.put(site,100,true);
        assertEquals(Boolean.TRUE,cache.get(site,100+FarmJob.WaterCache.TTL-1));
        assertNull(cache.get(site,100+FarmJob.WaterCache.TTL));
        cache.put(site,5000,false);
        assertEquals(Boolean.FALSE,cache.get(site,5001));
        assertNull(cache.get(site,10)); // another world with an earlier clock
        for(int i=0;i<FarmJob.WaterCache.MAX+10;i++)cache.put(i,20000,true);
        assertTrue(cache.size()<=FarmJob.WaterCache.MAX);
    }
}
