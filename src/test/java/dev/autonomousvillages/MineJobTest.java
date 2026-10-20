package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import dev.navigationai.api.ExcavationMode;
import dev.navigationai.api.ExcavationRequest;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class MineJobTest {
    @Test void shaftStepMatchesTheLibraryStaircase(){
        var entrance=new BlockPos(10,70,-5);
        for(Direction d:Direction.Plane.HORIZONTAL){
            var request=new ExcavationRequest(entrance,d,ExcavationMode.STAIRCASE_DOWN,MineJob.MAX_DEPTH);
            for(int step=0;step<=MineJob.MAX_DEPTH;step++)assertEquals(request.positionAt(step),MineJob.shaftStep(entrance,d,step));
        }
    }
    @Test void plannedLengthStopsAtTheFirstSegmentThatDoesNotFitAndAtTheCap(){
        assertEquals(12,MineJob.plannedLength(0,12,64,n->true));
        assertEquals(24,MineJob.plannedLength(12,12,64,n->true));
        assertEquals(64,MineJob.plannedLength(60,12,64,n->true));
        // The run ends before the segment outside the claim or below the world; nothing more fits once the next one is out.
        assertEquals(7,MineJob.plannedLength(0,12,64,n->n<8));
        assertEquals(7,MineJob.plannedLength(7,12,64,n->n<8));
        assertEquals(64,MineJob.plannedLength(64,12,64,n->true));
    }
    @Test void permanentObstaclesEndTheShaftAfterTwoRunsTransientOnesAfterSix(){
        assertEquals(MineJob.Verdict.RETRY,MineJob.verdict("unsafe_fluid",1,false));
        assertEquals(MineJob.Verdict.BLOCKED,MineJob.verdict("unsafe_fluid",2,false));
        assertEquals(MineJob.Verdict.BLOCKED,MineJob.verdict("mining_cannot_break",2,false));
        assertEquals(MineJob.Verdict.BLOCKED,MineJob.verdict("protected_block_entity",2,false));
        assertEquals(MineJob.Verdict.RETRY,MineJob.verdict("timeout",5,false));
        assertEquals(MineJob.Verdict.RETRY,MineJob.verdict("entrance_unreachable",5,false));
        assertEquals(MineJob.Verdict.BLOCKED,MineJob.verdict("entrance_unreachable",6,false));
        assertEquals(MineJob.Verdict.RETRY,MineJob.verdict("inventory_full",2,false));
        assertEquals(MineJob.Verdict.RETRY,MineJob.verdict("no_suitable_tool",2,false));
    }
    @Test void theWorldBottomFinishesTheMineInsteadOfBlockingIt(){
        assertEquals(MineJob.Verdict.FINISHED,MineJob.verdict("world_bounds",1,false));
        assertEquals(MineJob.Verdict.FINISHED,MineJob.verdict("world_bounds_return_failed",1,false));
        // Bedrock at the bottom is the end of the mine, not an obstacle that moves it.
        assertEquals(MineJob.Verdict.FINISHED,MineJob.verdict("mining_cannot_break",1,true));
        // A transient failure near the bottom is still only retried.
        assertEquals(MineJob.Verdict.RETRY,MineJob.verdict("timeout",1,true));
    }
    @Test void fluidAdjacencyLooksAtTheSixNeighboursOnly(){
        var p=new BlockPos(0,60,0);
        assertFalse(MineJob.fluidAdjacent(p,q->false));
        for(Direction d:Direction.values()){BlockPos wet=p.relative(d);assertTrue(MineJob.fluidAdjacent(p,q->q.equals(wet)),d.toString());}
        // Diagonal water does not flow into the pit and does not count; the block itself is not its own neighbour.
        assertFalse(MineJob.fluidAdjacent(p,q->q.equals(p.offset(1,1,0))||q.equals(p)));
    }
    @Test void torchesEverySixStepsOnlyAsManyAsTheRunNeeds(){
        assertEquals(6,MineJob.nextTorchStep(0));assertEquals(12,MineJob.nextTorchStep(6));
        assertEquals(12,MineJob.nextTorchStep(7));assertEquals(12,MineJob.nextTorchStep(11));
        assertEquals(2,MineJob.torchesWanted(0,12));assertEquals(2,MineJob.torchesWanted(6,18));
        assertEquals(0,MineJob.torchesWanted(12,17));assertEquals(0,MineJob.torchesWanted(60,64));assertEquals(1,MineJob.torchesWanted(59,64));
    }
    @Test void oldSavesLoadWithTheShaftStillDigging(){
        var s=VillageRuntime.GSON.fromJson("{\"name\":\"Old\",\"mineEntrance\":1,\"mineDirection\":2,\"mineLength\":17}",VillageState.class);
        assertEquals(VillageState.MINE_DIGGING,s.mineStatus);assertEquals(0,s.mineFailures);assertEquals(17,s.mineLength);
        var restored=VillageRuntime.GSON.fromJson(VillageRuntime.GSON.toJson(s),VillageState.class);
        assertEquals(VillageState.MINE_DIGGING,restored.mineStatus);
    }
    @Test void mineStatusIsSanitisedWithTheRestOfTheMetadata(){
        var s=new VillageState("A",UUID.randomUUID(),new BlockPos(0,64,0),24);
        s.mineEntrance=new BlockPos(3,64,0).asLong();s.mineStatus="flooded";s.mineFailures=-3;
        s.compactMetadata();s.validate();
        assertEquals(VillageState.MINE_DIGGING,s.mineStatus);assertEquals(0,s.mineFailures);
        s.mineStatus=VillageState.MINE_BLOCKED;s.mineFailures=40;s.compactMetadata();
        assertEquals(VillageState.MINE_BLOCKED,s.mineStatus);assertEquals(16,s.mineFailures);
        // A finished shaft whose entrance left the footprint is no longer finished: a new one may be dug.
        s.mineStatus=VillageState.MINE_FINISHED;s.mineEntrance=new BlockPos(90,64,0).asLong();s.compactMetadata();
        assertNull(s.mineEntrance);assertEquals(VillageState.MINE_DIGGING,s.mineStatus);
        assertEquals(Set.of("digging","finished","blocked"),Set.of(VillageState.MINE_DIGGING,VillageState.MINE_FINISHED,VillageState.MINE_BLOCKED));
    }
}
