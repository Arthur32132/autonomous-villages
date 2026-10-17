package dev.autonomousvillages;

import java.util.*;
import dev.navigationai.api.BotState;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;

/** Server-thread planner. Nodes describe real observations; only this class creates supply orders. */
final class SettlementNeeds {
    enum Stage { SHELTER, BED, WOOL, TOOL, IRON, MINE_KIT, WORKPLACE, TRANSPORT, TIMBER, STONE, STORAGE, FOOD }
    enum Route { PLANKS, LOGS }
    enum Action { NONE, FETCH, BUILD, CRAFT, WOOL, MINE, SMELT, WORKPLACE, WOOD, STONE, FOOD, SEEDS, STORAGE }
    enum State { DONE, READY, WAITING }
    record Key(long work,String purpose) { }
    record Need(Key key,Stage stage,String reason,int priority,String executor,String condition,
                List<Key> dependsOn,boolean observed,Action action,Orders.Material material,int required,
                String produces,String blocked) {
        Need { dependsOn=List.copyOf(dependsOn); }
        long work(){return key.work();}
        String purpose(){return key.purpose();}
    }
    record Resolution(State state,String reason) { }

    static final class Plan {
        private final List<Need> needs;
        private final Map<Key,Need> byKey;
        private final Map<Key,Resolution> states=new HashMap<>();
        Plan(List<Need> source){
            needs=source.stream().sorted(Comparator.comparingInt(Need::priority)).toList();
            byKey=new LinkedHashMap<>();
            for(var n:needs)if(byKey.put(n.key(),n)!=null)throw new IllegalArgumentException("Duplicate need: "+n.key());
            for(var n:needs)resolve(n.key(),new HashSet<>());
        }
        static Plan empty(){return new Plan(List.of());}
        List<Need> needs(){return needs;}
        List<Need> open(){return needs.stream().filter(n->state(n).state()==State.READY).toList();}
        Optional<Need> find(long work,String purpose){return Optional.ofNullable(byKey.get(new Key(work,purpose)));}
        Resolution state(Need n){return states.get(n.key());}
        boolean done(Key key){return states.containsKey(key)&&states.get(key).state()==State.DONE;}
        boolean claims(long work,String purpose){return find(work,purpose).map(n->state(n).state()!=State.WAITING).orElse(false);}
        private Resolution resolve(Key key,Set<Key> visiting){
            if(states.containsKey(key))return states.get(key);
            var n=byKey.get(key);
            if(n==null)throw new IllegalArgumentException("Missing dependency: "+key);
            if(!visiting.add(key))throw new IllegalArgumentException("Cyclic dependency: "+key);
            // Observed physical output can satisfy a need without manufacturing it again.
            Resolution result=new Resolution(State.READY,"");
            for(var dep:n.dependsOn()){
                var dependency=resolve(dep,visiting);
                if(dependency.state()!=State.DONE&&result.state()==State.READY)
                    result=new Resolution(State.WAITING,"ждёт "+dep.purpose()+": "+(dependency.reason().isEmpty()?byKey.get(dep).condition():dependency.reason()));
            }
            if(n.material()!=null&&n.action()==Action.FETCH&&n.blocked().isEmpty())result=new Resolution(State.READY,"");
            if(n.observed())result=new Resolution(State.DONE,"");
            else if(!n.blocked().isEmpty())result=new Resolution(State.WAITING,n.blocked());
            else if(n.executor().isEmpty())result=new Resolution(State.WAITING,"исполнитель не назначен");
            visiting.remove(key);states.put(key,result);return result;
        }
        String summary(){
            var pending=needs.stream().filter(n->state(n).state()!=State.DONE).limit(8).toList();
            if(pending.isEmpty())return "нет открытых потребностей";
            return String.join(", ",pending.stream().map(n->n.purpose()+" ["+state(n).state().name().toLowerCase(Locale.ROOT)+"]").toList());
        }
        List<Need> runnable(Resident r){return needs.stream().filter(n->n.executor().equals(r.id.toString())
                &&n.action()!=Action.NONE&&state(n).state()==State.READY).toList();}
    }

