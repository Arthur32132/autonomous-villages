package dev.autonomousvillages;

import dev.navigationai.api.*;
import java.util.*;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Either breaks a short list of exposed stone/ore blocks, or extends the village staircase mine
 * with the library excavator and remembers the ore it uncovered. The shaft has terminal states
 * kept in {@link VillageState}: finished (depth, claim edge or world bottom) and blocked (an
 * obstacle the excavator refuses), after which the planner looks for another entrance.
 */
final class MineJob extends Job {
    static final int MAX_DEPTH=64,RUN=12,TORCH_EVERY=6;
    /** Failed runs at one face before the shaft is declared blocked: obstacles the excavator never passes, then anything else. */
    static final int BLOCKED_AFTER=2,STUCK_AFTER=6;
    enum Verdict { RETRY, BLOCKED, FINISHED }
    /** What the stored shaft asks of the planner: dig on, wait out a rejection, leave it finished, or find another entrance. */
    enum ShaftCall { DIG, WAIT, DONE, MOVE }
    private final ArrayDeque<BlockPos> targets;
    private final BlockPos entrance;
    private Approach approach;
    private Harvest harvest;
    private Walk walk;
    private Fetch fetch;
    private boolean fetched;
    private ExcavationTask excavation;
    private int broken,failed,startLength,nextTorch;
    private String error="";
    MineJob(Worker w,List<BlockPos> targets){super(w,"добывает камень и руду");this.targets=new ArrayDeque<>(targets);this.entrance=null;}
    MineJob(Worker w,BlockPos entrance){super(w,"копает шахту");this.targets=new ArrayDeque<>();this.entrance=entrance;}
    @Override String kind(){return entrance==null?"quarry":"shaft";}
    @Override String activity(){return "mine";}
    @Override BlockPos target(){return entrance!=null?entrance:targets.peek();}
    @Override int timeLimit(){return entrance==null?2400:6000;}
    @Override boolean atomic(){return harvest!=null||excavation!=null&&!excavation.completion().isDone();}
    @Override boolean keeps(ItemStack s){return entrance!=null&&s.is(Items.TORCH);}
    @Override boolean tick(){return entrance==null?quarry():shaft();}

