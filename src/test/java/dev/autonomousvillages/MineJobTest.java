package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import dev.navigationai.api.ExcavationMode;
import dev.navigationai.api.ExcavationRequest;
import java.util.*;
import java.util.function.Predicate;
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
        assertEquals(VillageState.MINE_DIGGING,s.mineStatus);assertEquals(0,s.mineFailures);assertEquals(0,s.mineRetryAt);assertEquals(17,s.mineLength);
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
    }
    @Test void aFailedQuarryOrShaftPausesOnlyItsOwnKindAndThePlannerPausePausesBoth(){
        Set<String> cooling=new HashSet<>();Predicate<String> ready=k->!cooling.contains(k);
        assertTrue(MineJob.quarryAllowed(ready));assertTrue(MineJob.shaftAllowed(ready,false));
        cooling.add("quarry");assertFalse(MineJob.quarryAllowed(ready));assertTrue(MineJob.shaftAllowed(ready,false));
        cooling.clear();cooling.add("shaft");assertTrue(MineJob.quarryAllowed(ready));assertFalse(MineJob.shaftAllowed(ready,false));
        cooling.clear();cooling.add("mine");assertFalse(MineJob.quarryAllowed(ready));assertFalse(MineJob.shaftAllowed(ready,false));
        // One shaft at a time; the quarry does not wait for it.
        cooling.clear();assertFalse(MineJob.shaftAllowed(ready,true));assertTrue(MineJob.quarryAllowed(ready));
    }
    @Test void thePlannerDigsWaitsLeavesOrMovesTheShaftByItsState(){
        var s=new VillageState("A",UUID.randomUUID(),new BlockPos(0,64,0),24);
        assertEquals(MineJob.ShaftCall.MOVE,MineJob.shaftCall(s,-64,false));
        s.mineEntrance=new BlockPos(16,64,0).asLong();s.mineDirection=Direction.WEST.get2DDataValue();s.mineLength=5;
        assertEquals(MineJob.ShaftCall.DIG,MineJob.shaftCall(s,-64,false));
        // The worker's short rejection after a failed run pauses the shaft without moving it.
        assertEquals(MineJob.ShaftCall.WAIT,MineJob.shaftCall(s,-64,true));
        s.mineStatus=VillageState.MINE_BLOCKED;
        assertEquals(MineJob.ShaftCall.MOVE,MineJob.shaftCall(s,-64,false));assertEquals(MineJob.ShaftCall.MOVE,MineJob.shaftCall(s,-64,true));
        s.mineStatus=VillageState.MINE_DIGGING;s.mineLength=MineJob.MAX_DEPTH;
        assertEquals(MineJob.ShaftCall.DONE,MineJob.shaftCall(s,-64,false));
        // Finished where the next step (y=15) leaves the claim's 48-block depth; it stays finished.
        s.mineStatus=VillageState.MINE_FINISHED;s.mineLength=48;
        assertEquals(MineJob.ShaftCall.DONE,MineJob.shaftCall(s,-64,false));assertEquals(VillageState.MINE_FINISHED,s.mineStatus);
    }
    @Test void anEdgeFinishIsUndoneOnceTheClaimGrowsPastTheNextStepButDepthAndBottomStayFinal(){
        var s=new VillageState("A",UUID.randomUUID(),new BlockPos(0,64,0),24);
        s.bounds=new VillageState.Bounds(-24,-24,24,24);
        // Entrance 8 blocks inside the east edge, running west: step 41 at x=-25 is the first outside the claim.
        s.mineEntrance=new BlockPos(16,64,0).asLong();s.mineDirection=Direction.WEST.get2DDataValue();s.mineLength=40;s.mineStatus=VillageState.MINE_FINISHED;s.mineFailures=3;
        assertFalse(MineJob.resumable(s,-64));assertEquals(MineJob.ShaftCall.DONE,MineJob.shaftCall(s,-64,false));
        s.bounds.include(new BlockPos(-40,64,0),8);
        assertTrue(MineJob.resumable(s,-64));assertEquals(MineJob.ShaftCall.DIG,MineJob.shaftCall(s,-64,false));
        assertEquals(VillageState.MINE_DIGGING,s.mineStatus);assertEquals(0,s.mineFailures);
        // The same step at the world bottom (y=23 with minY=18) or at full depth is left finished.
        s.mineStatus=VillageState.MINE_FINISHED;
        assertFalse(MineJob.resumable(s,18));assertEquals(MineJob.ShaftCall.DONE,MineJob.shaftCall(s,18,false));
        s.mineLength=MineJob.MAX_DEPTH;s.bounds.include(new BlockPos(-80,64,0),8);
        assertFalse(MineJob.resumable(s,-64));assertEquals(MineJob.ShaftCall.DONE,MineJob.shaftCall(s,-64,false));
        // A blocked shaft is never resumed this way.
        s.mineLength=40;s.mineStatus=VillageState.MINE_BLOCKED;
        assertFalse(MineJob.resumable(s,-64));assertEquals(MineJob.ShaftCall.MOVE,MineJob.shaftCall(s,-64,false));
    }
    @Test void theChatNamesTheObstacleInRussianNotWithTheLibraryToken(){
        assertEquals("вода или лава",MineJob.describe("unsafe_fluid"));
        assertEquals("несокрушимая порода",MineJob.describe("mining_cannot_break"));
        for(String reason:List.of("unsafe_fluid","unsafe_floor","unsafe_passage","unsafe_entrance","dangerous_block","falling_block","unstable_ceiling",
                "protected_block_entity","not_allowed","mining_not_allowed","mining_cannot_break","world_border","entrance_unreachable","timeout","cancelled"))
            assertFalse(MineJob.describe(reason).contains("_"),reason);
    }
}