    private final Village v;
    private final List<Need> needs=new ArrayList<>();
    private final Set<Key> roots=new LinkedHashSet<>();
    private final List<Resident> workers;
    private final Resident builder,keeper,miner,steward,logger,farmer;
    private SettlementNeeds(Village village){
        v=village;
        workers=v.workers.values().stream().filter(r->r.bot()!=null&&r.bot().state()==BotState.ACTIVE
                &&r.body().isAlive()&&!r.body().isNoAi()&&!r.body().isBaby()&&!v.expeditions.owns(r.id))
                .sorted(Comparator.comparing(r->r.id.toString())).toList();
        builder=choose(ResidentRoles.Role.BUILDER);keeper=v.farm.caretaker();miner=choose(ResidentRoles.Role.MINER);
        steward=choose(ResidentRoles.Role.STEWARD);logger=choose(ResidentRoles.Role.LOGGER);farmer=choose(ResidentRoles.Role.FARMER);
    }
    private Resident choose(ResidentRoles.Role role){
        var exact=workers.stream().filter(r->v.hasRole(r.id,role)).findFirst().orElse(null);
        if(exact!=null)return exact;
        return workers.stream().filter(r->switch(role){
            case BUILDER->v.builder(r.id);case STEWARD->v.steward(r.id);case MINER->v.miner(r.id);
            case LOGGER->!v.guard(r.id)&&!v.porter(r.id);case FARMER->v.farmer(r.id);
            default->false;
        }).findFirst().orElse(null);
    }
    private static String who(Resident r){return r==null?"":r.id.toString();}
    private Key add(long work,String purpose,Stage stage,Resident executor,int priority,String reason,String condition,
                    boolean observed,Action action,List<Key> deps){
        return add(work,purpose,stage,executor,priority,reason,condition,observed,action,deps,null,0,"","");
    }
    private Key add(long work,String purpose,Stage stage,Resident executor,int priority,String reason,String condition,
                    boolean observed,Action action,List<Key> deps,Orders.Material material,int quantity,String produces,String blocked){
        var key=new Key(work,purpose);
        if(needs.stream().noneMatch(n->n.key().equals(key)))needs.add(new Need(key,stage,reason,priority,who(executor),condition,deps,
                observed,action,material,Math.max(0,quantity),produces,blocked));
        return key;
    }
    private Key supply(long work,String purpose,Orders.Material material,int target,Resident receiver,int priority,String reason){
        var existing=v.state.orders.stream().filter(o->o.work==work&&o.purpose.equals(purpose)&&o.kind()==material
                &&o.recipient.equals(who(receiver))&&o.quantity==target).findFirst().orElse(null);
        return add(work,purpose,Stage.TRANSPORT,receiver,priority,reason,"доставлено "+target+" "+material,
                target==0||existing!=null&&existing.delivered>=target,Action.FETCH,List.of(),material,target,"",receiver==null?"получатель не назначен":"");
    }
    private int count(Resident r,Orders.Material material){return r==null?0:r.count(material);}
    private Key table(Resident r,int priority){
        long work=v.state.center().asLong();String suffix=who(r);
        var wood=supply(work,"plan_table_wood_"+suffix,Orders.Material.PLANKS,4,r,priority,"переносной верстак для рецепта 3×3");
        boolean ready=r!=null&&v.index.tables.stream().anyMatch(p->v.level.getBlockState(p).is(Blocks.CRAFTING_TABLE)
                &&p.distSqr(r.body().blockPosition())<32*32);
        return add(work,"table_"+suffix,Stage.WORKPLACE,r,priority,"рецепту нужно рабочее место","доступен установленный верстак",
                ready,Action.WORKPLACE,List.of(wood));
    }
    private Key timber(long work,String prefix,int target,Resident r,int priority,String reason){
        int planks=count(r,Orders.Material.PLANKS),logs=count(r,Orders.Material.LOGS);
        var moving=v.state.orders.stream().filter(o->o.purpose.startsWith(prefix)&&(o.transit||!o.carrier.isEmpty())
                &&(o.kind()==Orders.Material.LOGS||o.kind()==Orders.Material.PLANKS)).findFirst().orElse(null);
        var route=chooseRoute(planks,logs,v.count(Orders.Material.PLANKS.accepts),v.count(Orders.Material.LOGS.accepts),
                moving==null?null:moving.kind()==Orders.Material.LOGS);
        if(route==Route.PLANKS)return supply(work,prefix+"planks",Orders.Material.PLANKS,target,r,priority,reason);
        var prior=v.state.orders.stream().filter(o->o.work==work&&o.purpose.equals(prefix+"planks")&&o.recipient.equals(who(r))).findFirst().orElse(null);
        int credited=prior==null?0:Math.min(target,Math.max(0,prior.delivered-prior.craftedCoverage));
        var input=supply(work,prefix+"logs",Orders.Material.LOGS,Math.max(0,(target-credited+3)/4),r,priority,reason+": выбран распил брёвен");
        // This is a local manufacturing target, not a second external delivery alternative.
        return add(work,prefix+"planks",Stage.TIMBER,r,priority,reason,"доски изготовлены и выделены этой работе",
                prior!=null&&prior.quantity==target&&prior.delivered>=target,Action.CRAFT,List.of(input),Orders.Material.PLANKS,target,"planks","");
    }
    private Key miningTool(int priority){
        long work=v.state.center().asLong();String suffix=who(miner);
        boolean ready=miner!=null&&Supplies.find(miner.bot(),stack->stack.is(ItemTags.PICKAXES)&&Supplies.usable(stack))>=0;
        var wood=timber(work,"plan_bootstrap_pick_"+suffix+"_",3,miner,priority,"деревянная кирка до первого булыжника");
        var sticks=supply(work,"plan_bootstrap_sticks_"+suffix,Orders.Material.STICKS,2,miner,priority,"рукоять первой кирки");
        var stickWood=timber(work,"plan_bootstrap_stickwood_"+suffix+"_",2,miner,priority,"палки первой кирки");
        var stickCraft=add(work,"bootstrap_sticks_"+suffix,Stage.TOOL,miner,priority,"первая кирка","две палки имеются",
                count(miner,Orders.Material.STICKS)>=2,Action.CRAFT,List.of(stickWood),null,0,"sticks","");
        link(sticks,stickCraft);
        return add(work,"bootstrap_pick_"+suffix,Stage.TOOL,miner,priority,"добыча первого камня","пригодная кирка имеется",ready,
                Action.CRAFT,List.of(wood,sticks,table(miner,priority)),null,0,"wooden_pick","");
    }
    private Key mineKit(int priority){
        long work=v.state.center().asLong();String suffix=who(miner);var deps=new ArrayList<Key>();
        var kit=miner==null?new MineSupplies.Kit(0,0,0,0):MineSupplies.inspect(miner);
        int otherPicks=miner==null?0:Supplies.count(miner.bot().inventory().snapshot().slots(),stack->stack.is(ItemTags.PICKAXES)
                &&!stack.is(Items.STONE_PICKAXE)&&stack.isCorrectToolForDrops(Blocks.IRON_ORE.defaultBlockState())
                &&stack.getMaxDamage()-stack.getDamageValue()>=32);
        int newPicks=Math.max(0,2-otherPicks-count(miner,Orders.Material.SURVEY_PICK));
        var pick=supply(work,"plan_mine_picks_"+suffix,Orders.Material.SURVEY_PICK,Math.max(0,2-otherPicks),miner,priority,"основная и запасная каменные кирки для железа");
        var stone=supply(work,"plan_mine_stone_"+suffix,Orders.Material.COBBLESTONE,3*newPicks,miner,priority,"недостающие каменные инструменты");
        var sticks=supply(work,"plan_mine_sticks_"+suffix,Orders.Material.STICKS,2*newPicks,miner,priority,"рукояти шахтных инструментов");
        var wood=timber(work,"plan_mine_wood_"+suffix+"_",4,miner,priority,"рукояти кирок");
        var stickCraft=add(work,"mine_sticks_"+suffix,Stage.TOOL,miner,priority,"рукояти кирок","палки для недостающих кирок готовы",
                count(miner,Orders.Material.STICKS)>=2*newPicks,Action.CRAFT,List.of(wood),null,0,"sticks","");
        link(sticks,stickCraft);
        var craft=add(work,"mine_picks_"+suffix,Stage.TOOL,miner,priority,"без каменной кирки железо не добыть","две пригодные кирки",
                kit.primary()>=32&&kit.spare()>=24,Action.CRAFT,List.of(stone,sticks,stickCraft,table(miner,priority)),null,0,"survey_pick","");
        link(pick,craft);
        // Existing picks satisfy their input chain without consuming new cobblestone.
        deps.add(add(work,"mine_tools_"+suffix,Stage.TOOL,miner,priority,"шахтный инструмент","основная и запасная кирки",
                kit.primary()>=32&&kit.spare()>=24,Action.NONE,List.of(pick,craft)));
        int nativeFood=miner==null?0:Supplies.count(Supplies.copy(miner.body().getInventory()),Supplies::food);
        deps.add(supply(work,"plan_mine_food_"+suffix,Orders.Material.FOOD,Math.max(0,3-nativeFood),miner,priority,"питание в шахте"));
        deps.add(supply(work,"plan_mine_exit_"+suffix,Orders.Material.COBBLESTONE,
                Math.max(0,8-count(miner,Orders.Material.PLANKS)-(miner==null?0:miner.bot().inventory().count(Items.DIRT))),miner,priority,"аварийные блоки выхода"));
        return add(work,"mine_kit_"+suffix,Stage.MINE_KIT,miner,priority,"железо для зависимых изделий","шахтный комплект готов",
                kit.ready(),Action.NONE,deps);
    }
    private Key ironProduction(int target,int priority){
        long work=v.state.center().asLong();Resident smelter=steward;
        int held=v.count(Orders.Material.IRON.accepts),raw=v.count(Orders.Material.RAW_IRON.accepts),cooking=0;
        boolean furnace=false;
        for(long p:v.state.furnaces)if(v.level.getBlockEntity(BlockPos.of(p)) instanceof FurnaceBlockEntity f&&!f.isLocked()){
            furnace=true;cooking+=(f.getItem(0).is(Items.RAW_IRON)?f.getItem(0).getCount():0)
                    +(f.getItem(2).is(Items.IRON_INGOT)?f.getItem(2).getCount():0);
        }
        int batch=Math.min(8,Math.max(0,target-held-cooking));
        var rawSupply=supply(work,"plan_raw_iron",Orders.Material.RAW_IRON,batch,smelter,priority,"общая партия железа для изделий");
        var mine=add(work,"mine_iron",Stage.IRON,miner,priority,"сырьё для изделий","руда для партии добыта",
                held+raw+cooking>=Math.min(target,held+cooking+8),Action.MINE,List.of(mineKit(priority)));
        link(rawSupply,mine);
        var fuel=supply(work,"plan_fuel_iron",Orders.Material.FUEL,batch>0?1:0,smelter,priority,"топливо партии железа");
        var furnaceStone=supply(work,"plan_furnace_stone",Orders.Material.COBBLESTONE,furnace?0:8,smelter,priority,"печь для железа");
        var furnaceNode=add(work,"furnace",Stage.WORKPLACE,smelter,priority,"выплавка слитков","установлена незапертая печь",furnace,
                Action.WORKPLACE,List.of(furnaceStone,table(smelter,priority)));
        var charcoalWood=supply(work,"plan_charcoal_logs",Orders.Material.LOGS,1,smelter,priority,"бревно для древесного угля");
        var charcoalFuel=supply(work,"plan_charcoal_bootstrap",Orders.Material.PLANKS,2,smelter,priority,"растопка первой партии угля");
        var charcoal=add(work,"charcoal",Stage.IRON,smelter,priority,"топливо печи","уголь доступен",
                v.count(Orders.Material.FUEL.accepts)>0,Action.SMELT,List.of(furnaceNode,charcoalWood,charcoalFuel));
        link(fuel,charcoal);
        return add(work,"produce_iron",Stage.IRON,smelter,priority,"общая цепочка железа","выплавлены слитки для всех действующих изделий",
                held>=target,Action.SMELT,List.of(rawSupply,fuel,furnaceNode));
    }
    private Key iron(IronSupply.Need request,int priority,Key production){
        var r=request.recipient();long work=v.state.center().asLong();String product=request.purpose();String purpose=product+"@"+r.id;
        var ingots=supply(work,purpose,Orders.Material.IRON,request.ingots(),r,priority,"изделие: "+purpose);
        link(ingots,production);
        var deps=new ArrayList<Key>(List.of(ingots));
        if(!product.equals("iron_shears"))deps.add(table(r,priority));
        return add(work,"craft_"+purpose,Stage.TOOL,r,priority,"производственный заказ "+product,"изделие пригодно и находится у исполнителя",
                false,Action.CRAFT,deps,null,0,product,"");
    }
    private void derive(){
        long center=v.state.center().asLong();
        var equipment=new ArrayList<IronSupply.Need>();
        var reusable=new EnumMap<Orders.Material,Integer>(Orders.Material.class);
        for(var request:IronSupply.requests(v)){
            var material=productMaterial(request.purpose());
            int left=reusable.computeIfAbsent(material,m->{
                int stored=v.chestCount(m);
                for(var worker:workers)stored+=Math.max(0,worker.count(m)-worker.kit(m));
                return stored;
            });
            if(left>0){
                reusable.put(material,left-1);
                if(!request.purpose().equals("iron_shears"))roots.add(supply(center,"plan_finished_"+request.purpose()+"@"+request.recipient().id,
                        material,1,request.recipient(),20,"использовать готовое изделие вместо нового железа"));
            }else equipment.add(request);
        }
        var tools=new HashMap<String,Key>();int rank=0;
        var production=equipment.isEmpty()?null:ironProduction(equipment.stream().mapToInt(IronSupply.Need::ingots).sum(),20);
        for(var request:equipment){
            var tool=iron(request,20+rank++,production);tools.put(request.purpose()+":"+who(request.recipient()),tool);roots.add(tool);
        }
        Key shears=null;
        boolean missingBeds=v.state.buildings.stream().flatMap(b->b.blocks.stream()).anyMatch(p->p.block().getBlock() instanceof BedBlock&&!p.matches(v));
        if(missingBeds){
            var delivered=supply(center,"bed_shears",Orders.Material.SHEARS,1,keeper,10,"общие ножницы для всех заказов кроватей");
            var manufacture=tools.get("iron_shears:"+who(keeper));
            shears=add(center,"shears_ready",Stage.TOOL,keeper,10,"шерсть для всех домов","пригодные ножницы у смотрителя",
                    count(keeper,Orders.Material.SHEARS)>0,Action.NONE,manufacture==null?List.of(delivered):List.of(delivered,manufacture));
        }
        int preparedBeds=count(builder,Orders.Material.BEDS);
        int totalBeds=(int)v.state.buildings.stream().flatMap(b->b.blocks.stream())
                .filter(p->p.block().getBlock() instanceof BedBlock&&!p.matches(v)).count();
        var colours=new HashMap<Item,Integer>();
        for(var chest:v.chests())for(var stack:Supplies.copy(chest))if(stack.is(ItemTags.WOOL))colours.merge(stack.getItem(),stack.getCount(),Integer::sum);
        for(var worker:workers)for(var stack:worker.bot().inventory().snapshot().slots())if(stack.is(ItemTags.WOOL))colours.merge(stack.getItem(),stack.getCount(),Integer::sum);
        int usableWool=colours.values().stream().mapToInt(n->n/3*3).sum();
        Key woolProduction=totalBeds==0?null:add(center,"produce_wool",Stage.WOOL,keeper,10,"все заказы кроватей",
                "достаточно шерсти одного цвета на каждую кровать",usableWool>=3*Math.max(0,totalBeds-preparedBeds),Action.WOOL,List.of(shears));
        var housing=new ArrayList<Key>();
        for(var b:v.state.buildings){
            int beds=(int)b.blocks.stream().filter(p->p.block().getBlock() instanceof BedBlock&&!p.matches(v)).count();
            var shell=add(b.origin,"shell",Stage.SHELTER,builder,10,"дом должен укрывать жителей","все элементы кроме кроватей установлены",
                    b.blocks.stream().filter(p->!(p.block().getBlock() instanceof BedBlock)).allMatch(p->p.matches(v)),Action.BUILD,List.of());
            var dependencies=new ArrayList<Key>(List.of(shell));
            if(beds>0){
                int prepared=Math.min(beds,preparedBeds);preparedBeds-=prepared;
                var planks=timber(b.origin,"bed_",3*beds,builder,10,"древесина кроватей дома");
                var wool=supply(b.origin,"bed_wool",Orders.Material.WOOL,3*beds,builder,10,"шерсть одного цвета на каждую кровать");
                link(wool,woolProduction);
                var craft=add(b.origin,"craft_beds",Stage.BED,builder,10,"дом должен получить кровати","готовые кровати у строителя",
                        prepared>=beds,Action.CRAFT,List.of(planks,wool,table(builder,10)),null,0,"beds","");
                dependencies.add(add(b.origin,"beds",Stage.BED,builder,10,"заселение дома","все половины кроватей установлены",false,
                        Action.BUILD,List.of(shell,craft)));
            }
            var usable=add(b.origin,"habitable",Stage.SHELTER,builder,10,"пригодное жильё","дом построен, подход и кровати пригодны",
                    b.completed&&b.blocks.stream().allMatch(p->p.matches(v))&&b.foundation.stream().allMatch(p->p.matches(v))
                            &&Infrastructure.prepared(v,b)&&b.blocks.stream().filter(p->p.block().getBlock() instanceof BedBlock)
                            .allMatch(p->v.town.knownShelter(p.position())),Action.BUILD,dependencies);
            if(b.blocks.stream().anyMatch(p->p.block().getBlock() instanceof BedBlock)){
                housing.add(usable);
                boolean occupied=workers.stream().anyMatch(r->r.body().getBrain().getMemory(MemoryModuleType.HOME)
                        .filter(home->home.dimension().equals(v.level.dimension())&&b.blocks.stream().anyMatch(p->p.block().getBlock() instanceof BedBlock
                            &&(p.position().equals(home.pos())||p.position().relative(p.block().getValue(BedBlock.FACING)).equals(home.pos())))).isPresent());
                roots.add(add(b.origin,"settled",Stage.SHELTER,builder,10,"заселение заказанного дома","житель выбрал кровать в пригодном доме",occupied&&b.completed&&b.blocks.stream().allMatch(p->p.matches(v))
                        &&Infrastructure.prepared(v,b)&&b.blocks.stream().filter(p->p.block().getBlock() instanceof BedBlock).allMatch(p->v.town.knownShelter(p.position())),
                        Action.NONE,List.of(usable)));
            }else roots.add(usable);
        }
        for(var r:workers)if(v.hasRole(r.id,ResidentRoles.Role.BUILDER)){
            var plans=v.town.wantedPlans(r);String prefix=ConstructionSupply.prefix(r);
            var wood=plans.stream().filter(p->ConstructionSupply.wood(p.block())&&!(p.block().getBlock() instanceof BedBlock)).findFirst().orElse(null);
            long work=wood==null?center:v.state.buildings.stream().filter(b->b.blocks.contains(wood)||b.foundation.contains(wood)).mapToLong(b->b.origin).findFirst().orElse(center);
            if(wood!=null){
                var batch=timber(work,prefix,32,r,30,"строительная партия");roots.add(batch);link(new Key(work,"shell"),batch);
            }
            int stone=TownCrafting.ingredientReserve(r,new ItemStack(Items.COBBLESTONE));
            if(stone>0)roots.add(supply(work,prefix+"stone",Orders.Material.COBBLESTONE,stone,r,30,"каменные детали стройки"));
        }
        var budget=SettlementEconomy.budget(v);
        var food=add(center,"reserve_food",Stage.FOOD,farmer,5,"питание жителей и производственных групп","достигнут запас еды "+budget.food(),
                SettlementEconomy.food(v)>=budget.food(),Action.FOOD,List.of());roots.add(food);
        roots.add(add(center,"reserve_seeds",Stage.FOOD,farmer,40,"семенной фонд","достигнут запас семян "+budget.seed(),
                v.count(Orders.Material.SEEDS.accepts)>=budget.seed(),Action.SEEDS,List.of()));
        roots.add(add(center,"reserve_timber",Stage.TIMBER,logger,40,"производственный запас древесины","достигнут запас древесины "+budget.timber(),
                SettlementEconomy.wood(v)>=budget.timber(),Action.WOOD,List.of()));
        var fieldDeps=new ArrayList<Key>();
        var irrigator=ManagedLand.irrigator(v);var bucket=tools.get("iron_bucket:"+who(irrigator));
        if(bucket!=null)fieldDeps.add(bucket);
        var field=add(center,"growth_field",Stage.FOOD,farmer,40,"урожай для роста поселения","есть орошаемое поле не меньше 16 клеток",
                v.state.land.fields.stream().anyMatch(f->v.level.getBlockState(BlockPos.of(f.water)).is(Blocks.WATER)
                    &&f.cells.stream().filter(cell->v.level.getBlockState(BlockPos.of(cell).below()).is(Blocks.FARMLAND)).count()>=16),
                Action.FOOD,fieldDeps);
        housing.add(food);housing.add(field);
        roots.add(add(center,"growth",Stage.SHELTER,farmer,40,"рост поселения","две свободные кровати, поле и запас еды",
                SettlementEconomy.canGrow(v),Action.NONE,housing));
        roots.add(add(center,"reserve_breeding_wheat",Stage.FOOD,farmer,40,"корм племенного стада",
                "сохранено пшеницы: "+budget.breedingWheat(),v.count(stack->stack.is(Items.WHEAT))>=budget.breedingWheat(),Action.FOOD,List.of()));
        var chestWood=timber(center,"plan_chest_",v.needsChest()?8:0,steward,15,"общий склад");
        roots.add(add(center,"storage",Stage.STORAGE,steward,15,"склад для продукции","есть свободное общее хранилище",!v.needsChest(),
                Action.STORAGE,List.of(chestWood,table(steward,15))));
    }