    private boolean quarry(){
        if(harvest!=null){
            var state=harvest.tick();
            if(state==Harvest.State.WORKING)return true;
            BlockPos done=targets.poll();
            if(state==Harvest.State.DONE){broken++;v.chronicle.mined();}
            // A block the miner cannot break is left alone for a while instead of being planned again at once.
            else{failed++;error="камень не поддаётся: "+harvest.error;if(done!=null)v.rejected.put(done.asLong(),now()+2400);}
            harvest=null;approach=null;
        }
        if(approach!=null){
            var state=approach.tick();
            if(state==Approach.State.MOVING)return true;
            if(state==Approach.State.ARRIVED){harvest=new Harvest(targets.peek());return true;}
            // An unreachable block is a failure too: a quarry that broke nothing ends with one and the worker cools down.
            failed++;error="не подойти к камню";v.rejected.put(targets.poll().asLong(),now()+1200);approach=null;
        }
        while(!targets.isEmpty()&&!quarryable(targets.peek()))targets.poll();
        if(targets.isEmpty()||broken>=12)return broken==0&&failed>0?fail(error):false;
        approach=new Approach(targets.peek(),4.5,true);
        return true;
    }
    /** A loaded, mineable block with no water or lava beside it; a wet one is left alone for a day. */
    private boolean quarryable(BlockPos p){
        if(!v.level.hasChunkAt(p)||!mineable(v.level.getBlockState(p)))return false;
        if(fluidAdjacent(p,fluidAt(v))){v.rejected.put(p.asLong(),now()+24000);return false;}
        return true;
    }
    /** Stone and ore need a fitting pick; sand comes away by hand. */
    private boolean mineable(BlockState s){
        if(s.is(Blocks.SAND)||s.is(Blocks.RED_SAND))return true;
        return (VillageIndex.oreResource(s)||s.is(Blocks.STONE))&&VillageCrafting.hasPickFor(bot(),s);
    }
    private boolean shaft(){
        var st=v.state;
        if(excavation==null){
            if(!fetched&&fetchTorches())return true;
            if(walk==null)walk=new Walk(v.feetPosition(entrance),2.5);
            var state=walk.tick();
            if(state==Approach.State.FAILED)return abandon("entrance_unreachable","не дойти до шахты");
            if(state==Approach.State.MOVING)return true;
            int done=st.mineLength,minY=v.level.getMinY();
            int length=plannedLength(done,RUN,MAX_DEPTH,n->{BlockPos p=shaftStep(entrance,direction(),n);return st.contains(p)&&p.getY()>minY+4;});
            if(length<=done)return finish("Шахта дошла до "+(done>=MAX_DEPTH?"предела глубины":"границы деревни или дна мира")+": "+done+" ступеней");
            var request=new ExcavationRequest(entrance,direction(),ExcavationMode.STAIRCASE_DOWN,length,done);
            excavation=bot().miner().excavate(request,new ExcavationPolicy(64,2400,4,true,true));
            startLength=done;nextTorch=nextTorchStep(done);
            status="прокладывает лестничную шахту вниз";
            return true;
        }
        progress();
        if(!excavation.completion().isDone()){light();return true;}
        uncoverOres();
        if(st.mineLength>startLength)v.chronicle.announce("Шахта углубилась до "+st.mineLength+" ступеней");
        if(excavation.status()==ExcavationStatus.SUCCESS){
            st.mineFailures=0;
            return st.mineLength>=MAX_DEPTH?finish("Шахта достигла предела глубины: "+MAX_DEPTH+" ступеней"):false;
        }
        String reason;
        try{reason=Objects.requireNonNullElse(excavation.completion().join().reason(),"failed");}catch(RuntimeException ignored){reason="cancelled";}
        return abandon(reason,"шахта: "+reason);
    }
    /** Segments already dug are kept as they complete, so a stopped or timed-out run loses nothing. */
    private void progress(){
        var st=v.state;
        if(excavation==null||st.mineEntrance==null||st.mineEntrance!=entrance.asLong())return;
        int segments=Math.min(MAX_DEPTH,excavation.completedSegments());
        if(segments>st.mineLength)st.mineLength=segments;
    }
    /**
     * A failed run: a transient reason is retried after the worker's cooldown; an obstacle the
     * excavator never passes moves the shaft elsewhere; the world bottom ends the mine.
     * The terminal transitions are not job failures, so the worker's 600-tick rejection does not
     * shorten the day-long one put on the entrance here.
     */
    private boolean abandon(String reason,String text){
        var st=v.state;
        st.mineFailures=Math.min(16,st.mineFailures+1);
        boolean atBottom=shaftStep(entrance,direction(),Math.min(MAX_DEPTH,st.mineLength+1)).getY()<=v.level.getMinY()+5;
        switch(verdict(reason,st.mineFailures,atBottom)){
            case FINISHED->{return finish("Шахта дошла до дна мира: "+st.mineLength+" ступеней");}
            case BLOCKED->{
                st.mineStatus=VillageState.MINE_BLOCKED;st.mineFailures=0;st.mineRetryAt=now()+24000;
                AutonomousVillagesMod.LOG.info("MINE {} blocked at {}: {}",st.name,entrance.toShortString(),reason);
                v.chronicle.announce("Шахта у "+entrance.toShortString()+" заброшена ("+describe(reason)+"), ищут другое место");
                status="шахта заброшена: "+describe(reason);return false;
            }
            default->{return fail(text);}
        }
    }
    private boolean finish(String message){
        var st=v.state;
        if(!VillageState.MINE_FINISHED.equals(st.mineStatus)){st.mineStatus=VillageState.MINE_FINISHED;st.mineFailures=0;v.chronicle.announce(message);}
        status="шахта закончена";return false;
    }
    private Direction direction(){return Direction.from2DDataValue(v.state.mineDirection);}
    /** Torches for the steps this run will dig, from the store only; a village without any digs in the dark as before. */
    private boolean fetchTorches(){
        Predicate<ItemStack> torch=s->s.is(Items.TORCH);
        int wanted=torchesWanted(v.state.mineLength,Math.min(MAX_DEPTH,v.state.mineLength+RUN));
        if(fetch==null){
            if(wanted<=w.count(torch)||v.stock.count(torch)==0){fetched=true;return false;}
            fetch=new Fetch(List.of(new Need(torch,wanted,"факелы")));
        }
        var state=fetch.tick();
        if(state==Approach.State.MOVING)return true;
        fetched=true;fetch=null;return false;
    }
    /** A wall torch beside every sixth step, placed between two blocks so the pick in hand is not swapped mid-swing. */
    private void light(){
        int step=Math.min(MAX_DEPTH,excavation.completedSegments());
        if(step<nextTorch||excavation.phase()==ExcavationPhase.MINING)return;
        nextTorch=nextTorchStep(step);
        if(w.count(s->s.is(Items.TORCH))<1)return;
        BlockPos feet=shaftStep(entrance,direction(),step);
        for(Direction side:List.of(direction().getClockWise(),direction().getCounterClockWise()))for(int up=1;up<=2;up++){
            BlockPos spot=feet.above(up);
            if(!v.level.hasChunkAt(spot)||!v.state.contains(spot))continue;
            var torch=Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING,side.getOpposite());
            if(Construction.place(v,bot(),new Construction.Step(spot,torch,Items.TORCH))){v.state.lights.add(spot.asLong());v.chronicle.lit();return;}
        }
    }
    /** Ore next to the shaft becomes a known quarry target even below the surface scan. */
    private void uncoverOres(){
        var request=new ExcavationRequest(entrance,direction(),ExcavationMode.STAIRCASE_DOWN,Math.max(1,Math.min(MAX_DEPTH,v.state.mineLength)));
        for(int step=0;step<=request.length();step++)for(BlockPos cell:request.clearanceAt(step))for(Direction d:Direction.values()){
            BlockPos p=cell.relative(d);
            if(v.level.hasChunkAt(p)&&VillageIndex.oreResource(v.level.getBlockState(p))){v.index.ore.add(p.immutable());v.index.shaftOre.add(p.immutable());}
        }
    }
    @Override void stop(){
        if(approach!=null)approach.cancel();if(harvest!=null)harvest.cancel();if(walk!=null)walk.cancel();if(fetch!=null)fetch.cancel();
        if(excavation!=null){progress();if(!excavation.completion().isDone())excavation.cancel();}
    }

    // --- pure helpers, unit-tested -----------------------------------------------------------------
    /** Feet position of a staircase step, as the library's STAIRCASE_DOWN request lays it out. */
    static BlockPos shaftStep(BlockPos entrance,Direction direction,int step){return entrance.relative(direction,step).below(step);}
    /** The longest run of up to {@code batch} more segments that all fit; {@code done} when the next one already does not. */
    static int plannedLength(int done,int batch,int max,IntPredicate fits){
        int length=done;
        while(length<Math.min(max,done+batch)&&fits.test(length+1))length++;
        return length;
    }
    /** Excavator reasons that no retry at the same face cures: fluids, hazards, protection, bedrock, a bad floor or passage. */
    static boolean permanent(String reason){
        return switch(reason){
            case "unsafe_fluid","unsafe_floor","unsafe_passage","unsafe_entrance","dangerous_block","falling_block","unstable_ceiling",
                 "protected_block_entity","not_allowed","mining_not_allowed","mining_cannot_break","world_border"->true;
            default->false;
        };
    }
    static Verdict verdict(String reason,int failures,boolean atBottom){
        if(reason.startsWith("world_bounds")||atBottom&&permanent(reason))return Verdict.FINISHED;
        return failures>=(permanent(reason)?BLOCKED_AFTER:STUCK_AFTER)?Verdict.BLOCKED:Verdict.RETRY;
    }
    /** The excavator's reason in the villagers' words, for chat. */
    static String describe(String reason){
        return switch(reason){
            case "unsafe_fluid"->"вода или лава";
            case "mining_cannot_break","world_border"->"несокрушимая порода";
            case "protected_block_entity","not_allowed","mining_not_allowed"->"чужая постройка";
            case "falling_block","unstable_ceiling"->"сыпучий грунт";
            case "dangerous_block","unsafe_floor","unsafe_passage","unsafe_entrance"->"опасный ход";
            case "entrance_unreachable"->"нет дороги ко входу";
            default->"работа не идёт";
        };
    }
    /**
     * Mining the planner may hand out, from the worker's cooldowns: a failed MineJob cools down its
     * own kind ("quarry" or "shaft"), "mine" is the planner's own pause; one shaft is dug at a time.
     */
    static boolean quarryAllowed(Predicate<String> ready){return ready.test("mine")&&ready.test("quarry");}
    static boolean shaftAllowed(Predicate<String> ready,boolean shaftBusy){return ready.test("mine")&&ready.test("shaft")&&!shaftBusy;}
    /** A shaft finished at the claim edge digs on once the footprint has grown past its next step; depth and world-bottom finishes are final. */
    static boolean resumable(VillageState st,int minY){
        if(st.mineEntrance==null||!VillageState.MINE_FINISHED.equals(st.mineStatus)||st.mineLength>=MAX_DEPTH)return false;
        BlockPos next=shaftStep(BlockPos.of(st.mineEntrance),Direction.from2DDataValue(st.mineDirection),st.mineLength+1);
        return st.contains(next)&&next.getY()>minY+5;
    }
    /** The planner's view of the stored shaft, undoing an edge finish the claim has grown past; {@code rejected} is the worker's short rejection after a failed run. */
    static ShaftCall shaftCall(VillageState st,int minY,boolean rejected){
        if(st.mineEntrance==null)return ShaftCall.MOVE;
        if(resumable(st,minY)){st.mineStatus=VillageState.MINE_DIGGING;st.mineFailures=0;}
        if(VillageState.MINE_FINISHED.equals(st.mineStatus)||st.mineLength>=MAX_DEPTH)return ShaftCall.DONE;
        if(VillageState.MINE_BLOCKED.equals(st.mineStatus))return ShaftCall.MOVE;
        return rejected?ShaftCall.WAIT:ShaftCall.DIG;
    }
    /** True when any of the six neighbours holds fluid (or is not loaded, which counts as unknown). */
    static boolean fluidAdjacent(BlockPos p,Predicate<BlockPos> fluid){
        for(Direction d:Direction.values())if(fluid.test(p.relative(d)))return true;
        return false;
    }
    static Predicate<BlockPos> fluidAt(Village v){return q->!v.level.hasChunkAt(q)||!v.level.getFluidState(q).isEmpty();}
    /** The first step after {@code step} that gets a torch. */
    static int nextTorchStep(int step){return (step/TORCH_EVERY+1)*TORCH_EVERY;}
    /** Torches the steps in {@code (done, length]} will take. */
    static int torchesWanted(int done,int length){return Math.max(0,length/TORCH_EVERY-done/TORCH_EVERY);}
}
