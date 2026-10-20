package dev.autonomousvillages;

import java.util.*;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BoneMealItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Work in the village orchard ({@link OrchardFeature}): planting oak saplings from the store on the
 * grid, or the daily round: bone meal from the store on the saplings, then the apples and other leaf
 * drops lying under the trees, carried to the store.
 */
final class OrchardJob extends Job {
    enum Phase { FETCH, TREES, GATHER, DEPOSIT }
    /** Pick-ups one round makes at most, and how long it looks for them. */
    static final int MAX_PICKUPS=16,GATHER_TICKS=600;
    final boolean planting;
    private final ArrayDeque<BlockPos> cells;
    private final BlockPos spot;
    private final AABB area;
    private final int wanted;
    private final Predicate<ItemStack> supply;
    private Phase phase=Phase.FETCH;
    private Fetch fetch;
    private Approach approach;
    private Walk walk;
    private Deposit deposit;
    private ItemEntity drop;
    private final Set<Integer> skipped=new HashSet<>();
    private boolean there;
    private int planted,fertilized,pickups,applesBefore,dropsBefore;
    private long nextAction,gatherUntil;

    private OrchardJob(Worker w,String status,boolean planting,List<BlockPos> cells,BlockPos spot,AABB area,int wanted){
        super(w,status);this.planting=planting;this.cells=new ArrayDeque<>(cells);this.spot=spot.immutable();this.area=area;this.wanted=wanted;
        supply=planting?OrchardFeature.OAK:OrchardFeature.BONE_MEAL;
    }
    /** Plants these grid cells with oak saplings fetched from the store, one sapling each. */
    static OrchardJob plant(Worker w,List<BlockPos> cells){return new OrchardJob(w,"сажает яблоневый сад",true,cells,cells.getFirst(),null,cells.size());}
    /** The daily round: bone meal (if any is to be fetched) on the saplings, then the drops under the trees. */
    static OrchardJob care(Worker w,BlockPos walkway,AABB area,List<BlockPos> saplings,int meal){
        return new OrchardJob(w,"обходит яблоневый сад",false,meal>0?saplings:List.of(),walkway,area,meal);
    }
    @Override String kind(){return OrchardFeature.KIND;}
    @Override BlockPos target(){return spot;}
    @Override int timeLimit(){return 3600;}
    @Override boolean keeps(ItemStack s){return phase!=Phase.DEPOSIT&&supply.test(s);}

    @Override boolean tick(){
        if(now()<nextAction)return true;
        switch(phase){
            case FETCH->{
                // Saplings or bone meal from the store; a round without bone meal still gathers.
                if(wanted>0&&w.count(supply)<wanted){
                    if(fetch==null)fetch=new Fetch(List.of(new Need(supply,wanted,planting?"саженцы дуба":"костная мука")));
                    var state=fetch.tick();
                    if(state==Approach.State.MOVING)return true;
                    if(state==Approach.State.FAILED&&planting&&w.count(supply)==0)return fail("нет саженцев дуба");
                }
                phase=Phase.TREES;
            }
            case TREES->{
                if(!trees())return true;
                if(planting)return false;
                phase=Phase.GATHER;gatherUntil=now()+GATHER_TICKS;
                applesBefore=w.count(s->s.is(Items.APPLE));dropsBefore=w.count(OrchardFeature.LEAF_DROP);
                status="собирает яблоки в саду";
            }
            case GATHER->{
                if(gather())return true;
                if(walk!=null){walk.cancel();walk=null;}
                OrchardFeature.gathered(v,Math.max(0,w.count(s->s.is(Items.APPLE))-applesBefore));
                if(w.count(OrchardFeature.LEAF_DROP)<=dropsBefore)return false;
                phase=Phase.DEPOSIT;deposit=new Deposit();status="несёт яблоки на склад";
            }
            case DEPOSIT->{
                var state=deposit.tick();
                if(state==Approach.State.MOVING)return true;
                v.stock.invalidate();
                return false;
            }
        }
        return true;
    }
    /** Plants or feeds the next cell; true once none is left. */
    private boolean trees(){
        while(!cells.isEmpty()){
            if(w.count(supply)==0){cells.clear();break;}
            BlockPos cell=cells.peek();
            if(!(planting?OrchardFeature.plantable(v,cell):OrchardFeature.young(v,cell))){cells.poll();approach=null;continue;}
            if(approach==null||!approach.target.equals(cell))approach=new Approach(cell,4.0,false);
            var state=approach.tick();
            if(state==Approach.State.MOVING)return false;
            cells.poll();approach=null;
            if(state==Approach.State.FAILED){unreachableCell(cell);v.rejected.put(cell.asLong(),now()+2400);continue;}
            if(planting){
                if(Construction.place(v,bot(),new Construction.Step(cell,Blocks.OAK_SAPLING.defaultBlockState(),Items.OAK_SAPLING))){planted++;OrchardFeature.planted(v,cell);}
            }else if(Supplies.consume(bot(),Items.BONE_MEAL,1)){
                if(BoneMealItem.growCrop(new ItemStack(Items.BONE_MEAL),v.level,cell)){fertilized++;v.level.levelEvent(1505,cell,15);}
                body().swing(InteractionHand.MAIN_HAND);
            }
            nextAction=now()+10;
            return false;
        }
        if(fertilized>0){v.chronicle.add("orchardFertilized",fertilized);fertilized=0;}
        return true;
    }
    /** Walks into the orchard, then from drop to drop under the trees; true while there is more to do. */
    private boolean gather(){
        if(now()>gatherUntil||pickups>=MAX_PICKUPS)return false;
        if(!there){
            if(walk==null)walk=new Walk(Vec3.atBottomCenterOf(spot),3.0);
            var state=walk.tick();
            if(state==Approach.State.MOVING)return true;
            walk=null;there=true;
            if(state==Approach.State.FAILED)return false;
        }
        if(drop==null||!drop.isAlive()){
            walk=null;
            drop=v.level.getEntitiesOfClass(ItemEntity.class,area,e->e.isAlive()&&!e.hasPickUpDelay()&&OrchardFeature.LEAF_DROP.test(e.getItem())&&!skipped.contains(e.getId()))
                    .stream().min(Comparator.comparingDouble(e->e.distanceToSqr(body()))).orElse(null);
            if(drop==null)return false;
        }
        if(walk==null)walk=new Walk(drop.position(),1.0);
        var state=walk.tick();
        if(state==Approach.State.MOVING)return true;
        w.pickup();pickups++;
        if(drop.isAlive())skipped.add(drop.getId());
        drop=null;walk=null;
        return true;
    }
    @Override void stop(){if(fetch!=null)fetch.cancel();if(approach!=null)approach.cancel();if(walk!=null)walk.cancel();if(deposit!=null)deposit.cancel();}
}