    private void link(Key child,Key producer){
        for(int i=0;i<needs.size();i++)if(needs.get(i).key().equals(child)){
            var n=needs.get(i);var deps=new ArrayList<>(n.dependsOn());deps.add(producer);
            needs.set(i,new Need(n.key(),n.stage(),n.reason(),n.priority(),n.executor(),n.condition(),deps,n.observed(),n.action(),
                    n.material(),n.required(),n.produces(),n.blocked()));return;
        }
    }
    /** Completed outputs release their manufacturing inputs; unrelated stocks cannot hold them. */
    private void retainDependencies(){
        var index=new HashMap<Key,Need>();for(var n:needs)index.put(n.key(),n);
        var priority=new HashMap<Key,Integer>();
        var pending=new ArrayDeque<Key>(roots);
        for(var root:roots)priority.put(root,index.get(root).priority());
        while(!pending.isEmpty()){
            var key=pending.removeFirst();var n=index.get(key);
            if(n.observed())continue;
            for(var dep:n.dependsOn())if(priority.getOrDefault(dep,Integer.MAX_VALUE)>priority.get(key)){
                priority.put(dep,priority.get(key));pending.addLast(dep);
            }
        }
        var retained=new LinkedHashSet<Key>();var queue=new ArrayDeque<Key>(roots);
        while(!queue.isEmpty()){
            var key=queue.removeFirst();if(!retained.add(key))continue;
            var n=index.get(key);if(n==null)throw new IllegalArgumentException("Missing dependency: "+key);
            if(!n.observed())queue.addAll(n.dependsOn());
        }
        needs.removeIf(n->!retained.contains(n.key()));
        for(int i=0;i<needs.size();i++){
            var n=needs.get(i);
            needs.set(i,new Need(n.key(),n.stage(),n.reason(),priority.getOrDefault(n.key(),n.priority()),n.executor(),n.condition(),
                    n.observed()?List.of():n.dependsOn(),n.observed(),n.action(),n.material(),n.required(),n.produces(),n.blocked()));
        }
    }

