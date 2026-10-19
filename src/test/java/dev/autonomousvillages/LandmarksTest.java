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
            Blocks.COBBLESTONE,Blocks.COBBLESTONE_WALL,Blocks.GLASS_PANE,Blocks.OAK_FENCE_GATE,Blocks.FURNACE,Blocks.TORCH,Blocks.WALL_TORCH);}

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
        // As Village.stand picks a cell: feet at most two cells aside and up to six below, the eye 1.62
        // above the feet within 3.55 of the block's centre. Builders are taken to stand only on the floor
        // or the ground (feet at y=1), never on the walls; over water only on the deck or the bank.
        for(var d:designs()){
            var cells=cells(d);
            for(var p:d.pieces()){
                assertTrue(p.pos().getX()>=-2&&p.pos().getX()<=d.width()+1&&p.pos().getZ()>=-2&&p.pos().getZ()<=d.depth()+1,d.kind()+" outside at "+p.pos());
                if(p.optional())continue;
                assertTrue(p.pos().getY()<=Landmarks.REACH_TOP,d.kind()+" too high at "+p.pos());
                boolean reached=false;
                for(int dx=-2;dx<=2&&!reached;dx++)for(int dz=-2;dz<=2&&!reached;dz++){
                    BlockPos feet=new BlockPos(p.pos().getX()+dx,1,p.pos().getZ()+dz);
                    if(feet.equals(p.pos())||feet.above().equals(p.pos())||p.pos().getY()-feet.getY()>6)continue;
                    if(solid(cells,feet)||solid(cells,feet.above()))continue;
                    boolean inside=feet.getX()>=0&&feet.getX()<d.width()&&feet.getZ()>=0&&feet.getZ()<d.depth();
                    boolean floor=solid(cells,feet.below())||!d.afloat()&&!inside||d.afloat()&&feet.getZ()<0;
                    double ex=dx,ey=1+1.62-(p.pos().getY()+.5),ez=dz;
                    if(floor&&ex*ex+ey*ey+ez*ez<=3.55*3.55)reached=true;
                }
                assertTrue(reached,d.kind()+": nowhere to stand for "+p.state()+" at "+p.pos());
            }
        }
    }
    private static boolean solid(Map<BlockPos,Landmarks.Piece> cells,BlockPos p){
        var c=cells.get(p);
        return c!=null&&!c.optional()&&!(c.state().getBlock() instanceof TorchBlock)&&!(c.state().getBlock() instanceof DoorBlock);
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
                // The bank by a pier is ground of its own (if it is water there, the sign is simply left out).
                if(under==null&&d.afloat()&&support.getY()==0&&support.getZ()<0)continue;
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
        assertTrue(cells.get(new BlockPos(3,6,5)).state().is(Blocks.CAMPFIRE));
        assertTrue(cells.get(new BlockPos(3,5,5)).state().is(Blocks.COBBLESTONE));
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
        assertTrue(t.get(new BlockPos(4,5,3)).state().is(Blocks.OAK_PLANKS));
        // The gable ends are closed under the roof.
        assertTrue(t.get(new BlockPos(0,4,3)).state().is(Blocks.OAK_PLANKS));
    }
    @Test void theMillTurnsItsSailsBesideItsWall(){
        var m=Landmarks.mill();var cells=cells(m);
        assertTrue(cells.get(new BlockPos(5,4,2)).state().is(Blocks.OAK_LOG)&&cells.get(new BlockPos(6,4,2)).optional());
        // Four arms of three fence posts, each with two cloths; the lowest stays off the ground.
        assertEquals(12,m.pieces().stream().filter(p->p.pos().getX()==6&&p.state().is(Blocks.OAK_FENCE)).count());
        assertEquals(8,m.pieces().stream().filter(p->p.state().is(Blocks.WOOL.white())).count());
        assertTrue(m.pieces().stream().filter(p->p.pos().getX()==6).allMatch(p->p.pos().getY()>=1));
        assertTrue(m.pieces().stream().anyMatch(p->p.state().is(Blocks.COMPOSTER)));
    }
    @Test void thePierRunsOutFromTheBank(){
        var p=Landmarks.dock();
        assertTrue(p.afloat()&&!Landmarks.tavern().afloat());
        // The deck is laid row by row from the bank, so each plank has one to stand on before it.
        var deck=p.pieces().stream().filter(c->c.pos().getY()==0).toList();
        assertEquals(18,deck.size());
        for(int i=1;i<deck.size();i++)assertTrue(deck.get(i).pos().getZ()>=deck.get(i-1).pos().getZ());
        assertTrue(p.pieces().stream().noneMatch(c->c.pos().getY()<0));
        for(int z=0;z<p.depth()-1;z++)assertNull(cells(p).get(new BlockPos(1,1,z)),"walkway at z="+z);
    }
    @Test void landmarksComeOneAtATimeAsTheVillageGrows(){
        assertNull(Landmarks.due(Set.of(),5,Set.of()));
        assertEquals("forge",Landmarks.due(Set.of(),6,Set.of()));
        assertNull(Landmarks.due(Set.of("forge"),6,Set.of()));
        assertEquals("market",Landmarks.due(Set.of("forge"),7,Set.of()));
        // In order: a big village without a forge builds the forge first.
        assertEquals("forge",Landmarks.due(Set.of("market"),20,Set.of()));
        assertEquals("mill",Landmarks.due(Set.of("forge","market"),10,Set.of()));
        assertEquals("chapel",Landmarks.due(Set.of("forge","market","mill"),10,Set.of()));
        assertEquals("tavern",Landmarks.due(Set.of("forge","market","mill","chapel"),10,Set.of()));
        // No site for the tavern lately: the stable takes its turn once the village is big enough for it.
        assertNull(Landmarks.due(Set.of("forge","market","mill","chapel"),10,Set.of("tavern")));
        assertEquals("stable",Landmarks.due(Set.of("forge","market","mill","chapel"),11,Set.of("tavern")));
        assertEquals("dock",Landmarks.due(Set.of("forge","market","mill","chapel","tavern","stable"),12,Set.of()));
        assertNull(Landmarks.due(Set.copyOf(Landmarks.ORDER),40,Set.of()));
        for(String kind:Landmarks.ORDER)assertTrue(TownBuilding.KINDS.contains(kind),kind);
        assertEquals("рынок",Projects.label(new TownBuilding("market",BlockPos.ZERO)));
    }
    @Test void theStableKeepsAHorseBehindEachGate(){
        var st=Landmarks.stable();var cells=cells(st);
        for(int x:Landmarks.STALLS){
            assertTrue(cells.get(new BlockPos(x,1,0)).state().getBlock() instanceof FenceGateBlock,"gate at x="+x);
            // Behind the gate the stall is open floor for a horse (two high), fenced from the next.
            for(int y=1;y<=2;y++)assertNull(cells.get(new BlockPos(x,y,1)),"stall at x="+x);
        }
        for(int z=1;z<st.depth()-1;z++)assertTrue(cells.get(new BlockPos(3,1,z)).state().is(Blocks.OAK_FENCE));
        // No gap a horse can step through: every front cell between the corner posts is fence or gate.
        for(int x=1;x<st.width()-1;x++)assertNotNull(cells.get(new BlockPos(x,1,0)),"front at x="+x);
    }
    @Test void theChapelHasPewsBeforeItsAltarAndAnAisle(){
        var c=Landmarks.chapel();var cells=cells(c);
        assertEquals(8,c.pieces().stream().filter(p->p.state().is(Blocks.OAK_STAIRS)&&p.optional()).count());
        for(int z=1;z<=2;z++)assertNull(cells.get(new BlockPos(3,1,z)),"aisle at z="+z);
        assertTrue(cells.get(new BlockPos(3,1,3)).state().is(Blocks.BREWING_STAND));
        assertTrue(cells.get(new BlockPos(3,0,0)).state().is(Blocks.COBBLESTONE)&&cells.get(new BlockPos(3,1,0)).state().getBlock() instanceof DoorBlock);
    }
}
