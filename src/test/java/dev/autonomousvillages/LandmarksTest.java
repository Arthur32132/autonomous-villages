package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LandmarksTest {
    @BeforeAll static void bootstrapRegistries(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}

    /** What the village makes from wood, stone and coal; everything else in a design is decoration (built after bootstrap). */
    private static Set<Block> made(){return Set.of(Blocks.OAK_PLANKS,Blocks.OAK_LOG,Blocks.OAK_STAIRS,Blocks.OAK_SLAB,Blocks.OAK_FENCE,Blocks.OAK_DOOR,
            Blocks.COBBLESTONE,Blocks.COBBLESTONE_WALL,Blocks.GLASS_PANE,Blocks.FURNACE,Blocks.TORCH,Blocks.WALL_TORCH);}

    private static List<Landmarks.Design> designs(){return Landmarks.ORDER.stream().map(Landmarks::design).toList();}
    private static Map<BlockPos,Landmarks.Piece> cells(Landmarks.Design d){
        var out=new HashMap<BlockPos,Landmarks.Piece>();for(var p:d.pieces())assertNull(out.put(p.pos(),p),d.kind()+" twice at "+p.pos());
        return out;
    }

    @Test void structuralBlocksAreOnesTheVillageMakes(){
        var made=made();
        for(var d:designs())for(var p:d.pieces())
            if(!p.optional())assertTrue(made.contains(p.state().getBlock()),d.kind()+": "+p.state()+" at "+p.pos());
    }
    @Test void everyBlockIsWithinABuildersReach(){
        // Builders stand on the ground or the floor (feet at y=1, eyes near 2.6) and reach 4.2 blocks.
        for(var d:designs())for(var p:d.pieces()){
            if(!p.optional())assertTrue(p.pos().getY()<=6,d.kind()+" too high at "+p.pos());
            assertTrue(p.pos().getX()>=-1&&p.pos().getX()<=d.width()&&p.pos().getZ()>=-1&&p.pos().getZ()<=d.depth(),d.kind()+" outside at "+p.pos());
        }
    }
    @Test void attachedBlocksHaveSomethingToHangOn(){
        for(var d:designs()){
            var cells=cells(d);
            for(var p:d.pieces()){
                BlockState s=p.state();BlockPos support=null;
                if(s.getBlock() instanceof WallTorchBlock)support=p.pos().relative(s.getValue(WallTorchBlock.FACING).getOpposite());
                else if(s.getBlock() instanceof WallSignBlock)support=p.pos().relative(s.getValue(WallSignBlock.FACING).getOpposite());
                else if(s.getBlock() instanceof CarpetBlock||s.is(Blocks.TORCH)||s.is(Blocks.OAK_PRESSURE_PLATE)||s.is(Blocks.LANTERN)||s.is(Blocks.CAKE)
                        ||s.is(Blocks.CAMPFIRE)||s.is(Blocks.BREWING_STAND)||s.getBlock() instanceof StandingSignBlock)support=p.pos().below();
                if(support==null)continue;
                var under=cells.get(support);
                assertNotNull(under,d.kind()+": "+s+" at "+p.pos()+" hangs on nothing");
                // What a torch hangs on is built before it; decoration may rest on decoration placed before it.
                assertTrue(p.optional()||!under.optional(),d.kind()+": "+s+" at "+p.pos()+" rests on decoration");
                assertFalse(under.state().is(Blocks.GLASS_PANE)&&s.getBlock() instanceof WallTorchBlock,d.kind()+": torch on glass at "+p.pos());
                if(p.optional()&&under.optional())assertTrue(d.pieces().indexOf(under)<d.pieces().indexOf(p),d.kind()+": "+s+" before its support");
            }
        }
    }
    @Test void theTavernDoorOpensOntoTheStreetAndAnAisle(){
        var t=cells(Landmarks.tavern());
        var door=t.get(new BlockPos(4,1,0));
        assertTrue(door.state().getBlock() instanceof DoorBlock&&!door.optional());
        assertNull(t.get(new BlockPos(4,2,0)),"the door's upper half stays free");
        for(int z=1;z<=3;z++)for(int y=1;y<=2;y++)assertNull(t.get(new BlockPos(4,y,z)),"aisle at z="+z);
        for(int z=-1;z>=-2;z--)assertNull(t.get(new BlockPos(4,1,z)),"doorstep at z="+z);
    }
    @Test void theForgeSmeltsUnderItsChimneyAndIsOpenToWalkIn(){
        var f=Landmarks.forge();var cells=cells(f);
        assertEquals(2,f.pieces().stream().filter(p->p.state().is(Blocks.FURNACE)&&!p.optional()).count());
        assertTrue(cells.get(new BlockPos(3,7,5)).state().is(Blocks.CAMPFIRE));
        assertTrue(cells.get(new BlockPos(3,6,5)).state().is(Blocks.COBBLESTONE));
        for(int y=1;y<=2;y++)assertNull(cells.get(new BlockPos(3,y,0)),"entrance");
        // A workstation for each smith: armourer, toolsmith and weaponsmith can move in.
        for(Block b:new Block[]{Blocks.BLAST_FURNACE,Blocks.SMITHING_TABLE,Blocks.GRINDSTONE})
            assertTrue(f.pieces().stream().anyMatch(p->p.optional()&&p.state().is(b)),b.toString());
    }
    @Test void theMarketHasFourStallsAndAWayThroughTheMiddle(){
        var m=Landmarks.market();var cells=cells(m);
        assertEquals(4,m.pieces().stream().filter(p->p.state().is(Blocks.BARREL)).count());
        assertEquals(24,m.pieces().stream().filter(p->p.state().getBlock() instanceof CarpetBlock).count());
        for(int z=0;z<m.depth();z++)if(z!=3&&z!=4)assertNull(cells.get(new BlockPos(4,1,z)),"walkway at z="+z);
        assertTrue(cells.get(new BlockPos(4,3,4)).state().is(Blocks.TORCH));
    }
    @Test void roofsSlopeTowardTheEaves(){
        var t=cells(Landmarks.tavern());
        assertEquals(Direction.SOUTH,t.get(new BlockPos(4,3,-1)).state().getValue(StairBlock.FACING));
        assertEquals(Direction.NORTH,t.get(new BlockPos(4,3,7)).state().getValue(StairBlock.FACING));
        assertTrue(t.get(new BlockPos(4,6,3)).state().is(Blocks.OAK_PLANKS));
        // The gable ends are closed under the roof.
        assertTrue(t.get(new BlockPos(0,5,3)).state().is(Blocks.OAK_PLANKS));
    }
    @Test void landmarksComeOneAtATimeAsTheVillageGrows(){
        assertNull(Landmarks.due(Set.of(),5));
        assertEquals("forge",Landmarks.due(Set.of(),6));
        assertNull(Landmarks.due(Set.of("forge"),7));
        assertEquals("market",Landmarks.due(Set.of("forge"),8));
        // In order: a big village without a forge builds the forge first.
        assertEquals("forge",Landmarks.due(Set.of("market"),20));
        assertEquals("tavern",Landmarks.due(Set.of("forge","market"),10));
        assertNull(Landmarks.due(Set.of("forge","market","tavern"),40));
        for(String kind:Landmarks.ORDER)assertTrue(TownBuilding.KINDS.contains(kind),kind);
        assertEquals("рынок",Projects.label(new TownBuilding("market",BlockPos.ZERO)));
    }
}