    /** One producer per material accounts for the sum of all consumers, including their own stock. */
    private void attachSources(){
        Key pick=null;
        if(needs.stream().anyMatch(n->n.material()==Orders.Material.COBBLESTONE&&!n.observed()))pick=miningTool(10);
        final Key miningPick=pick;
        for(var material:List.of(Orders.Material.PLANKS,Orders.Material.LOGS,Orders.Material.COBBLESTONE,Orders.Material.FOOD,Orders.Material.SEEDS)){
            var consumers=needs.stream().filter(n->n.material()==material&&!n.produces().equals("planks")).toList();if(consumers.isEmpty())continue;
            int target=consumers.stream().mapToInt(Need::required).sum(),stock=v.chestCount(material);
            var consumerKeys=new HashSet<Key>();for(var consumer:consumers)consumerKeys.add(consumer.key());
            stock+=v.state.orders.stream().filter(o->o.kind()==material&&consumerKeys.contains(new Key(o.work,o.purpose)))
                    .mapToInt(o->o.craftedCoverage).sum();
            for(var worker:workers){
                int own=consumers.stream().filter(n->n.executor().equals(who(worker))).mapToInt(Need::required).sum();
                int protectedElsewhere=v.state.orders.stream().filter(o->o.kind()==material&&o.recipient.equals(who(worker))
                        &&!consumerKeys.contains(new Key(o.work,o.purpose))).mapToInt(o->Math.max(0,o.delivered-o.craftedCoverage)).sum();
                int held=Math.max(0,worker.count(material)-protectedElsewhere),personal=Math.min(held,worker.kit(material));
                stock+=Math.max(0,held-personal)+Math.min(held,Math.min(personal,own));
            }
            Resident producer=switch(material){case PLANKS,LOGS->logger;case COBBLESTONE->miner;default->farmer;};
            Action action=switch(material){case PLANKS,LOGS->Action.WOOD;case COBBLESTONE->Action.STONE;case SEEDS->Action.SEEDS;default->Action.FOOD;};
            int priority=consumers.stream().mapToInt(Need::priority).min().orElse(40);
            var source=add(v.state.center().asLong(),"produce_"+material.name().toLowerCase(Locale.ROOT),Stage.TOOL,producer,priority,
                    "суммарные заявки "+material,"достаточно не обещанного другим работам материала",stock>=target,action,material==Orders.Material.COBBLESTONE&&miningPick!=null?List.of(miningPick):List.of());
            for(var consumer:consumers)link(consumer.key(),source);
        }
    }

