package dev.autonomousvillages;

import java.util.*;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;

/**
 * Builds the next batch of a project: clears soil and plants in the way, fetches what the store has
 * for the batch and places the blocks bottom-up. Steps without material are left for later.
 */
final class BuildJob extends Job {
    final TownBuilding project;
    private final ArrayDeque<BlockPos> cuts;
    private final ArrayDeque<TownBuilding.Plan> steps=new ArrayDeque<>();
    private Fetch fetch;
    private Approach approach;
    private Harvest harvest;
    private int placed,misses;
    private boolean fetched;
    BuildJob(Worker w,TownBuilding project){
        super(w,"строит: "+Projects.label(project));this.project=project;
        cuts=new ArrayDeque<>(v.town.pendingCuts(project,6));
    }
    @Override String kind(){return "build";}
    @Override String activity(){return "build";}
    @Override boolean mayBreak(BlockPos p){return cuts.contains(p)||harvest!=null&&harvest.target.equals(p);}
    /** Several builders share a site; the steps themselves are not reserved. */
    @Override BlockPos target(){return null;}
    @Override int timeLimit(){return 3600;}
    @Override boolean atomic(){return harvest!=null;}
    @Override boolean keeps(ItemStack s){return steps.stream().anyMatch(p->Construction.material(p.step()).test(s));}

    /** What one batch of the project needs, limited to steps the store (plus the bag) can pay for. */
    List<TownBuilding.Plan> affordable(int limit){
        var out=new ArrayList<TownBuilding.Plan>();var reserved=new IdentityHashMap<Predicate<ItemStack>,Integer>();var tests=new HashMap<String,Predicate<ItemStack>>();
        for(var p:v.town.nextSteps(project,48)){
            var test=tests.computeIfAbsent(Projects.materialKey(p.block()),k->Construction.material(p.step()));
            int cost=Construction.cost(p.step()),used=reserved.getOrDefault(test,0);
            if(v.stock.count(test)+w.count(test)>=used+cost){reserved.put(test,used+cost);out.add(p);if(out.size()>=limit)break;}
        }
        return out;
    }
    @Override boolean tick(){
        if(harvest!=null){
            var state=harvest.tick();
            if(state==Harvest.State.WORKING)return true;
            harvest=null;approach=null;cuts.poll();
        }
        while(!cuts.isEmpty()&&Construction.clear(v.level.getBlockState(cuts.peek())))cuts.poll();
        if(!cuts.isEmpty()){
            status="расчищает площадку";
            if(approach==null)approach=new Approach(cuts.peek(),4.5,false);
            var state=approach.tick();
            if(state==Approach.State.MOVING)return true;
            if(state==Approach.State.FAILED){cuts.poll();approach=null;return true;}
            harvest=new Harvest(cuts.peek());approach=null;return true;
        }
        if(fetch==null){
            steps.addAll(affordable(16));
            if(steps.isEmpty())return fail("стройке не хватает материалов: "+String.join(", ",v.town.missing(project).keySet()));
            var needs=new LinkedHashMap<String,Need>();
            for(var p:steps)needs.merge(Projects.materialKey(p.block()),new Need(Construction.material(p.step()),Construction.cost(p.step()),Projects.materialKey(p.block())),
                    (a,b)->new Need(a.test(),a.amount()+b.amount(),a.label()));
            // Plain rubble goes into the walls first: cobblestone also makes the furnace (charcoal,
            // torches, glass), tools and stone shapes (rc10: a house ate it all and no furnace came).
            needs.computeIfPresent("cobblestone",(k,n)->v.stock.count(VillageCrafting.RUBBLE)>=n.amount()?new Need(VillageCrafting.RUBBLE,n.amount(),n.label()):n);
            fetch=new Fetch(List.copyOf(needs.values()));
        }
        if(!fetched){
            if(fetch.tick()==Approach.State.MOVING){status="берёт материалы на складе";return true;}
            fetched=true; // whatever the store had; placing uses it up and must not trigger new fetches
        }
        while(!steps.isEmpty()&&(steps.peek().matches(v)||Construction.available(bot(),steps.peek().step())==null||!Construction.clear(v.level.getBlockState(steps.peek().position())))){steps.poll();approach=null;}
        if(steps.isEmpty())return placed>0?false:fail("материалы не донесены");
        var plan=steps.peek();
        status="строит: "+Projects.label(project)+" "+v.town.built(project)+"/"+v.town.size(project);
        if(approach==null)approach=new Approach(plan.position(),4.2,false);
        var state=approach.tick();
        if(state==Approach.State.MOVING)return true;
        if(state==Approach.State.FAILED){steps.poll();approach=null;if(++misses>=6)return placed>0?false:fail("не подойти к стройке");return true;}
        var step=Construction.available(bot(),plan.step());
        if(step!=null&&Construction.place(v,bot(),step)){placed++;v.chronicle.placed();}
        else if(++misses>=6)return placed>0?false:fail("не удаётся поставить блоки");
        steps.poll();approach=null;
        return true;
    }
    @Override void stop(){if(fetch!=null)fetch.cancel();if(approach!=null)approach.cancel();if(harvest!=null)harvest.cancel();}
}
