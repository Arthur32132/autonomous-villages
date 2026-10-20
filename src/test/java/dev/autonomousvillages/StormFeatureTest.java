package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class StormFeatureTest {
    @BeforeAll static void bootstrapRegistries(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}

    private static final BlockPos FEET=new BlockPos(0,64,0);

    @Test void aResidentIsOutdoorsOnlyWithNoRoofWithinSixBlocks(){
        assertTrue(StormFeature.outdoors(p->false,FEET),"open sky");
        for(int k=1;k<=StormFeature.ROOF;k++){
            var roof=FEET.above(k);
            assertFalse(StormFeature.outdoors(p->p.equals(roof),FEET),"a roof "+k+" above");
        }
        var high=FEET.above(StormFeature.ROOF+1);
        assertTrue(StormFeature.outdoors(p->p.equals(high),FEET),"a canopy seven blocks up is no roof");
        assertTrue(StormFeature.outdoors(p->p.equals(FEET)||p.equals(FEET.below()),FEET),"the ground and the feet do not count");
        assertTrue(StormFeature.outdoors(p->p.equals(FEET.above(2).east()),FEET),"a wall beside the head is no roof");
    }

    /** The watchtower's top as Projects plans it: a 5×5 plank lookout at 4, a fence ring at 5, torches on the corners at 6. */
    private static Map<BlockPos,Boolean> tower(){
        var plan=new LinkedHashMap<BlockPos,Boolean>();
        for(int x=1;x<=3;x++)for(int z=1;z<=3;z++)for(int y=0;y<=3;y++)plan.put(new BlockPos(x,y,z),!(x==2&&z==2));
        for(int x=0;x<5;x++)for(int z=0;z<5;z++)if(x!=2||z!=2)plan.put(new BlockPos(x,4,z),true);
        for(int x=0;x<5;x++)for(int z=0;z<5;z++)if(x==0||z==0||x==4||z==4)plan.put(new BlockPos(x,5,z),true);
        for(int[] c:new int[][]{{0,0},{0,4},{4,0},{4,4}})plan.put(new BlockPos(c[0],6,c[1]),false);
        return plan;
    }
    @Test void theRodStandsOnTheTowersTopmostSolidCellWithNothingPlannedAbove(){
        var plan=tower();
        BlockPos site=StormFeature.rodSite(plan.keySet(),p->plan.getOrDefault(p,false));
        assertNotNull(site);
        assertEquals(5,site.getY(),"the fence railing is the top, not the torches");
        assertFalse(plan.containsKey(site.above()),"never under a planned torch");
        // The middle of a railing side, the cell nearest the lookout's middle, chosen the same way every time.
        assertEquals(new BlockPos(0,5,2),site);
        assertNull(StormFeature.rodSite(List.of(new BlockPos(0,0,0)),p->false),"nothing solid, no site");
        // Every bolt within 128 blocks lands on the rod, and its hit box takes in the lookout (Projects.lookout:
        // base+(1,5,1)) where the night watch waits: the storm keeps the watch indoors.
        var strike=StormFeature.strikeBox(Vec3.atBottomCenterOf(site.above()));
        assertTrue(strike.contains(Vec3.atBottomCenterOf(new BlockPos(1,5,1))),"the lookout is under the rod's bolts");
    }
    @Test void onAPitchedBarnRoofTheRodGoesOnTheRidgeAtTheGableABuilderReaches(){
        // Ridge planks at 5 along x from -1 to 7, stairs at 4 below the eaves on both long sides.
        var plan=new LinkedHashMap<BlockPos,Boolean>();
        for(int x=0;x<7;x++)for(int z=0;z<5;z++)plan.put(new BlockPos(x,0,z),true);
        for(int x=-1;x<=7;x++){plan.put(new BlockPos(x,4,-1),true);plan.put(new BlockPos(x,4,5),true);for(int z=0;z<=4;z++)plan.put(new BlockPos(x,5,z),true);}
        BlockPos site=StormFeature.rodSite(plan.keySet(),p->plan.getOrDefault(p,false));
        assertEquals(5,site.getY());
        assertEquals(2,site.getZ(),"on the ridge line");
        assertEquals(-1,site.getX(),"at the gable end, beside an empty column");
    }

    @Test void aRodTakesThreeCopperIngotsAtAWorkbenchAsInVanilla() throws Exception {
        var recipe=StormFeature.ROD;
        assertEquals(Map.of('M',3),recipe.symbols());
        assertTrue(recipe.needsTable(),"a 1×3 column needs the 3×3 grid");
        assertTrue(recipe.material().test(new ItemStack(Items.COPPER_INGOT)));
        assertFalse(recipe.material().test(new ItemStack(Items.RAW_COPPER)),"raw copper is smelted first");
        assertTrue(recipe.output().test(new ItemStack(Items.LIGHTNING_ROD.weathering().unaffected())));
        assertTrue(LightningRodJob.ROD.test(new ItemStack(Items.LIGHTNING_ROD.waxed().oxidized())),"any rod in the store will do");
        try(var in=Bootstrap.class.getResourceAsStream("/data/minecraft/recipe/lightning_rod.json")){
            assertNotNull(in);
            var json=JsonParser.parseReader(new InputStreamReader(in,StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals(StormFeature.ROD_COPPER,json.getAsJsonArray("pattern").size());
            assertEquals("minecraft:copper_ingot",json.getAsJsonObject("key").get("#").getAsString());
        }
        assertFalse(StormFeature.rodAffordable(2));
        assertTrue(StormFeature.rodAffordable(3));
        assertEquals(2,StormFeature.copperToSmelt(1,5),"only what the ingots lack");
        assertEquals(0,StormFeature.copperToSmelt(1,1),"not enough ore: nothing is smelted for the rod");
        assertEquals(0,StormFeature.copperToSmelt(3,10));
    }
    @Test void aFailedRodAttemptWaitsTwiceAsLongUpToADay(){
        long d=StormFeature.ROD_RETRY;
        for(long want:new long[]{2400,4800,9600,19200,24000,24000}){d=StormFeature.backoff(d);assertEquals(want,d);}
    }

    @Test void aResidentLastSeenInTheStrikeAndGoneSinceWasStruck(){
        UUID near=UUID.randomUUID(),alive=UUID.randomUUID(),far=UUID.randomUUID();
        var seen=new LinkedHashMap<UUID,Vec3>();
        seen.put(near,new Vec3(2.4,64,-2.9));seen.put(alive,new Vec3(0.5,64,0.5));seen.put(far,new Vec3(6.5,64,0.5));
        var strike=StormFeature.strikeBox(new Vec3(0.5,65,0.5));
        Set<UUID> gone=Set.of(near,far);
        assertEquals(List.of(near),StormFeature.vanished(seen,strike,gone::contains),"turned into a witch: gone from the world, never listed by the bolt");
        assertEquals(List.of(),StormFeature.vanished(Map.of(),strike,gone::contains));
    }

    @Test void theStormLineIsWrittenOncePerStormWhenEveryoneShelteredAndNobodyWasStruck(){
        var log=new StormFeature.StormLog();
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        assertNull(log.end(),"no storm, no line");
        log.begin();log.sheltered(a);log.begin();log.sheltered(b);
        assertEquals(StormFeature.SHELTERED,log.end());
        assertNull(log.end(),"one line per storm");
        log.begin();
        assertNull(log.end(),"nobody had to shelter");
        log.begin();log.sheltered(a);
        assertTrue(log.struck(b));assertFalse(log.struck(b),"a strike is told once");
        assertNull(log.end(),"someone was struck");
        log.sheltered(a);
        assertNull(log.end(),"between storms nothing is counted");
        log.begin();log.sheltered(a);
        assertEquals(StormFeature.SHELTERED,log.end(),"a new storm starts clean");
        log.begin();log.sheltered(a);log.exposed(true);
        assertNull(log.end(),"someone still under the open sky at the end");
        log.begin();log.sheltered(a);log.exposed(true);log.exposed(false);
        assertEquals(StormFeature.SHELTERED,log.end(),"only the latest pass counts: everyone got in");
        log.begin();log.sheltered(a);log.exposed(true);log.end();
        log.begin();log.sheltered(b);
        assertEquals(StormFeature.SHELTERED,log.end(),"the last storm's stragglers do not spoil the next");
    }
}