    private static Orders.Material productMaterial(String purpose){
        return switch(purpose){
            case "iron_shears"->Orders.Material.SHEARS;case "iron_pickaxe"->Orders.Material.IRON_PICKAXE;
            case "iron_bucket"->Orders.Material.BUCKET;case "iron_offhand"->Orders.Material.SHIELD;
            case "iron_chest"->Orders.Material.CHESTPLATE;case "iron_head"->Orders.Material.HELMET;
            case "iron_legs"->Orders.Material.LEGGINGS;case "iron_feet"->Orders.Material.BOOTS;
            default->throw new IllegalArgumentException(purpose);
        };
    }
    /** A recipe may spend its own inputs or unpromised stock, never another work's inputs. */
    static boolean mayCraft(Village v,Resident r,ItemStack out,List<ItemStack> grid,List<ItemStack> before){
        Orders.refreshStock(v);
        for(var material:Orders.Material.values()){
            int spent=Supplies.count(grid,material.accepts);if(spent==0)continue;
            int protectedStock=v.state.orders.stream().filter(o->!o.returnCargo()&&o.kind()==material&&o.recipient.equals(who(r))
                    &&!recipeOwns(o,out)).mapToInt(o->Math.max(0,o.delivered-o.craftedCoverage)).sum();
            if(Supplies.count(before,material.accepts)-spent<protectedStock)return false;
        }
        return true;
    }
    static int furnaceAllowance(Village v,Resident r,Orders.Material material){
        Orders.refreshStock(v);
        int protectedStock=v.state.orders.stream().filter(o->o.kind()==material&&o.recipient.equals(who(r))&&!o.returnCargo()
                &&!(o.purpose.equals("plan_raw_iron")||o.purpose.equals("plan_fuel_iron")||o.purpose.startsWith("plan_charcoal_")))
                .mapToInt(o->Math.max(0,o.delivered-o.craftedCoverage)).sum();
        return Math.max(0,r.count(material)-protectedStock);
    }
    static boolean mayPlace(Village v,Resident r,Construction.Step step){
        Orders.refreshStock(v);
        if(new ItemStack(step.item()).is(ItemTags.BEDS)){
            var promised=new HashMap<Long,Integer>();
            for(var o:v.state.orders)if(o.recipient.equals(who(r))&&o.craftedCoverage>0
                    &&v.state.buildings.stream().filter(b->b.origin==o.work).noneMatch(b->b.blocks.stream().anyMatch(p->p.position().equals(step.pos()))))
                promised.merge(o.work,o.craftedCoverage/3,Math::max);
            int protectedBeds=promised.values().stream().mapToInt(Integer::intValue).sum();
            if(r.count(Orders.Material.BEDS)-1<protectedBeds)return false;
        }
        for(var material:Orders.Material.values())if(material.accepts.test(new ItemStack(step.item()))){
            int protectedStock=v.state.orders.stream().filter(o->o.kind()==material&&!o.returnCargo()&&o.recipient.equals(who(r))
                    &&!(o.purpose.startsWith("b_")&&v.state.buildings.stream().anyMatch(b->b.origin==o.work
                        &&java.util.stream.Stream.concat(b.blocks.stream(),b.foundation.stream()).anyMatch(p->p.position().equals(step.pos())))))
                    .mapToInt(o->Math.max(0,o.delivered-o.craftedCoverage)).sum();
            if(r.count(material)-Construction.cost(step)<protectedStock)return false;
        }
        return true;
    }
    private static boolean recipeOwns(Orders.Order o,ItemStack out){
        String p=o.purpose;
        if(out.is(ItemTags.BEDS))return p.equals("bed_planks")||p.equals("bed_wool");
        if(out.is(Items.CRAFTING_TABLE))return p.startsWith("plan_table_wood_");
        if(out.is(Items.FURNACE))return p.equals("plan_furnace_stone");
        if(out.is(Items.CHEST))return p.startsWith("plan_chest_");
        if(out.is(ItemTags.PLANKS))return o.kind()==Orders.Material.LOGS&&!p.equals("plan_charcoal_logs");
        if(out.is(Items.STICK))return p.startsWith("plan_mine_wood_")||p.startsWith("plan_bootstrap_stickwood_");
        if(out.is(Items.WOODEN_PICKAXE))return p.startsWith("plan_bootstrap_pick_")||p.startsWith("plan_bootstrap_sticks_");
        if(out.is(Items.STONE_PICKAXE))return p.startsWith("plan_mine_stone_")||p.startsWith("plan_mine_sticks_");
        if(p.startsWith("iron_"))return out.is(IronSupply.output(p.split("@",2)[0]));
        return p.startsWith("b_")&&out.getItem() instanceof BlockItem;
    }

