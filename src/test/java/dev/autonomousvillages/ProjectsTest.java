package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ProjectsTest {
    @BeforeAll static void bootstrapRegistries(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}

    /** A village of {@code residents} with {@code beds}, nothing under way, the given kinds standing, all features on. */
    private static Projects.Queue village(int beds,int residents,String... built){
        return new Projects.Queue(beds,residents,0,true,false,0,true,false,false,null,0,Set.of(built),Set.of(),new ModConfig());
    }
    private static Projects.Queue with(Projects.Queue q,int houses,boolean housesWaiting,int works,boolean worksWaiting){
        return new Projects.Queue(q.beds(),q.residents(),houses,housesWaiting,q.bedBound(),works,worksWaiting,q.woolWanted(),q.waterAtHub(),q.landmarkDue(),q.workshops(),q.built(),q.cooling(),q.flags());
    }
    private static Projects.Queue cooling(Projects.Queue q,String... kinds){
        return new Projects.Queue(q.beds(),q.residents(),q.houses(),q.housesWaiting(),q.bedBound(),q.works(),q.worksWaiting(),q.woolWanted(),q.waterAtHub(),q.landmarkDue(),q.workshops(),q.built(),Set.of(kinds),q.flags());
    }

    @Test void aHomelessVillageLaysTheBarnBesideItsHouse(){
        // beds < residents+2 used to block every civic project; now one goes up alongside one house.
        var choice=Projects.decide(village(2,6));
        assertTrue(choice.house());assertEquals("storehouse",choice.civic());
        // A house under way (not waiting) holds up the next house only; a barn under way holds up the next civic project only.
        choice=Projects.decide(with(village(2,6),1,false,0,true));
        assertFalse(choice.house());assertEquals("storehouse",choice.civic());
        choice=Projects.decide(with(village(2,6),0,true,1,false));
        assertTrue(choice.house());assertNull(choice.civic());
        // A third house waiting for wool does not make room for a fourth; a civic project that waited a whole day lets the next start.
        assertFalse(Projects.decide(with(village(2,9),3,true,0,true)).house());
        assertTrue(Projects.decide(with(village(2,9),2,true,0,true)).house());
        assertEquals("storehouse",Projects.decide(with(village(2,9),0,true,1,true)).civic());
        assertNull(Projects.decide(with(village(2,9),0,true,2,true)).civic());
    }
    @Test void theChainRunsBarnWellTowerPenPalisadeLandmarksWorkshops(){
        assertEquals("storehouse",Projects.decide(village(9,6)).civic());
        assertEquals("well",Projects.decide(village(9,6,"storehouse")).civic());
        assertEquals("tower",Projects.decide(village(9,6,"storehouse","well")).civic());
        assertEquals("pen",Projects.decide(village(9,6,"storehouse","well","tower")).civic());
        assertEquals("wall",Projects.decide(village(9,6,"storehouse","well","tower","pen")).civic());
        var all=village(9,8,"storehouse","well","tower","pen","wall");
        assertEquals("civic",Projects.decide(all).civic());
        var due=new Projects.Queue(9,8,0,true,false,0,true,false,false,"forge",0,all.built(),Set.of(),new ModConfig());
        assertEquals("forge",Projects.decide(due).civic());
        // Too small a village for a pen or a palisade goes on to its workshops; four residents get a barn but no pen.
        assertEquals("civic",Projects.decide(village(9,4,"storehouse","well","tower")).civic());
        assertNull(Projects.decide(village(9,3)).civic());
        // Enough workshops for the size: nothing more to lay.
        var staffed=new Projects.Queue(9,8,0,true,false,0,true,false,false,null,2,all.built(),Set.of(),new ModConfig());
        assertNull(Projects.decide(staffed).civic());
    }
    @Test void aKindWithoutASiteCoolsDownAndTheNextOneGetsItsTurn(){
        assertEquals("well",Projects.decide(cooling(village(9,6),"storehouse")).civic());
        assertEquals("tower",Projects.decide(cooling(village(9,6),"storehouse","well")).civic());
        assertEquals("pen",Projects.decide(cooling(village(9,6),"storehouse","well","tower")).civic());
        assertNull(Projects.decide(cooling(village(9,6,"storehouse","well","tower","pen","wall"),"civic")).civic());
        assertEquals(2400,Projects.retryTicks("storehouse"));assertEquals(24000,Projects.retryTicks("wall"));
    }
    @Test void flagsAndWaterSkipTheirKinds(){
        var flags=new ModConfig();flags.storehouse=false;flags.watchtower=false;
        assertEquals("pen",Projects.decide(new Projects.Queue(9,6,0,true,false,0,true,false,false,null,0,Set.of(),Set.of(),flags)).civic());
        // Water under the bell: no well, the chain moves on.
        assertEquals("tower",Projects.decide(new Projects.Queue(9,6,0,true,false,0,true,false,true,null,0,Set.of("storehouse"),Set.of(),new ModConfig())).civic());
    }
    @Test void aHouseWaitingForWoolCallsForThePenFirst(){
        // No wool, a house needs a bed: the pen jumps the chain even beside a barn under way.
        var q=new Projects.Queue(2,5,1,true,true,1,false,true,false,null,0,Set.of(),Set.of(),new ModConfig());
        var choice=Projects.decide(q);
        assertEquals("pen",choice.civic());
        // Bed-bound houses do not beget another house while a civic project is laid, only when there is none left to lay.
        assertFalse(choice.house());
        var penned=new Projects.Queue(2,5,1,true,true,0,true,true,false,null,0,Set.of("pen"),Set.of(),new ModConfig());
        assertFalse(Projects.decide(penned).house());assertEquals("storehouse",Projects.decide(penned).civic());
        var housed=new Projects.Queue(2,5,1,true,true,0,true,true,false,null,1,Set.of("pen","storehouse","well","tower"),Set.of(),new ModConfig());
        assertTrue(Projects.decide(housed).house());assertNull(Projects.decide(housed).civic());
        // A barn under way (not waiting) lays nothing new, and the bed-bound house does not beget a third beside it.
        var building=new Projects.Queue(2,5,1,true,true,1,false,true,false,null,0,Set.of("pen"),Set.of(),new ModConfig());
        assertNull(Projects.decide(building).civic());assertFalse(Projects.decide(building).house());
        // Houses short of planks (not bed-bound) still get their second site beside the barn.
        var planks=new Projects.Queue(2,5,1,true,false,1,false,false,false,null,0,Set.of("pen"),Set.of(),new ModConfig());
        assertTrue(Projects.decide(planks).house());
    }
    @Test void aHouseWithoutASiteCoolsDownLikeACivicKind(){
        var choice=Projects.decide(cooling(village(2,6),"house"));
        assertFalse(choice.house());assertEquals("storehouse",choice.civic());
        assertTrue(Projects.decide(village(2,6)).house());assertEquals(2400,Projects.retryTicks("house"));
    }
    @Test void theSearchRingGrowsWithEveryRetryAndComesRoundAgain(){
        assertEquals(5,Projects.ring(5,0));assertEquals(5,Projects.ring(5,15));
        assertEquals(9,Projects.ring(5,16));assertEquals(13,Projects.ring(5,32));
        assertEquals(5+11*4,Projects.ring(5,16*11));assertEquals(5,Projects.ring(5,16*12));
    }
    @Test void cutsAreRequiredOnlyWithinABuildersReach(){
        // Village.stand: eye at feet+1.62 within reach-.65 of the block's centre, builders standing on the floor.
        assertEquals(5,Projects.reachTop(4.5));assertEquals(5,Projects.reachTop(4.2));assertEquals(4,Projects.reachTop(3.0));
        assertEquals(5,Projects.CUT_TOP);assertEquals(Landmarks.REACH_TOP,Projects.CUT_TOP);
        // The reach is told from the floor before the door: a sunk template's origin lies a block lower.
        var house=new TownBuilding("house",new BlockPos(0,63,0));house.add(new BlockPos(2,65,0),Blocks.OAK_DOOR.defaultBlockState());
        assertEquals(64,Projects.floor(house));
        var well=new TownBuilding("well",new BlockPos(0,64,0));assertEquals(64,Projects.floor(well));
        assertTrue(Projects.reachable(64,new BlockPos(1,69,1).asLong()));assertFalse(Projects.reachable(64,new BlockPos(1,70,1).asLong()));
    }
    @Test void sitesRejectStreetsRoofsAndDoorLines(){
        for(var s:List.of(Blocks.GRASS_BLOCK,Blocks.DIRT,Blocks.COARSE_DIRT,Blocks.SAND,Blocks.RED_SAND,Blocks.GRAVEL,Blocks.STONE,Blocks.ANDESITE,Blocks.SNOW_BLOCK,Blocks.SANDSTONE))
            assertTrue(Projects.natural(s.defaultBlockState()),s.toString());
        for(var s:List.of(Blocks.DIRT_PATH,Blocks.SMOOTH_SANDSTONE,Blocks.OAK_PLANKS,Blocks.OAK_SLAB,Blocks.OAK_STAIRS,Blocks.COBBLESTONE,Blocks.OAK_LOG,Blocks.FARMLAND,Blocks.WATER,Blocks.OAK_FENCE))
            assertFalse(Projects.natural(s.defaultBlockState()),s.toString());
        assertTrue(Projects.street(Blocks.DIRT_PATH.defaultBlockState())&&Projects.street(Blocks.SMOOTH_SANDSTONE.defaultBlockState()));
        assertFalse(Projects.street(Blocks.GRASS_BLOCK.defaultBlockState()));
        // A 5×5 site at (10,10): doors up to three cells west/east and four north, two south, are too close.
        assertTrue(Projects.nearDoor(10,10,5,5,7,12));assertFalse(Projects.nearDoor(10,10,5,5,6,12));
        assertTrue(Projects.nearDoor(10,10,5,5,17,12));assertFalse(Projects.nearDoor(10,10,5,5,18,12));
        assertTrue(Projects.nearDoor(10,10,5,5,12,6));assertFalse(Projects.nearDoor(10,10,5,5,12,5));
        assertTrue(Projects.nearDoor(10,10,5,5,12,17));assertFalse(Projects.nearDoor(10,10,5,5,12,18));
    }
    @Test void aPlayersHouseIsToldByBlocksNoVillageTemplateHolds(){
        for(var s:List.of(Blocks.OAK_SIGN,Blocks.OAK_WALL_SIGN,Blocks.OAK_HANGING_SIGN,Blocks.ENDER_CHEST,Blocks.TRAPPED_CHEST,Blocks.HOPPER,Blocks.ANVIL,Blocks.PISTON,Blocks.REDSTONE_WIRE,Blocks.LEVER,Blocks.IRON_DOOR,Blocks.SHULKER_BOX))
            assertTrue(Projects.foreign(s.defaultBlockState()),s.toString());
        // taiga_small_house_1 hangs two spruce wall signs; no other sign stands in a 26.2 village template.
        for(var s:List.of(Blocks.CHEST,Blocks.OAK_PLANKS,Blocks.FURNACE,Blocks.CRAFTING_TABLE,Blocks.LANTERN,Blocks.BELL,Blocks.STONE_BUTTON,Blocks.OAK_PRESSURE_PLATE,Blocks.IRON_BARS,Blocks.SPRUCE_WALL_SIGN))
            assertFalse(Projects.foreign(s.defaultBlockState()),s.toString());
    }
    @Test void chestsAreAdoptedOnlyFromUnmarkedPiecesOfTheGeneratedVillage(){
        assertTrue(Projects.adoptable(true,false));
        // A home outside every piece is a player's even when the survey found no pieces; a marked piece keeps its chests too.
        assertFalse(Projects.adoptable(false,false));assertFalse(Projects.adoptable(true,true));assertFalse(Projects.adoptable(false,true));
    }
    @Test void thePalisadeRingStaysInsideTheBounds(){
        assertArrayEquals(new int[]{-20,-20,20,20},Palisade.clamp(-24,-24,24,24,-20,-20,20,20));
        assertArrayEquals(new int[]{-10,-10,10,10},Palisade.clamp(-10,-10,10,10,-20,-20,20,20));
        assertNull(Palisade.clamp(30,30,40,40,-20,-20,20,20));
        assertNull(Palisade.clamp(-24,-24,24,24,20,-20,20,20));
    }
    @Test void anOldPalisadeRecordOutsideTheBoundsKeepsItsRing(){
        var village=new VillageState("A",UUID.randomUUID(),new BlockPos(0,64,0),24);village.bounds=new VillageState.Bounds(-20,-20,20,20);
        var wall=new TownBuilding("wall",new BlockPos(-24,64,-24));
        wall.add(new BlockPos(-24,65,-24),Blocks.OAK_FENCE.defaultBlockState());wall.add(new BlockPos(-20,65,-20),Blocks.OAK_FENCE.defaultBlockState());
        wall.blocks.removeIf(p->!village.contains(p.position()));
        assertTrue(wall.valid(village));assertEquals(new BlockPos(-20,65,-20),BlockPos.of(wall.origin));
        // Any other building with its origin outside is still dropped, as before.
        var house=new TownBuilding("house",new BlockPos(-24,64,-24));house.add(new BlockPos(-20,65,-20),Blocks.OAK_PLANKS.defaultBlockState());
        assertFalse(house.valid(village));
    }
}