    static Route chooseRoute(int planks,int logs,int globalPlanks,int globalLogs,Boolean movingLogs){
        if(movingLogs!=null)return movingLogs?Route.LOGS:Route.PLANKS;
        int externalLogs=Math.max(0,globalLogs-logs);
        return globalPlanks-planks<=0&&(logs>0||externalLogs>0)?Route.LOGS:Route.PLANKS;
    }
    static boolean managed(Orders.Order o){return o.purpose.startsWith("bed_")||o.purpose.startsWith("iron_")
            ||o.purpose.startsWith("b_")||o.purpose.startsWith("plan_");}
    /** Reconcile all production orders together, retaining identity of compatible live deliveries. */
    static void sync(Village v,long now){
        Orders.refreshStock(v);
        var planner=new SettlementNeeds(v);planner.derive();planner.retainDependencies();planner.attachSources();planner.retainDependencies();
        var wanted=planner.needs.stream().filter(n->n.material()!=null&&n.required()>0&&!n.executor().isEmpty())
                .sorted(Comparator.comparingInt(Need::priority)).toList();
        var keys=new HashSet<Key>();for(var n:wanted)keys.add(n.key());
        if(wanted.size()+v.state.orders.stream().filter(o->!managed(o)).count()>Orders.MAX_ORDERS)
            throw new IllegalStateException("Лимит заказов: план не применён; действующие заказы сохранены");
        boolean changed=false;
        for(var o:List.copyOf(v.state.orders))if(managed(o)&&!keys.contains(new Key(o.work,o.purpose))){
            // Cancellation is metadata only; the courier retains physical cargo and is released.
            v.state.orders.remove(o);Orders.release(o);changed=true;
        }
        for(var n:wanted){
            changed|=Orders.applyScoped(v.state.orders,n.work(),List.of(new Orders.Demand(n.material(),n.purpose(),n.required())),
                    n.executor(),v.meetingPoint().asLong(),now,n.purpose());
            var order=v.state.orders.stream().filter(o->o.work==n.work()&&o.purpose.equals(n.purpose())).findFirst().orElseThrow();
            boolean local=n.material()==Orders.Material.PLANKS&&n.produces().equals("planks");
            if(order.localOnly!=local){order.localOnly=local;changed=true;}
        }
        var priorities=new HashMap<Key,Integer>();for(var n:wanted)priorities.put(n.key(),n.priority());
        var before=List.copyOf(v.state.orders);
        v.state.orders.sort(Comparator.comparingInt(o->priorities.getOrDefault(new Key(o.work,o.purpose),0)));
        changed|=!before.equals(v.state.orders);
        changed|=Orders.refreshStock(v);
        // Stock observations are allocated once, in the same order used by dispatch and fetch.
        var allocation=Orders.allocations(v);
        var observations=new ArrayList<Need>();
        for(var n:planner.needs){
            if(n.material()==null){observations.add(n);continue;}
            var o=v.state.orders.stream().filter(order->order.work==n.work()&&order.purpose.equals(n.purpose())).findFirst().orElse(null);
            boolean done=n.required()==0||o!=null&&o.delivered>=o.quantity;
            String blocked=n.blocked();
            if(!done&&o!=null&&!o.localOnly){
                var reserved=allocation.get(o);
                if(reserved==null||reserved.total()==0)blocked="нет свободного "+n.material()+"; ожидает производство";
                else if(!o.carrier.isEmpty())blocked="доставка: "+o.carrier;
                else blocked="";
            }
            observations.add(new Need(n.key(),n.stage(),n.reason(),n.priority(),n.executor(),n.condition(),n.dependsOn(),done,
                    n.action(),n.material(),n.required(),n.produces(),blocked));
        }
        v.needs=new Plan(observations);
        if(changed)v.runtime.save();
    }
    static boolean needsMineKit(Village v,Resident r){return v.needs.needs().stream().anyMatch(n->n.stage()==Stage.MINE_KIT
            &&n.executor().equals(who(r))&&v.needs.state(n).state()!=State.DONE);}
    static List<String> lines(Plan plan){
        return plan.needs().stream().filter(n->plan.state(n).state()!=State.DONE).map(n->{
            var state=plan.state(n);
            return n.work()+"/"+n.purpose()+" ["+state.state()+", приоритет "+n.priority()+"] "+(n.required()>0?"×"+n.required()+" ":"")
                    +(n.executor().isEmpty()?"исполнитель не назначен":n.executor())+"; причина: "+n.reason()+"; условие: "+n.condition()
                    +(state.reason().isEmpty()?"":"; ЖДЁТ: "+state.reason())+"; зависимости: "+n.dependsOn();
        }).toList();
    }
}
