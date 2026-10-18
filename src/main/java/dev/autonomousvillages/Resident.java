package dev.autonomousvillages;

import dev.navigationai.api.*;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.phys.*;

/** A bounded work state machine. Every resident has at most one claimed target and one library task. */
final class Resident {
    static final int BUILDING_TIMBER=64;
    static final NavigationPolicy TRAVEL = new NavigationPolicy(false,false,true,true,32_000,1024,32,200,2);
    final Village v; final UUID id;
    final BreathingSafety breathing=new BreathingSafety(this);
    String status="осматривается", job;
    BlockPos target, treeBase, shelter;
    NavigationTask navigation;
    MiningTask mining;
    ExcavationTask excavation;
    CombatTask combat;
    UUID supplyRecipient;
    UUID deliverRecipient;
    Orders.Order deliverOrder;
    UUID haulDonor;
    private int deliveryKit=-1;
    DeliveryRuns.Agreement agreement;
    Construction.Step build;
    TownBuilding.Plan townPlan;
    long since, cooldown, collectingUntil;
    private final InfrastructureWaitAccounting workWait=new InfrastructureWaitAccounting();
    String lastFailure="";
    final Set<BlockPos> triedApproaches=new HashSet<>();
    BlockPos lastApproach;
    boolean fresh=true;
    boolean clearingLeaves;
    boolean needsWorkbench;
    long lastRestHeal;
    long lastRecovery;
    boolean fleeing;
    private final Map<String,Long> delays=new HashMap<>();
    private final Map<Long,Long> homeRetries=new HashMap<>();
    Resident(Village v, UUID id) { this.v=v;this.id=id; }
    BotHandle bot() { return v.runtime.bots.find(id).orElse(null); }
    Villager body() { return (Villager)bot().entity(); }
    long now() { return v.level.getGameTime(); }
    int count(Orders.Material material){BotHandle b=bot();return b==null?0:Supplies.count(b.inventory().snapshot().slots(),material.accepts);}
    /** Personal emergency kit for a whole material class; never shared with another worker. */
    int kit(Orders.Material material){
        BotHandle b=bot();if(b==null)return 0;
        var bag=b.inventory().snapshot().slots();
        int held=Supplies.count(bag,material.accepts);
        if(held==0){
            var cargo=v.state.cargoKits.get(id);if(cargo!=null){cargo.remove(material);if(cargo.isEmpty())v.state.cargoKits.remove(id);}
            return 0;
        }
        int keep=0;var counted=new HashSet<Item>();
        if(material==Orders.Material.SHEARS||bag.stream().anyMatch(s->material.accepts.test(s)&&!PersonalKits.family(s).isEmpty())){
            for(int slot:protectedSlots())if(material.accepts.test(bag.get(slot)))keep+=bag.get(slot).getCount();
        }
        else for(ItemStack stack:bag)if(!stack.isEmpty()&&material.accepts.test(stack)&&counted.add(stack.getItem()))keep=Math.max(keep,reserve(stack,v.steward(id)));
        keep=Math.max(keep,Supplies.count(bag,s->material.accepts.test(s)&&s.has(DataComponents.CUSTOM_NAME)));
        // Cargo just accepted by a porter is not retroactively turned into his own kit.
        if(deliveryKit>=0&&deliverOrder!=null&&deliverOrder.kind()==material)return Math.min(held,deliveryKit);
        var cargoKit=v.state.cargoKits.get(id);
        if(cargoKit!=null&&cargoKit.containsKey(material)){
            int personal=cargoKit.get(material);
            if(held<=personal){cargoKit.remove(material);if(cargoKit.isEmpty())v.state.cargoKits.remove(id);}
            else return Math.min(held,personal);
        }
        return Math.min(held,keep);
    }
    private Set<Integer> protectedSlots(){
        boolean shepherd=v.farm.caretaker()==this||"shear".equals(job)
                ||v.expeditions.owns(id)&&v.state.expedition!=null&&"wool".equals(v.state.expedition.purpose);
        return PersonalKits.kept(bot().inventory().snapshot().slots(),v.primaryRole(id),shepherd,
                v.state.cargoKits.getOrDefault(id,Map.of()));
    }
    boolean shareableSlot(Orders.Material material,int slot){
        var s=bot().inventory().snapshot().slots().get(slot);
        return material.accepts.test(s)&&!s.has(DataComponents.CUSTOM_NAME)
                &&!protectedSlots().contains(slot);
    }
    List<ItemStack> shareable(Orders.Material material){
        var bag=bot().inventory().snapshot().slots();var out=new ArrayList<ItemStack>();
        int left=Math.max(0,count(material)-kit(material));
        for(int i=0;i<bag.size()&&left>0;i++)if(shareableSlot(material,i)){
            int n=Math.min(left,bag.get(i).getCount());out.add(bag.get(i).copyWithCount(n));left-=n;
        }
        return out;
    }
    /** Inventory operations stop intents, so external exchanges use an idle boundary. */
    boolean canExchange(){
        // Inventory mutations stop library intents. Recheck this boundary at handoff.
        var b=bot();return b!=null&&b.state()==BotState.ACTIVE&&body().isAlive()&&!body().isBaby()&&!body().isNoAi()
                &&!body().isTrading()&&!body().isSleeping()&&!fleeing&&combat==null&&navigation==null
                &&mining==null&&excavation==null&&collectingUntil==0&&b.miner().status()!=MiningStatus.RUNNING
                &&b.navigator().status()!=NavigationStatus.RUNNING&&b.navigator().status()!=NavigationStatus.COMPUTING
                &&b.combat().status()!=CombatStatus.RUNNING
                &&(job==null||"farm_watch".equals(job));
    }
    boolean canFetch(Orders.Order order){
        if(order.kind()==null||combat!=null||mining!=null||excavation!=null||v.expeditions.owns(id))return false;
        if(v.claimableFromChests(order)==0)return false;
        return v.chests().stream().anyMatch(chest->{
            if(Supplies.count(Supplies.copy(chest),order.kind().accepts)==0)return false;
            if(body().position().distanceToSqr(Vec3.atCenterOf(chest.getBlockPos()))<=16&&lineOfSight(bot(),chest.getBlockPos()))return true;
            var path=body().getNavigation().createPath(chest.getBlockPos(),1);
            return path!=null&&path.canReach();
        });
    }
    /** Physically carry order material to the worker who needs it. */
    void deliver(Orders.Order order,UUID recipient){
        deliver(order,recipient,-1);
    }
    void deliver(Orders.Order order,UUID recipient,int personalKit){
        cancel();
        deliverOrder=order;deliveryKit=personalKit;
        order.inTransit=Math.min(v.claimableFromBag(order,this),Math.max(0,count(order.kind())-kit(order.kind())));
        order.transit=order.inTransit>0;order.carrier=id.toString();
        if(!order.transit||!DeliveryRuns.reserve(this,order,null,v.workers.get(recipient),order.inTransit)){Orders.release(order);finish();return;}
        order.inTransit=Math.min(order.inTransit,agreement.quantity);
        deliverOrder=order;deliverRecipient=recipient;
        job="deliver";target=v.workers.get(recipient).body().blockPosition();shelter=null;since=now();
        workWait.reset();navigation=null;mining=null;collectingUntil=0;triedApproaches.clear();lastApproach=null;
        status="несёт материалы по заказу исполнителю";
    }
    /** Continue the existing promise: picked-up stock must not be reallocated to chests. */
    void carryCollectedOrder(int moved,int personalKit){
        agreement.quantity=moved;agreement.pickedUp=true;
        deliveryKit=personalKit;haulDonor=null;
        // Receiving a better tool can change which concrete stack is surplus.
        // Confirm capacity for that selection without releasing the order's cargo.
        agreement.cargo=shareable(agreement.material);
        agreement.quantity=DeliveryRuns.room(agreement.recipient.bot().inventory().snapshot().slots(),
                agreement.cargo,agreement.material.accepts,Math.min(moved,deliverOrder.outstanding()));
        if(agreement.quantity==0){
            deliverOrder.waiting="после получения груза нет места для доступных компонентов; груз сохранён";
            cancel();return;
        }
        deliverOrder.inTransit=agreement.quantity;
        deliverOrder.transit=deliverOrder.inTransit>0;deliverOrder.carrier=id.toString();
        deliverOrder.waiting="груз получен; носильщик идёт к исполнителю";
        job="deliver";target=agreement.recipient.body().blockPosition();shelter=null;since=now();
        workWait.reset();navigation=null;mining=null;collectingUntil=0;triedApproaches.clear();lastApproach=null;
        status="несёт материалы по заказу исполнителю";
    }
    boolean ripeHarvest(){
        return v.index.crops.stream().anyMatch(p->v.state.policy.allows("farm",p)&&v.level.getBlockState(p).getBlock() instanceof CropBlock c&&c.isMaxAge(v.level.getBlockState(p)));
    }
    boolean nativeDutyTime(){
        long day=Math.floorMod(v.level.getOverworldClockTime(),24000);
        int start=2000+roleIndexSlot()%8*600;
        return day>=start&&day<start+600&&body().getBrain().getMemory(MemoryModuleType.JOB_SITE)
                .filter(p->p.dimension().equals(v.level.dimension())).isPresent();
    }
    private int roleIndexSlot(){return v.roleIndex(id);}
    /** Help is a bounded job, never a new specialization. Protect native schedules and urgent work. */
    boolean canHelp(){
        if(bot()==null||bot().state()!=BotState.ACTIVE||!body().isAlive()||body().isBaby()||body().isNoAi()
                ||body().isSleeping()||body().isTrading()||combat!=null||mining!=null||excavation!=null||collectingUntil>0
                ||body().getHealth()<12||v.expeditions.owns(id)||nativeDutyTime()||nightRestWindow())return false;
        long day=Math.floorMod(v.level.getOverworldClockTime(),24000);int social=9000+roleIndexSlot()%3*1000;
        if(day>=social&&day<social+1000||v.guard(id)||v.farmer(id)&&ripeHarvest())return false;
        return job==null||Set.of("farm_watch","wood","seeds","pickup").contains(job);
    }
    void collectOrder(Orders.Order order,Resident donor,UUID recipient){
        cancel();assign("collect_order",donor.body().blockPosition());
        if(!"collect_order".equals(job))return;
        deliverOrder=order;deliverRecipient=recipient;haulDonor=donor.id;
        if(!DeliveryRuns.reserve(this,order,donor,v.workers.get(recipient),v.claimableFromBag(order,donor))){finish();return;}
        order.carrier=id.toString();status="забирает подтверждённый излишек для доставки";
    }
    void tick() {
        tick(true);
    }
    void tick(boolean mayPlan) {
        BotHandle b=bot();
        if (b==null||b.state()!=BotState.ACTIVE||!b.entity().isAlive()) { status="не загружен";return; }
        if (fresh) { b.stopAll();fresh=false; }
        Villager e=(Villager)b.entity();
        if(e.isSleeping()&&e.getSleepingPos().filter(p->!v.town.shelterIntact(p)).isPresent())e.stopSleeping();
        if (e.level()!=v.level) { cancel();status="в другом измерении";return; }
        if(breathing.tick())return;
        if(thermalSafety(b))return;
        restRecovery(b);
        secureDoors(b);
        if (e.isTrading()||e.isBaby()||e.isNoAi()) { cancel();status="торгует или отдыхает";return; }
        if ((combat!=null||now()%10==Math.floorMod(id.hashCode(),10))&&danger(b)) return;
        if (combat!=null) { if (!combat.completion().isDone()&&now()-since<600)return;cancel(); }
        if(nightRestWindow()&&"escape".equals(job)&&mining==null&&excavation==null&&b.miner().status()!=MiningStatus.RUNNING
                &&(atRestHome()||v.index.beds.stream().anyMatch(p->p.distSqr(e.blockPosition())<64)))cancel();
        if(recoverAtHome(b))return;
        if(job==null&&!v.state.contains(e.blockPosition())){
            pickup(b);
            if(travelTools(b))return;
            assign("home",v.meetingPoint());status="возвращается в деревню после вылазки";return;
        }
        long day=Math.floorMod(v.level.getOverworldClockTime(),24_000);
        var jobSite=e.getBrain().getMemory(MemoryModuleType.JOB_SITE).filter(p->p.dimension().equals(v.level.dimension()));
        int schedule=v.roleIndex(id);
        int professionalStart=2000+(schedule%8)*600;
        boolean professionTime=day>=professionalStart&&day<professionalStart+600&&jobSite.isPresent();
        int socialStart=9000+(schedule%3)*1000;
        boolean socialTime=day>=socialStart&&day<socialStart+1000;
        boolean nightRest=nightRestWindow();
        boolean breakTime=nightRest || socialTime || professionTime;
        // A failed overnight route must not consume the next working day. The resident
        // is already inside the village; injuries and expedition returns retain their own recovery.
        if("home".equals(job)&&!nightRest&&e.getHealth()>=e.getMaxHealth()&&v.state.contains(e.blockPosition())&&!v.expeditions.owns(id))cancel();
        if("native_work".equals(job)&&!professionTime)cancel();
        // A social/work schedule is not a reason to erase block-damage progress. Complete the atomic
        // action and collect its drops first. Dangerous combat still preempts immediately above.
        boolean finishingBlock=mining!=null||excavation!=null||collectingUntil>0||"escape".equals(job);
        if (breakTime&&!finishingBlock) {
            String returnKind=professionTime?"native_work":"home";
            if (job!=null&&!job.equals(returnKind)) cancel();
            if (e.isSleeping()) { status="спит";return; }
            if (job==null) {
                BlockPos home=professionTime?jobSite.orElseThrow().pos():restHome();
                if ((nightRest||professionTime)&&(e.blockPosition().distSqr(home)>(nightRest?4:9)
                        ||nightRest&&!lineOfSight(b,home))&&ready(returnKind)) { assign(returnKind,home);return; }
                if(nightRest){
                    var nativeHome=e.getBrain().getMemory(MemoryModuleType.HOME).filter(p->p.dimension().equals(v.level.dimension())).map(GlobalPos::pos);
                    var bed=v.level.getBlockState(home);
                    if(nativeHome.filter(home::equals).isEmpty()||bed.hasProperty(BedBlock.OCCUPIED)&&bed.getValue(BedBlock.OCCUPIED)){
                        b.activities().run(List.of(new BotActivity.Wait(40)));status="ночует в безопасном доме";return;
                    }
                }
                status=professionTime?"работает по профессии и обновляет торговлю":v.level.isDarkOutside()?"отдых и сон по правилам Minecraft":"общение, торговля и размножение";
                return; // release native Brain for social behavior and reproduction, at least 3000 ticks/day
            }
        }
        if(!breakTime&&v.expeditions.work(this))return;
        if("farm_watch".equals(job)&&DeliveryRuns.promised(this)&&canExchange())finish();
        if (job!=null) { work(b);return; }
        if(DeliveryRuns.hold(this))return;
        if(!mayPlan)return;
        if (now()<cooldown) return;
        cooldown=now()+20;
        pickup(b);
        if (feed(b)) {status="раздаёт еду";return;}
        boolean steward=v.steward(id);
        if(v.expeditions.preparingSurveyPick(id)&&(prepareCrafting(b,steward)||depositOrFetch(b,steward)))return;
        if((Supplies.find(b,s->s.is(ItemTags.PICKAXES)&&Supplies.usable(s))<0
                ||Supplies.find(b,s->s.is(ItemTags.AXES)&&Supplies.usable(s))<0)&&prepareCrafting(b,steward))return;
        for(var need:v.needs.runnable(this))if(plannedWork(b,need))return;
        if(constructionWork(b))return;
        // Bring excess cargo to the shared store before accepting another project. Otherwise a
        // repeatable renovation can keep the equipped shearer ineligible for every wool outing.
        // depositAmount retains the tools, food and materials needed by the current building plan.
        if(shouldUnloadBeforeWork()&&depositOrFetch(b,steward))return;
        if(foodWork(b))return;
        if(v.farm.planWork(this))return;
        if(ProductionBuildings.work(this))return;
        if(Infrastructure.work(this))return;
        if(v.guard(id)){
            if(prepareCrafting(b,steward))return;
            guardDuty(b);return;
        }
        if(v.porter(id)){
            if(depositOrFetch(b,steward)||salvage(b))return;
            status="ждёт заявки на переноску; носильщик";return;
        }
        if(v.builder(id)&&startBuilding(b,true))return;
        if(steward&&production(b))return;
        if(infrastructure(b,steward))return;
        if(!v.state.reforestation.isEmpty()&&plantTree(b))return;
        if(buildingTimber(b))return;
        if(Supplies.find(b,s->s.is(ItemTags.AXES)&&Supplies.usable(s))>=0&&startBuilding(b,true))return;
        if(prepareCrafting(b,steward))return;
        if(requestedResourceWork(b))return;
        if(startBuilding(b))return;
        if (depositOrFetch(b,steward)) return;
        if(!needsWorkbench&&recoverWorkbench(b))return;
        // Wheat needs a workbench before it becomes food, and supplies need a depot before they can
        // be shared. Do not let endlessly renewed harvest jobs starve this bootstrap dependency.
        if(v.chests().isEmpty()&&Supplies.count(b.inventory().snapshot().slots(),s->s.is(ItemTags.LOGS)||s.is(ItemTags.PLANKS))<(steward?32:12)){
            BlockPos log=nearest(v.index.trees,this::treeCandidate);
            if(log!=null){assign("wood",log);return;}
        }
        BlockPos crop=nearest(v.index.crops,p->v.state.policy.allows("farm",p)&&v.level.getBlockState(p).getBlock() instanceof CropBlock c&&c.isMaxAge(v.level.getBlockState(p)));
        if (crop!=null&&SettlementEconomy.food(v)<SettlementEconomy.budget(v).food()&&v.activeJobs("harvest","plant","hoe")<Math.max(1,v.workers.size()/3)) {assign("harvest",crop);return;}
        BlockPos soil=nearest(v.index.soil,p->v.state.policy.allows("farm",p)&&v.level.getBlockState(p).isAir()&&v.level.getBlockState(p.below()).is(Blocks.FARMLAND));
        if (soil!=null&&seed(b)!=null) {assign("plant",soil);return;}
        if(v.farmer(id)){
            if(farmExpand(b))return;
            if(seed(b)==null&&v.count(s->s.is(Items.WHEAT_SEEDS))<32){
                BlockPos grass=nearest(v.index.grass,p->v.level.getBlockState(p).is(Blocks.SHORT_GRASS)||v.level.getBlockState(p).is(Blocks.TALL_GRASS));
                if(grass!=null){assign("seeds",grass);return;}
            }
        }
        if(v.town.pending(Items.GLASS_PANE)&&v.count(s->s.is(Items.GLASS)||s.is(Items.SAND))<24&&v.activeJobs("sand")<1){
            BlockPos resource=nearest(v.index.sand,p->v.level.getBlockState(p).is(Blocks.SAND)&&!v.town.protects(p));
            if(resource!=null){assign("sand",resource);return;}
        }
        if (steward&&v.state.shelter!=null) {
            Construction.Step requested=Construction.next(v);
            Construction.Step step=requested==null?null:Construction.available(b,requested);
            if (step!=null&&Construction.clear(v.level.getBlockState(step.pos()))&&v.ready(step.pos(),id)) {build=step;assign("build",step.pos());return;}
        }
        if (steward&&ready("furnace")) for (long packed:v.state.furnaces) {
            BlockPos p=BlockPos.of(packed);
            if(v.level.getBlockEntity(p) instanceof FurnaceBlockEntity f && !f.isLocked() && VillageWorkshop.wantsFurnace(b,v,f)) {assign("furnace",p);return;}
        }
        int wood=v.count(s->s.is(ItemTags.LOGS)||s.is(ItemTags.PLANKS));
        if(v.hasRole(id,ResidentRoles.Role.MINER)&&v.state.expedition==null&&miningWork(b))return;
        if (wood<Math.max(v.town.woodDemand(),v.workers.size()*24)) {
            BlockPos log=nearest(v.index.trees,this::treeCandidate);
            if(log!=null){assign("wood",log);return;}
        }
        if(miningWork(b))return;
        if (seed(b)==null&&v.count(s->s.is(Items.WHEAT_SEEDS))<16) {
            BlockPos grass=nearest(v.index.grass,p->v.level.getBlockState(p).is(Blocks.SHORT_GRASS)||v.level.getBlockState(p).is(Blocks.TALL_GRASS));
            if(grass!=null){assign("seeds",grass);return;}
        }
        if (farmExpand(b)) return;
        if (salvage(b)) return;
        if (plantTree(b)) return;
        if (steward&&shear(b)) return;
        if (recoverScaffolding(b))return;
        if (steward&&v.state.bounds==null&&now()%200<30) Construction.start(v);
        if (e.blockPosition().distSqr(v.state.center())>20*20&&ready("home")) {assign("home",v.state.center());return;}
        status="отдыхает; ждёт урожай и потребности деревни";
    }
    private void guardDuty(BotHandle b){
        BlockPos post=ProductionBuildings.post(v);
        if(body().blockPosition().distSqr(post)>36&&ready("guard")){assign("guard",post);return;}
        b.activities().run(List.of(new BotActivity.Wait(20)));status="охраняет жилую часть деревни";
    }
    private boolean miningWork(BotHandle b){
            if(IronSupply.rawNeeded(v)>0) {
                BlockPos ore=nearest(v.index.ore,p->MineKnowledge.iron(v,p)&&VillageIndex.exposed(v.level,p)&&oreHarvestable(b,p));
                if(ore!=null){assign("ore",ore);return job!=null;}
            }
        if (Supplies.find(b,s->s.is(ItemTags.PICKAXES)&&Supplies.usable(s))>=0) {
            boolean stoneRequested=v.needs.needs().stream().anyMatch(n->n.purpose().equals("produce_cobblestone")
                    &&v.needs.state(n).state()==SettlementNeeds.State.READY);
            if(stoneRequested||v.count(s->s.is(Items.COBBLESTONE))<Math.max(32,v.workers.size()*12)) {
                BlockPos stone=nearest(v.index.stone,p->v.state.policy.allows("mine",p)&&v.level.getBlockState(p).is(Blocks.STONE)&&VillageIndex.exposed(v.level,p));
                if(stone!=null){assign("stone",stone);return job!=null;}
            }
            if((stoneRequested||v.count(s->s.is(Items.COBBLESTONE))<Math.max(32,v.workers.size()*12)
                    ||IronSupply.rawNeeded(v)>0)&&startMine(b))return true;
        }
        return false;
    }
    /** A primary builder services construction before optional hauling, harvesting or logging. */
    private boolean constructionWork(BotHandle b){
        if(!v.hasRole(id,ResidentRoles.Role.BUILDER)||v.smallCrew())return false;
        // Grading is possible bare-handed, but must not consume every shift before
        // this specialist obtains the kit needed for the actual building and its inputs.
        if(needsBasicTools()){
            if(prepareCrafting(b,false)||infrastructure(b,false)||depositOrFetch(b,false))return true;
            if(!ConstructionSupply.awaiting(this)){
                var tree=nearest(v.index.trees,this::treeCandidate);if(tree!=null){assign("wood",tree);return true;}
            }
            status="стройка ждёт инструменты; доступен для другой работы";return false;
        }
        if(startBuilding(b,true)||constructionInputs(b)||startBuilding(b)||Infrastructure.work(this))return true;
        var plans=v.town.wantedPlans(this);if(plans.isEmpty())return false;
        if(prepareCrafting(b,false)||infrastructure(b,false)||depositOrFetch(b,false))return true;
        if(ConstructionSupply.awaiting(this)){status="стройка ждёт носильщика; доступен для другой работы";return false;}
        int timber=Supplies.count(b.inventory().snapshot().slots(),s->s.is(ItemTags.PLANKS))+4*Supplies.count(b.inventory().snapshot().slots(),s->s.is(ItemTags.LOGS));
        if(timber<16||needsBasicTools()){
            var tree=nearest(v.index.trees,this::treeCandidate);if(tree!=null){assign("wood",tree);return true;}
        }
        if(TownCrafting.ingredientReserve(this,new ItemStack(Items.COBBLESTONE))>b.inventory().count(Items.COBBLESTONE)
                &&Supplies.find(b,s->s.is(ItemTags.PICKAXES)&&Supplies.usable(s))>=0){
            var stone=nearest(v.index.stone,p->v.state.policy.allows("mine",p)&&v.level.getBlockState(p).is(Blocks.STONE)&&VillageIndex.exposed(v.level,p));
            if(stone!=null){assign("stone",stone);return true;}
        }
        status="стройка ждёт материалы или доступ; доступен для другой работы";return false;
    }
    /** Try useful ready leaves; waiting graph nodes never claim the resident's whole shift. */
    private boolean plannedWork(BotHandle b,SettlementNeeds.Need need){
        return switch(need.action()){
            case FETCH -> depositOrFetch(b,v.steward(id));
            case CRAFT -> {
                needsWorkbench=false;
                if(VillageWorkshop.makePlanned(b,v,need)){
                    b.activities().run(List.of(new BotActivity.Wait(20)));status="изготавливает по плану: "+need.produces();yield true;
                }
                yield needsWorkbench&&(infrastructure(b,v.steward(id))||prepareWorkbench(b,v.steward(id)));
            }
            case BUILD -> startBuilding(b,true)||constructionInputs(b)||startBuilding(b)||Infrastructure.work(this);
            case WOOL -> v.farm.planWork(this)||shear(b);
            case MINE -> miningWork(b);
            case SMELT -> production(b);
            case WORKPLACE -> {
                needsWorkbench=true;
                yield infrastructure(b,v.steward(id))||prepareWorkbench(b,v.steward(id))||prepareCrafting(b,v.steward(id));
            }
            case STORAGE -> prepareCrafting(b,true)||infrastructure(b,true);
            case WOOD -> {
                BlockPos tree=nearest(v.index.trees,this::treeCandidate);
                if(tree!=null)assign("wood",tree);yield job!=null;
            }
            case STONE -> miningWork(b);
            case FOOD -> foodWork(b)||farmExpand(b);
            case SEEDS -> {
                BlockPos grass=nearest(v.index.grass,p->v.level.getBlockState(p).is(Blocks.SHORT_GRASS)||v.level.getBlockState(p).is(Blocks.TALL_GRASS));
                if(grass!=null)assign("seeds",grass);yield job!=null;
            }
            case NONE -> false;
        };
    }
    private boolean constructionInputs(BotHandle b){
        needsWorkbench=false;
        int planks=Supplies.count(b.inventory().snapshot().slots(),VillageWorkshop.PLANK);
        if(planks<16&&VillageWorkshop.craft(b,v,new String[]{"M"},s->s.is(ItemTags.LOGS_THAT_BURN),VillageWorkshop.PLANK)
                ||TownCrafting.make(b,v)){
            b.activities().run(List.of(new BotActivity.Wait(20)));status="готовит строительную партию и детали перед кладкой";return true;
        }
        return needsWorkbench&&(infrastructure(b,false)||prepareWorkbench(b,false));
    }
    private boolean requestedResourceWork(BotHandle b){
        if(v.smallCrew()||SettlementEconomy.food(v)<Math.max(6,v.state.residents.size()))return false;
        boolean timber=v.hasRole(id,ResidentRoles.Role.LOGGER)&&v.state.orders.stream().anyMatch(o->o.open()&&!o.returnCargo()&&(o.kind()==Orders.Material.LOGS||o.kind()==Orders.Material.PLANKS));
        boolean minerals=v.hasRole(id,ResidentRoles.Role.MINER)&&v.state.orders.stream().anyMatch(o->o.open()&&!o.returnCargo()&&(o.kind()==Orders.Material.COBBLESTONE||o.kind()==Orders.Material.IRON));
        // The full growth reserve remains unchanged. With basic food already covered,
        // concrete input orders take precedence over optional harvest help and small unloading trips.
        if(minerals&&miningWork(b)){status="шахтёр добывает материалы по открытой заявке";return true;}
        if(timber&&v.count(s->s.is(ItemTags.LOGS)||s.is(ItemTags.PLANKS))<Math.max(v.town.woodDemand(),v.workers.size()*24)
                ||minerals&&needsBasicTools()){
            BlockPos tree=nearest(v.index.trees,this::treeCandidate);
            if(tree!=null){assign("wood",tree);status=timber?"лесоруб снабжает стройку по заявке":"шахтёр готовит древесину для своих инструментов";return job!=null;}
        }
        return false;
    }
    private boolean startBuilding(BotHandle b){
        return startBuilding(b,false);
    }
    private boolean startBuilding(BotHandle b,boolean nonPlanks){
        TownBuilding.Plan plan=v.town.request(this,nonPlanks);if(plan==null)return false;
        var step=Construction.available(b,plan.step());if(step==null)return false;
        townPlan=plan;build=step;assign(!Construction.clear(v.level.getBlockState(step.pos()))?"renovate":"build",step.pos());return true;
    }
    private boolean oreHarvestable(BotHandle b,BlockPos p) {
        return v.state.policy.allows("mine",p)&&VillageWorkshop.hasPickFor(b,v.level.getBlockState(p));
    }
    private boolean production(BotHandle b){
        if(v.state.furnaces.isEmpty())return false;
        if(ready("furnace"))for(long packed:v.state.furnaces){
            BlockPos p=BlockPos.of(packed);
            if(v.ready(p,id)&&v.level.getBlockEntity(p) instanceof FurnaceBlockEntity f&&!f.isLocked()&&VillageWorkshop.wantsFurnace(b,v,f)){
                assign("furnace",p);return job!=null;
            }
        }
        if(!ready("storage")||b.inventory().count(Items.RAW_IRON)>0)return false;
        var supply=v.chests().stream().filter(c->v.ready(c.getBlockPos(),id)&&c.countItem(Items.RAW_IRON)>0)
                .min(Comparator.comparingDouble(c->{double dy=c.getBlockPos().getY()-b.entity().getY();return c.getBlockPos().distSqr(b.entity().blockPosition())+15*dy*dy;})).orElse(null);
        if(supply==null)return false;
        assign("storage",supply.getBlockPos());status="забирает руду для настоящей печи";return job!=null;
    }
    private boolean ready(String key){return now()>=delays.getOrDefault(key,0L);}
    boolean prepareCrafting(BotHandle b,boolean steward){
        needsWorkbench=false;
        if(VillageWorkshop.make(b,v,steward)){
            b.activities().run(List.of(new BotActivity.Wait(20)));
            status="изготавливает инструменты и припасы";return true;
        }
        if(infrastructure(b,steward))return true;
        return prepareWorkbench(b,steward);
    }
    private boolean prepareWorkbench(BotHandle b,boolean steward){
        if(needsWorkbench){
            BlockPos table=nearest(v.index.tables,p->v.level.getBlockState(p).is(Blocks.CRAFTING_TABLE)&&p.distSqr(b.entity().blockPosition())<32*32);
            if(table!=null&&!VillageWorkshop.nearTable(b,v,table)&&ready("table")){assign("table",table);return true;}
            if(table==null&&b.inventory().count(Items.CRAFTING_TABLE)==0
                    &&v.chests().stream().anyMatch(c->c.countItem(Items.CRAFTING_TABLE)>0)&&depositOrFetch(b,steward))return true;
            if(table==null&&b.inventory().count(Items.CRAFTING_TABLE)==0&&VillageWorkshop.craft(b,v,Items.CRAFTING_TABLE,new String[]{"PP","PP"},VillageWorkshop.PLANK)){
                needsWorkbench=true;status="готовит переносной верстак для нужного рецепта";return true;
            }
        }
        return false;
    }
    private boolean buildingTimber(BotHandle b){
        if(!v.builder(id)||Supplies.find(b,s->s.is(ItemTags.AXES)&&Supplies.usable(s))<0)return false;
        var plans=v.town.wantedPlans(this);
        if(plans.stream().noneMatch(p->p.block().is(BlockTags.PLANKS)||p.block().is(BlockTags.LOGS)||p.block().is(BlockTags.WOODEN_STAIRS))
                ||plans.stream().anyMatch(p->p.position().distSqr(b.entity().blockPosition())<24*24))return false;
        var bag=b.inventory().snapshot().slots();
        if(Supplies.count(bag,s->s.is(ItemTags.PLANKS))+4*Supplies.count(bag,s->s.is(ItemTags.LOGS))>=v.town.timberBatch())return false;
        BlockPos log=nearest(v.index.trees,p->treeCandidate(p)&&p.distSqr(b.entity().blockPosition())<32*32);
        if(log==null)return false;
        assign("wood",log);status="заготавливает партию древесины перед походом на стройку";return job!=null;
    }
    boolean travelTools(BotHandle b){
        if(Supplies.find(b,s->s.is(ItemTags.PICKAXES)&&Supplies.usable(s))>=0)return false;
        if(prepareCrafting(b,v.steward(id)))return true;
        if(Supplies.count(b.inventory().snapshot().slots(),s->s.is(ItemTags.PLANKS))>=7)return false;
        BlockPos nearby=null;double distance=Double.POSITIVE_INFINITY;
        for(BlockPos mutable:BlockPos.betweenClosed(b.entity().blockPosition().offset(-8,-6,-8),b.entity().blockPosition().offset(8,8,8))){
            if(!v.level.hasChunkAt(mutable)||!v.ready(mutable,id)||v.town.protects(mutable)||!VillageIndex.naturalLog(v.level,mutable))continue;
            double score=b.entity().position().distanceToSqr(Vec3.atCenterOf(mutable));
            if(score<distance){distance=score;nearby=mutable.immutable();}
        }
        if(nearby==null)return false;
        v.index.trees.add(nearby);assign("wood",nearby);status="заготавливает дерево для замены походного инструмента";return job!=null;
    }
    private boolean treeCandidate(BlockPos base){
        if(v.town.protects(base)||!v.state.policy.allows("wood",base)||!ManagedLand.mayHarvest(v,base))return false;
        if(VillageIndex.naturalLog(v.level,base))return true;
        if(!v.state.fellingTrees.contains(base.asLong()))return false;
        BlockPos next=ForestWork.next(v,base);
        if(next!=null){
            if(v.builder(id)&&next.getY()-base.getY()>4&&Supplies.count(bot().inventory().snapshot().slots(),s->s.is(ItemTags.PLANKS))<8)return false;
            return true;
        }
        ForestWork.complete(v,base);return false;
    }
    private BlockPos nearest(Collection<BlockPos> positions,Predicate<BlockPos> valid){
        BotHandle b=bot();return positions.stream().filter(p->v.ready(p,id)&&valid.test(p))
                .min(Comparator.comparingDouble(p->{double dy=p.getY()-b.entity().getY();return b.entity().position().distanceToSqr(Vec3.atCenterOf(p))+15*dy*dy;})).orElse(null);
    }
    void assign(String kind,BlockPos p){
        if(!v.state.policy.allows(kind.equals("build")&&build!=null&&build.state().getBlock() instanceof SaplingBlock?"wood":activity(kind),p))return;
        if(exclusiveJob(kind)&&!v.reservations.acquire(p.asLong(),id,now()))return;
        if(kind.equals("wood")){
            if(!v.state.fellingTrees.contains(p.asLong())&&v.state.fellingTrees.size()>=128){v.reservations.release(id);return;}
            treeBase=p.immutable();
            BlockPos next=ForestWork.next(v,treeBase);if(next==null){ForestWork.complete(v,treeBase);return;}p=next;
            v.state.fellingTrees.add(treeBase.asLong());v.runtime.save();
        }
        job=kind;target=p.immutable();shelter=null;since=now();workWait.reset();navigation=null;mining=null;collectingUntil=0;
        triedApproaches.clear();lastApproach=null;
        status=switch(kind){case "wood"->"лесозаготовка";case "stone","ore"->"добывает камень и руду";case "harvest","plant","hoe"->"работает на грядках";
            case "storage"->"идёт на общий склад";case "delivery_return"->"освобождает стройку после доставки, возвращается к складу";case "build"->"строит инфраструктуру";case "home"->"возвращается домой";
            case "table"->"идёт к верстаку";case "furnace"->"обслуживает печь";case "seeds"->"ищет семена";
            case "mine"->"идёт ко входу в шахту";case "native_work"->"возвращается к рабочему месту";
            case "pickup"->"собирает припасы";case "shear"->"идёт стричь овец";case "supply"->"несёт припасы застрявшему путнику";default->"работает";};
    }
    void work(BotHandle b){
        if(!v.state.policy.allows(job.equals("build")&&build!=null&&build.state().getBlock() instanceof SaplingBlock?"wood":activity(job),target)){cancel();status="работа запрещена границей игрока";return;}
        if(Set.of("deliver","collect_order","collect_chest","deliver_chest").contains(job)&&!DeliveryRuns.valid(this))return;
        if(job.equals("herd")){v.farm.herd(this);return;}
        if(job.equals("collect_order")){
            var donor=v.workers.get(haulDonor);
            if(donor==null||donor.bot()==null||!donor.body().isAlive()||deliverOrder==null||!deliverOrder.open()
                    ||!v.state.orders.contains(deliverOrder)||!id.toString().equals(deliverOrder.carrier)
                    ||!Objects.toString(deliverRecipient,"").equals(deliverOrder.recipient)){cancel();return;}
            target=donor.body().blockPosition();
        }
        if(job.equals("supply")){
            var recipient=v.workers.get(supplyRecipient);
            if(recipient==null||recipient.bot()==null||!recipient.body().isAlive()||Supplies.find(recipient.bot(),s->s.is(ItemTags.PICKAXES)&&Supplies.usable(s))>=0){finish();return;}
            if(navigation==null||navigation.completion().isDone()){
                v.reservations.release(target.asLong(),id);target=recipient.body().blockPosition();
            }
        }
        if(job.equals("deliver")){
            var recipient=v.workers.get(deliverRecipient);
            // The order itself is the reason to walk: a completed or cancelled order ends the run.
            if(recipient==null||recipient.bot()==null||!recipient.body().isAlive()||deliverOrder==null||!v.state.orders.contains(deliverOrder)||!deliverOrder.open()
                    ||!recipient.id.toString().equals(deliverOrder.recipient)||!id.toString().equals(deliverOrder.carrier)){cancel();return;}
            target=recipient.body().blockPosition();
        }
        long infrastructureTicks=workWait.observe(navigation,excavation);
        if(now()-since-infrastructureTicks>(job.equals("mine")||job.equals("escape")?6000:2400)){fail("цель недоступна; выбирает другую работу");return;}
        if((exclusiveJob(job)&&!v.reservations.acquire(target.asLong(),id,now())) || treeBase!=null&&!v.reservations.acquire(treeBase.asLong(),id,now())){fail("цель занята другим работником");return;}
        // A route's preferred standing cell is only a means to reach the actual block. Do not
        // overshoot a visible reachable ore, then reject it because that cell is inaccessible.
        if(navigation!=null&&mining==null&&excavation==null&&collectingUntil==0
                &&!Set.of("mine","escape","supply","pickup","delivery_return").contains(job)
                &&b.entity().getEyePosition().distanceToSqr(Vec3.atCenterOf(target))<=workReach()*workReach()
                &&(job.equals("build")||lineOfSight(b,target))&&!(job.equals("build")&&build!=null&&Construction.intersectsActor(v,b,build))
                &&!(job.equals("renovate")&&b.entity().blockPosition().below().equals(target))){
            navigation.cancel();navigation=null;lastApproach=null;
        }
        if(excavation!=null){
            if(!excavation.completion().isDone())return;
            if(excavation.status()==ExcavationStatus.SUCCESS&&job.equals("mine")){v.state.mineLength=Math.min(32,v.state.mineLength+8);v.runtime.save();}
            else if(excavation.status()!=ExcavationStatus.SUCCESS){lastFailure="проходка: "+excavation.completion().join().reason();delays.put("mine",now()+1800);}
            if(job.equals("mine")&&v.state.mineEntrance!=null){
                var seen=MineKnowledge.record(v,v.state.mineEntrance,v.state.mineDirection);
                if(seen!=null){seen.length=Math.max(seen.length,excavation.completedSegments());seen.lastSurvey=now();
                    if(excavation.status()!=ExcavationStatus.SUCCESS){seen.blocked=true;var reason=excavation.completion().join().reason();seen.hazard=reason.substring(0,Math.min(160,reason.length()));}}
                v.runtime.save();
            }
            finish();return;
        }
        if(navigation!=null){
            if(!navigation.completion().isDone())return;
            if(navigation.status()!=NavigationStatus.SUCCESS){
                String reason=navigation.completion().join().reason();
                if(lastApproach!=null&&triedApproaches.size()<(job.equals("home")||job.equals("native_work")?2:10)){triedApproaches.add(lastApproach);navigation=null;lastApproach=null;
                    if(triedApproaches.size()>2&&job.equals("wood")&&AccessWorks.tree(this))return;
                }else{fail("маршрут: "+reason);return;}
            }
            if(lastApproach!=null&&!job.equals("build")&&!job.equals("supply")&&!lineOfSight(b,target))triedApproaches.add(lastApproach);
            navigation=null;
        }
        if(mining!=null){
            if(!mining.completion().isDone())return;
            if(mining.status()!=MiningStatus.SUCCESS){fail("добыча: "+mining.completion().join().reason());return;}
            v.event(v.role(id)+" добыл "+target.toShortString());
            if(job.equals("scaffold")){v.state.accessBlocks.remove(target.asLong());v.runtime.save();}
            if(job.equals("bench")){v.state.temporaryWorkbenches.remove(target.asLong());v.index.tables.remove(target);v.runtime.save();}
            if(v.expeditions.owns(id)&&v.state.expedition!=null&&!v.state.expedition.purpose.equals("mine"))v.state.expedition.brokenBlocks++;
            else if(v.expeditions.owns(id)&&v.state.expedition!=null&&v.state.expedition.dug&&job.equals("ore")){v.state.expedition.brokenBlocks++;v.state.expedition.oreCollected++;}
            if(job.equals("hazard")){finish();status="убрал рыхлый снег, выбирается на твёрдую землю";return;}
            mining=null;collectingUntil=now()+(job.equals("ore")?160:80);
        }
        if(collectingUntil>0){
            pickup(b);
            // Walk to stable ground, not to the instantaneous position of a falling item or a sapling
            // resting on the canopy. Those points can become unreachable before pathfinding finishes.
            BlockPos collectAt=treeBase!=null?treeBase:target;
            if(job.equals("shear")||job.equals("ore")){
                ItemEntity drop=v.level.getEntitiesOfClass(ItemEntity.class,b.entity().getBoundingBox().inflate(7),item->item.isAlive()&&(job.equals("shear")?item.getItem().is(ItemTags.WOOL):mineral(item.getItem())))
                        .stream().min(Comparator.comparingDouble(b.entity()::distanceToSqr)).orElse(null);
                BlockPos approach=drop==null?null:collectionStand(drop);
                if(approach!=null)collectAt=approach;
            }
            if(treeBase!=null&&target.getY()-treeBase.getY()>4&&!ForestWork.remaining(v,treeBase).isEmpty())collectAt=b.entity().blockPosition();
            if(now()<collectingUntil&&v.safeFeet(collectAt)&&b.entity().position().distanceToSqr(v.feetPosition(collectAt))>.36){
                lastApproach=null;navigation=b.navigator().goTo(new NavigationGoal.PositionGoal(v.feetPosition(collectAt),.35),TRAVEL);return;
            }
            if(now()<collectingUntil){b.activities().run(List.of(new BotActivity.Wait(10)));return;}
            if(job.equals("harvest")) plant(b,target);
            if(job.equals("renovate")){collectingUntil=0;job="build";navigation=null;mining=null;return;}
            if(job.equals("hoe")){collectingUntil=0;navigation=null;mining=null;return;}
            if(job.equals("wood")&&treeBase!=null){
                if(clearingLeaves){clearingLeaves=false;collectingUntil=0;return;}
                BlockPos next=ForestWork.next(v,treeBase);
                if(next!=null){
                    int carriedWood=Supplies.count(b.inventory().snapshot().slots(),s->s.is(ItemTags.LOGS)||s.is(ItemTags.PLANKS));
                    int buildingWood=Supplies.count(b.inventory().snapshot().slots(),s->s.is(ItemTags.PLANKS))+4*Supplies.count(b.inventory().snapshot().slots(),s->s.is(ItemTags.LOGS));
                    // A builder returns with a useful load instead of spending the entire day clearing
                    // the canopy before making the first tools or placing available project blocks.
                    // The marked branches remain a shared persistent forestry job.
                    boolean productionRole=!v.builder(id)&&!v.logger(id);
                    if((v.builder(id)&&buildingWood>=v.town.timberBatch())||(carriedWood>=4&&(productionRole||Supplies.find(b,s->s.is(ItemTags.AXES)&&Supplies.usable(s))<0))){
                        finish();status="несёт древесину в мастерскую и на стройку";return;
                    }
                    long day=Math.floorMod(v.level.getOverworldClockTime(),24_000);
                    int socialStart=9000+(v.roleIndex(id)%3)*1000;
                    if(nightRestWindow()||day>=socialStart&&day<socialStart+1000){finish();status="вернётся к отмеченному дереву после отдыха";return;}
                    if(!target.equals(treeBase))v.reservations.release(target.asLong(),id);target=next;navigation=null;collectingUntil=0;since=now();workWait.reset();return;
                }
                replantTree(b,treeBase);ForestWork.complete(v,treeBase);
            }
            finish();return;
        }
        boolean nativeWork=job.equals("native_work");
        double reach=workReach();
        boolean close=b.entity().getEyePosition().distanceToSqr(Vec3.atCenterOf(target))<=reach*reach;
        if(job.equals("supply"))close=b.entity().distanceToSqr(v.workers.get(supplyRecipient).body())<=9&&b.entity().hasLineOfSight(v.workers.get(supplyRecipient).body());
        if(job.equals("collect_order")){var donor=v.workers.get(haulDonor);close=donor!=null&&donor.bot()!=null&&b.entity().distanceToSqr(donor.body())<=9&&b.entity().hasLineOfSight(donor.body());}
        if(job.equals("deliver")){var recipient=v.workers.get(deliverRecipient);close=recipient!=null&&recipient.bot()!=null&&b.entity().distanceToSqr(recipient.body())<=9&&b.entity().hasLineOfSight(recipient.body());}
        if(job.equals("delivery_return"))close=b.entity().position().distanceToSqr(v.feetPosition(target))<=.64;
        if(job.equals("build")&&build!=null&&Construction.intersectsActor(v,b,build))close=false;
        if(job.equals("renovate")&&b.entity().blockPosition().below().equals(target))close=false;
        if(Set.of("wood","stone","ore","renovate").contains(job)){
            var body=b.entity().getBoundingBox();
            if(target.getY()<body.minY&&target.getY()+1>=body.minY-.15&&body.maxX>target.getX()&&body.minX<target.getX()+1
                    &&body.maxZ>target.getZ()&&body.minZ<target.getZ()+1)close=false;
        }
        if(job.equals("wood")&&close&&!lineOfSight(b,target)){
            var hit=v.level.clip(new ClipContext(b.entity().getEyePosition(),Vec3.atCenterOf(target),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,b.entity()));
            if(hit.getType()==HitResult.Type.BLOCK&&v.level.getBlockState(hit.getBlockPos()).is(BlockTags.LEAVES)){
                clearingLeaves=true;mining=b.miner().breakBlock(hit.getBlockPos());status="расчищает листву, чтобы добраться до ветвей";return;
            }
        }
        // Builders assemble paid adjacent blocks in reach; aiming at the center of an empty roof cell
        // would incorrectly reject it whenever a previously placed roof edge crosses that ray.
        if(!close||!job.equals("build")&&!job.equals("supply")&&!nativeWork&&!lineOfSight(b,target)){
            if(job.equals("pickup")||job.equals("delivery_return")){navigation=b.navigator().goTo(new NavigationGoal.PositionGoal(job.equals("pickup")?Vec3.atBottomCenterOf(target):v.feetPosition(target),job.equals("pickup")?.65:.35),TRAVEL);return;}
            BlockPos stand=v.stand(target,b.entity().position(),reach,!job.equals("build")&&!job.equals("wood")&&!nativeWork,triedApproaches);
            if(stand==null){if(job.equals("wood")&&AccessWorks.tree(this))return;fail("нет безопасного подхода");return;}
            lastApproach=stand;navigation=b.navigator().goTo(new NavigationGoal.PositionGoal(v.feetPosition(stand),job.equals("build")||job.equals("renovate")?.15:.35),TRAVEL);return;
        }
        switch(job){
            case "wood","stone","ore","harvest","seeds","renovate","hazard","sand","scaffold","bench","grade","dig_irrigation" -> {lastApproach=null;mining=b.miner().breakBlock(target);}
            case "mine" -> {
                if(!MineSupplies.inspect(this).ready()){fail("не хватает припасов для шахты");return;}
                // Another miner may have abandoned this entrance while this one was walking to it.
                if(v.state.mineEntrance==null){finish();status="вход в шахту отменён; выберет другую работу";return;}
                BlockPos entrance=BlockPos.of(v.state.mineEntrance);
                if(!Infrastructure.mineAccess(v,entrance)){
                    var failure=v.state.accessFailures.get(entrance.asLong());
                    if(failure!=null&&!failure.resolved&&failure.attempts>=3&&v.state.mineLength==0
                            &&v.state.mines.stream().noneMatch(m->m.entrance==entrance.asLong()&&m.length>0)){
                        v.state.mineEntrance=null;v.state.mineDirection=0;v.runtime.save();
                    }
                    finish();status="шахта ждёт подготовки пути снабжения или выбора другого входа";return;
                }
                var request=new ExcavationRequest(entrance,Direction.from2DDataValue(v.state.mineDirection),ExcavationMode.STAIRCASE_DOWN,
                        Math.min(32,v.state.mineLength+Math.min(8,MineSupplies.inspect(this).segments())),v.state.mineLength);
                if(!v.state.contains(request.positionAt(request.length()))){fail("шахта достигла границы деревни");return;}
                excavation=b.miner().excavate(request,new ExcavationPolicy(64,2400,4,true,true));status="прокладывает лестничную шахту";
            }
            case "fill_bucket","irrigate" -> {ManagedLand.transferWater(this,target,job.equals("fill_bucket"));finish();}
            case "workshop" -> {finish();}
            case "plant" -> {plant(b,target);finish();}
            case "hoe" -> {
                int hoe=Supplies.find(b,s->s.is(ItemTags.HOES)&&Supplies.usable(s));
                if(!v.level.getBlockState(target).isAir()&&Construction.clear(v.level.getBlockState(target))){mining=b.miner().breakBlock(target);return;}
                if(hoe>=0&&v.state.policy.allows("farm",target.below())&&watered(target.below())&&v.level.getBlockState(target).isAir()&&VillageIndex.naturalSoil(v.level.getBlockState(target.below()))){
                    b.inventory().select(hoe);v.level.setBlock(target.below(),Blocks.FARMLAND.defaultBlockState(),3);
                    b.entity().getMainHandItem().hurtAndBreak(1,b.entity(),EquipmentSlot.MAINHAND);
                    v.index.soil.add(target);v.state.plantedCrops.add(target.asLong());plant(b,target);
                }finish();
            }
            case "storage" -> {storage(b,v.steward(id));finish();delays.put("storage",now()+100);}
            case "pickup" -> {pickup(b);finish();}
            case "breed_sheep" -> {v.farm.feedPair(this);finish();}
            case "shear" -> {if(shearNearby(b))collectingUntil=now()+400;else finish();}
            case "supply" -> {
                var recipient=v.workers.get(supplyRecipient);
                if(!recipient.canExchange()){status="ждёт окончания действия путника перед передачей припасов";return;}
                int slot=Supplies.find(b,s->s.is(Items.CRAFTING_TABLE));
                if(slot<0)slot=Supplies.find(b,s->s.is(ItemTags.PLANKS)&&s.getCount()>=8);
                if(slot>=0){
                    ItemStack carried=b.inventory().snapshot().slots().get(slot);recipient.cancel();
                    if(b.inventory().transferTo(recipient.bot().inventory(),slot,carried.is(Items.CRAFTING_TABLE)?1:8)>0){
                        b.entity().swing(net.minecraft.world.InteractionHand.MAIN_HAND);recipient.body().swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                        v.event("Путнику передали припасы для замены инструмента");
                    }
                }
                finish();
            }
            case "table" -> {finish();delays.put("table",now()+200);}
            case "collect_chest","deliver_chest" -> DeliveryRuns.chestArrived(this);
            case "collect_order" -> {
                var order=deliverOrder;UUID recipient=deliverRecipient;var donor=v.workers.get(haulDonor);
                if(!donor.canExchange()){status="ждёт окончания действия владельца груза";return;}
                donor.cancel();
                Orders.refreshStock(v);
                var material=order.kind();int moved=0,personalKit=kit(material);
                int limit=Math.min(agreement.quantity,v.claimableFromBag(order,donor));
                limit=Math.min(limit,DeliveryRuns.room(v.workers.get(recipient).bot().inventory().snapshot().slots(),donor.shareable(material),material.accepts,limit));
                var slots=donor.bot().inventory().snapshot().slots();
                for(int slot=0;slot<slots.size()&&moved<limit;slot++)if(donor.shareableSlot(material,slot)&&DeliveryRuns.accepts(agreement,slots.get(slot)))
                    moved+=donor.bot().inventory().transferTo(b.inventory(),slot,limit-moved);
                if(moved>0){
                    v.state.cargoKits.computeIfAbsent(id,k->new EnumMap<>(Orders.Material.class)).put(material,personalKit);
                    carryCollectedOrder(moved,personalKit);
                    v.runtime.save();
                }else{
                    finish();order.retryAfter=now()+200;
                }
            }
            case "deliver" -> {
                var recipient=v.workers.get(deliverRecipient);
                if(!recipient.canExchange()){status="ждёт окончания действия получателя перед передачей груза";return;}
                recipient.cancel();
                Orders.refreshStock(v);
                var material=deliverOrder==null?null:deliverOrder.kind();
                int moved=0;
                if(recipient!=null&&recipient.bot()!=null&&material!=null){
                    int limit=Math.min(agreement.quantity,v.claimableFromBag(deliverOrder,this));
                    var slots=b.inventory().snapshot().slots();
                    for(int slot=0;slot<slots.size()&&moved<limit;slot++)if(shareableSlot(material,slot)&&DeliveryRuns.accepts(agreement,slots.get(slot))){
                        int amount=b.inventory().transferTo(recipient.bot().inventory(),slot,limit-moved);
                        moved+=amount;deliverOrder.delivered+=amount;
                    }
                    if(moved>0){
                        b.entity().swing(net.minecraft.world.InteractionHand.MAIN_HAND);recipient.body().swing(net.minecraft.world.InteractionHand.MAIN_HAND);
                        v.event("Материалы по заказу переданы исполнителю: "+moved);
                    }
                    Orders.refreshStock(v);
                    if(deliverOrder.open()){
                        deliverOrder.retryAfter=now()+200;
                        status="Доставка неполная: передано "+moved+", осталось "+deliverOrder.outstanding()+"; груз сохранён у носителя";
                        v.event(status);
                    }
                }
                boolean clearSite=moved>0&&v.porter(id);
                finish();
                if(clearSite){var waiting=deliveryWaitingPoint();if(waiting!=null&&b.entity().position().distanceToSqr(v.feetPosition(waiting))>1)assign("delivery_return",waiting);}
                v.runtime.save();
            }
            case "furnace" -> {if(v.level.getBlockEntity(target)instanceof FurnaceBlockEntity f)VillageWorkshop.serviceFurnace(b,v,f);finish();delays.put("furnace",now()+200);}
            case "build" -> {if(build!=null&&!Construction.place(v,b,build))v.rejected.put(target.asLong(),now()+400);finish();}
            case "home" -> {finish();delays.put("home",now()+10);}
            case "guard" -> {finish();delays.put("guard",now()+20);}
            case "native_work" -> {finish();delays.put("native_work",now()+400);}
            default -> finish();
        }
    }
    private BlockPos deliveryWaitingPoint(){
        BlockPos hub=v.chests().stream().min(Comparator.comparingDouble(c->c.getBlockPos().distSqr(body().blockPosition())))
                .map(ChestBlockEntity::getBlockPos).orElse(v.meetingPoint());
        var doors=List.copyOf(v.town.doors());BlockPos best=null;double score=Double.MAX_VALUE;
        for(var p:BlockPos.betweenClosed(hub.offset(-5,-2,-5),hub.offset(5,2,5))){
            if(!v.state.contains(p)||!v.level.hasChunkAt(p)||!v.safeFeet(p)||p.distSqr(hub)<4||p.distSqr(hub)>25
                    ||v.town.constructionCell(p)||v.town.constructionCell(p.below())||v.town.constructionCell(p.above())
                    ||doors.stream().anyMatch(d->d.distSqr(p)<9))continue;
            double distance=p.distSqr(hub)+.01*p.distSqr(body().blockPosition());
            if(distance<score){score=distance;best=p.immutable();}
        }
        return best;
    }
    private double workReach(){return job.equals("home")?2.4:job.equals("native_work")?2.8:job.equals("build")||job.equals("renovate")||job.equals("wood")?4.2:job.equals("pickup")?1.3:job.equals("supply")||job.equals("deliver")?2.5:3.0;}
    boolean reaches(BlockPos p){
        return body().getEyePosition().distanceToSqr(Vec3.atCenterOf(p))<=9&&lineOfSight(bot(),p);
    }
    private boolean lineOfSight(BotHandle b,BlockPos p){
        var hit=v.level.clip(new ClipContext(b.entity().getEyePosition(),Vec3.atCenterOf(p),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,b.entity()));
        return hit.getType()==HitResult.Type.MISS||hit.getBlockPos().equals(p);
    }
    private BlockPos collectionStand(ItemEntity item){
        BlockPos at=item.blockPosition(),best=null;double score=Double.POSITIVE_INFINITY;
        for(BlockPos p:BlockPos.betweenClosed(at.offset(-2,-2,-2),at.offset(2,0,2))){
            if(!v.safeFeet(p))continue;
            Vec3 feet=v.feetPosition(p);
            if(item.getY()<feet.y-.5||item.getY()>feet.y+2.5)continue;
            var hit=v.level.clip(new ClipContext(feet.add(0,1.62,0),item.position(),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,body()));
            if(hit.getType()!=HitResult.Type.MISS)continue;
            double candidate=feet.distanceToSqr(item.position())+.02*feet.distanceToSqr(body().position());
            if(candidate<score){score=candidate;best=p.immutable();}
        }
        return best;
    }
    private boolean thermalSafety(BotHandle b){
        if("hazard".equals(job)){work(b);return true;}
        if(b.entity().getTicksFrozen()==0)return false;
        var samples=new ArrayList<BlockPos>();
        for(BlockPos p:BlockPos.betweenClosed(b.entity().blockPosition().offset(-1,-1,-1),b.entity().blockPosition().offset(1,1,1)))samples.add(p.immutable());
        samples.sort(Comparator.<BlockPos>comparingInt(p->new AABB(p).intersects(b.entity().getBoundingBox())?0:1).thenComparingInt(p->-p.getY()));
        for(BlockPos p:samples){
            if(!v.level.getBlockState(p).is(Blocks.POWDER_SNOW))continue;
            cancel();assign("hazard",p.immutable());status="выбирается из рыхлого снега";work(b);return true;
        }
        return false;
    }
    private void restRecovery(BotHandle b){
        if(b.entity().getHealth()>=b.entity().getMaxHealth()||now()-lastRestHeal<400||b.entity().getTicksFrozen()>0)return;
        Villager resident=(Villager)b.entity();
        if(resident.blockPosition().distSqr(restHome())>9)return;
        var food=resident.getInventory();
        for(int slot=0;slot<food.getContainerSize();slot++)if(Supplies.food(food.getItem(slot))){
            int cost=food.getItem(slot).is(Items.BREAD)?1:3;if(food.getItem(slot).getCount()<cost)continue;
            food.removeItem(slot,cost);resident.heal(1);lastRestHeal=now();status="восстанавливает силы дома, ест припасы";break;
        }
    }
    BlockPos restHome(){
        homeRetries.values().removeIf(expiry->expiry<=now());
        var nativeHome=body().getBrain().getMemory(MemoryModuleType.HOME).filter(p->p.dimension().equals(v.level.dimension())&&v.level.getBlockState(p.pos()).getBlock() instanceof BedBlock).map(GlobalPos::pos);
        if(nativeHome.filter(p->v.town.shelterIntact(p)&&!homeRetries.containsKey(p.asLong())).isPresent())return nativeHome.orElseThrow();
        var intact=v.index.beds.stream().filter(p->v.level.getBlockState(p).getBlock() instanceof BedBlock&&v.town.shelterIntact(p)).toList();
        var available=intact.stream().filter(p->!homeRetries.containsKey(p.asLong())).min(Comparator.comparingDouble(p->p.distSqr(body().blockPosition())));
        if(available.isPresent())return available.orElseThrow();
        return intact.stream().min(Comparator.comparingDouble(p->p.distSqr(body().blockPosition())))
                .orElseGet(()->nativeHome.orElseGet(()->v.index.beds.stream().min(Comparator.comparingDouble(p->p.distSqr(body().blockPosition()))).orElse(v.meetingPoint())));
    }
    static boolean nightRestWindow(long clock,boolean dark){long day=Math.floorMod(clock,24000);return dark||day<1000||day>=11500;}
    static boolean crossedScheduleBoundary(long start,long end,int role,boolean profession){
        if(end<=start||end-start>6000)return false;
        for(int boundary:new int[]{9000+Math.floorMod(role,3)*1000,11500,profession?2000+Math.floorMod(role,8)*600:-1}){
            if(boundary<0)continue;
            long next=Math.floorDiv(start,24000)*24000+boundary;if(next<=start)next+=24000;
            if(next<=end)return true;
        }
        return false;
    }
    private boolean nightRestWindow(){return nightRestWindow(v.level.getOverworldClockTime(),v.level.isDarkOutside());}
    boolean atRestHome(){BlockPos home=restHome();return body().isSleeping()||body().blockPosition().distSqr(home)<=4&&lineOfSight(bot(),home);}
    private boolean recoverAtHome(BotHandle b){
        if(body().getHealth()>=body().getMaxHealth()||body().isSleeping()||mining!=null||excavation!=null||collectingUntil>0||"escape".equals(job))return false;
        if("home".equals(job)){work(b);return true;}
        if(job==null&&feed(b))return true;
        boolean fed=body().getInventory().countItem(Items.BREAD)>0||body().getInventory().countItem(Items.CARROT)>=3
                ||body().getInventory().countItem(Items.POTATO)>=3||body().getInventory().countItem(Items.BEETROOT)>=3;
        if(!fed){if(job==null&&v.chests().stream().anyMatch(c->Supplies.copy(c).stream().anyMatch(Supplies::food))&&depositOrFetch(b,v.steward(id)))return true;return false;}
        if(job!=null)cancel();
        BlockPos home=restHome();
        if(body().blockPosition().distSqr(home)>9){assign("home",home);return true;}
        status="отдыхает дома и восстанавливает здоровье";return true;
    }
    private boolean foodWork(BotHandle b){
        if(v.farmer(id)&&v.activeJobs("harvest","plant","hoe")<Math.max(1,v.workers.size()/3)){
            BlockPos ripe=nearest(v.index.crops,p->v.state.policy.allows("farm",p)&&v.level.getBlockState(p).getBlock() instanceof CropBlock c&&c.isMaxAge(v.level.getBlockState(p)));
            if(ripe!=null){assign("harvest",ripe);return job!=null;}
        }
        if(ManagedLand.work(this))return true;
        if(SettlementEconomy.food(v)>=SettlementEconomy.budget(v).food())return false;
        // Turn carried wheat into food before accepting another remote logging job.
        if(b.inventory().count(Items.WHEAT)>=3&&prepareCrafting(b,v.steward(id)))return true;
        boolean farmer=v.farmer(id);
        // Early farm duty belongs to the farmer. Other roles can still help in the
        // ordinary idle-work pass, after their available projects and supplies.
        if(!farmer)return false;
        if(v.activeJobs("harvest","plant","hoe")<Math.max(1,v.workers.size()/3)){
            BlockPos ripe=nearest(v.index.crops,p->v.state.policy.allows("farm",p)&&v.level.getBlockState(p).getBlock() instanceof CropBlock c&&c.isMaxAge(v.level.getBlockState(p)));
            if(ripe!=null){assign("harvest",ripe);return job!=null;}
        }
        if(Supplies.find(b,s->s.is(ItemTags.HOES)&&Supplies.usable(s))<0){if(prepareCrafting(b,v.steward(id)))return true;return false;}
        BlockPos field=nearest(v.index.crops,p->v.state.policy.allows("farm",p)&&v.level.getBlockState(p).getBlock() instanceof CropBlock);
        if(v.farm.needsAttention(this))return false;
        BlockPos empty=nearest(v.index.soil,p->v.state.policy.allows("farm",p)&&v.level.getBlockState(p).isAir()&&v.level.getBlockState(p.below()).is(Blocks.FARMLAND));
        if(empty!=null&&seed(b)!=null){assign("plant",empty);return job!=null;}
        if(farmExpand(b))return true;
        if(v.workers.size()>=3&&field!=null&&body().blockPosition().distSqr(field)>16){assign("farm_watch",field);return job!=null;}
        if(v.workers.size()>=3&&!v.index.crops.isEmpty()){
            status="следит за посадками, ждёт созревания урожая";return true;
        }
        return false;
    }
    void pickup(BotHandle b){
        int woolBefore=Supplies.count(b.inventory().snapshot().slots(),s->s.is(ItemTags.WOOL));
        var nativeFood=((Villager)b.entity()).getInventory();
        for(int slot=0;slot<nativeFood.getContainerSize();slot++){
            ItemStack stack=nativeFood.getItem(slot);
            if(!stack.is(Items.WHEAT)&&!stack.is(Items.WHEAT_SEEDS)&&!stack.is(Items.BEETROOT_SEEDS))continue;
            ItemStack remainder=b.inventory().insert(stack);
            int accepted=stack.getCount()-remainder.getCount();
            if(accepted>0){stack.shrink(accepted);nativeFood.setChanged();}
        }
        for(ItemEntity item:v.level.getEntitiesOfClass(ItemEntity.class,b.entity().getBoundingBox().inflate(1.4,.7,1.4),e->e.isAlive()&&!e.hasPickUpDelay())){
            ItemStack rest=b.inventory().insert(item.getItem());
            if(rest.isEmpty())item.discard();else item.setItem(rest);
        }
        var expedition=v.state.expedition;
        if(expedition!=null&&expedition.purpose.equals("wool")&&expedition.team.contains(id))expedition.woolCollected+=Math.max(0,Supplies.count(b.inventory().snapshot().slots(),s->s.is(ItemTags.WOOL))-woolBefore);
    }
    private boolean feed(BotHandle b){
        Villager e=(Villager)b.entity();
        int portions=SettlementEconomy.canGrow(v)?3:2;
        int nativeUnits=e.getInventory().countItem(Items.BREAD)*4+e.getInventory().countItem(Items.CARROT)+e.getInventory().countItem(Items.POTATO)+e.getInventory().countItem(Items.BEETROOT);
        if(nativeUnits>=portions*4)return false;
        int slot=Supplies.find(b,Supplies::food);if(slot<0)return false;
        ItemStack stack=b.inventory().snapshot().slots().get(slot);
        boolean plantingReserve=(v.farmer(id))
                &&(stack.is(Items.CARROT)||stack.is(Items.POTATO));
        int n=Math.min(Math.max(0,stack.getCount()-(plantingReserve?8:0)),stack.is(Items.BREAD)?Math.max(0,(portions*4-nativeUnits)/4):portions*4-nativeUnits);
        if(n==0)return false;
        ItemStack moving=stack.copyWithCount(n);
        // Native food inventory insertion can be partial; only debit what it accepted.
        if(!e.getInventory().canAddItem(moving))return false;
        ItemStack extracted=b.inventory().extract(slot,n);ItemStack rest=e.getInventory().addItem(extracted);
        if(!rest.isEmpty())b.inventory().insert(rest);return rest.getCount()<n;
    }
    private Item seed(BotHandle b){
        for(Item item:new Item[]{Items.WHEAT_SEEDS,Items.CARROT,Items.POTATO,Items.BEETROOT_SEEDS})if(b.inventory().count(item)>0)return item;return null;
    }
    private void plant(BotHandle b,BlockPos p){
        Item item=seed(b);if(item==null||!v.state.policy.allows("farm",p)||!v.state.contains(p)||!v.level.getBlockState(p).isAir()||!v.level.getBlockState(p.below()).is(Blocks.FARMLAND))return;
        Block block=item==Items.WHEAT_SEEDS?Blocks.WHEAT:item==Items.CARROT?Blocks.CARROTS:item==Items.POTATO?Blocks.POTATOES:Blocks.BEETROOTS;
        if(block.defaultBlockState().canSurvive(v.level,p)&&Supplies.consume(b,item,1)){
            v.level.setBlock(p,block.defaultBlockState(),3);v.index.crops.add(p);v.state.plantedCrops.add(p.asLong());
        }
    }
    private boolean watered(BlockPos soil){
        for(BlockPos p:BlockPos.betweenClosed(soil.offset(-4,0,-4),soil.offset(4,1,4)))if(v.level.getFluidState(p).is(FluidTags.WATER))return true;return false;
    }
    private boolean startMine(BotHandle b){
        if(!ready("mine")||v.state.mineLength>=32||!MineSupplies.inspect(this).ready())return false;
        if(v.state.mineEntrance==null){
            for(Direction d:Direction.Plane.HORIZONTAL){
                BlockPos column=v.state.localMineColumn(d);int x=column.getX(),z=column.getZ();
                if(!v.level.hasChunkAt(column))continue;
                BlockPos p=new BlockPos(x,v.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z),z);
                if(Infrastructure.coolingDown(v,p))continue;
                if(v.state.contains(p)&&v.state.contains(p.relative(d.getOpposite(),8).below(8))&&v.safeFeet(p)
                        &&VillageIndex.naturalSoil(v.level.getBlockState(p.below()))&&v.index.beds.stream().allMatch(bed->bed.distSqr(p)>100)){
                    var route=NetworkRoutes.plan(v,NetworkRoutes.root(v),p,Set.of());
                    if(!route.found()){Infrastructure.reject(v,p,route.failure());v.runtime.save();continue;}
                    v.state.mineEntrance=p.asLong();v.state.mineDirection=d.getOpposite().get2DDataValue();v.runtime.save();break;
                }
            }
        }
        if(v.state.mineEntrance!=null){
            if(v.state.mines.stream().anyMatch(m->m.entrance==v.state.mineEntrance&&m.direction==v.state.mineDirection&&(m.blocked||m.exhausted)))return false;
            BlockPos p=BlockPos.of(v.state.mineEntrance);
            if(v.state.policy.allows("mine",p)&&v.ready(p,id)){assign("mine",p);delays.put("mine",now()+400);return true;}
        }
        return false;
    }
    boolean farmExpand(BotHandle b){
        if(!v.state.land.fields.isEmpty())return ManagedLand.work(this);
        if(v.index.soil.size()>=Math.min(64,16+v.state.residents.size()*4)||seed(b)==null||Supplies.find(b,s->s.is(ItemTags.HOES)&&Supplies.usable(s))<0||!ready("hoe"))return false;
        BlockPos center=b.entity().blockPosition();
        for(BlockPos mutable:BlockPos.betweenClosed(center.offset(-5,-1,-5),center.offset(5,1,5))){BlockPos p=mutable.immutable();
            if(v.state.policy.allows("farm",p)&&v.ready(p,id)&&VillageIndex.surfaceFarmSite(v.level,p)&&v.level.getBlockState(p).isAir()&&VillageIndex.naturalSoil(v.level.getBlockState(p.below()))&&watered(p.below())&&v.safeFeet(p)){
                assign("hoe",p);delays.put("hoe",now()+200);return true;}}
        BlockPos bank=nearest(v.index.farmSites,p->v.state.policy.allows("farm",p)&&VillageIndex.surfaceFarmSite(v.level,p)&&Construction.clear(v.level.getBlockState(p))&&VillageIndex.naturalSoil(v.level.getBlockState(p.below()))
                &&watered(p.below())&&v.safeFeet(p)&&!v.town.protects(p.below()));
        if(bank!=null){assign("hoe",bank);delays.put("hoe",now()+100);return true;}
        for(BlockPos mutable:BlockPos.betweenClosed(center.offset(-6,-1,-6),center.offset(6,1,6))){BlockPos p=mutable.immutable();
            if(v.state.policy.allows("farm",p)&&v.ready(p,id)&&VillageIndex.surfaceFarmSite(v.level,p)&&Construction.clear(v.level.getBlockState(p))&&VillageIndex.naturalSoil(v.level.getBlockState(p.below()))
                    &&v.safeFeet(p)&&!v.town.protects(p.below())&&v.index.beds.stream().noneMatch(bed->bed.distSqr(p)<9)){
                assign("hoe",p);delays.put("hoe",now()+100);return true;
            }
        }
        delays.put("hoe",now()+400);return false;
    }
    private boolean plantTree(BotHandle b){
        if(!ready("sapling")||Supplies.find(b,s->s.is(ItemTags.SAPLINGS))<0)return false;
        for(long position:List.copyOf(v.state.reforestation)){
            BlockPos p=BlockPos.of(position);if(!v.state.policy.allows("wood",p)||!v.ready(p,id)||!Construction.clear(v.level.getBlockState(p)))continue;
            int slot=Supplies.find(b,s->s.is(ItemTags.SAPLINGS));var stack=b.inventory().snapshot().slots().get(slot);
            if(stack.getItem() instanceof BlockItem item&&item.getBlock().defaultBlockState().canSurvive(v.level,p)){
                build=new Construction.Step(p,item.getBlock().defaultBlockState(),item);assign("build",p);delays.put("sapling",now()+200);return true;
            }
        }
        BlockPos from=b.entity().blockPosition();
        for(int n=0;n<12;n++){
            int angle=(id.hashCode()+n*37)&255;double rad=angle*Math.PI/128;
            int x=v.state.x+(int)(Math.cos(rad)*(v.state.radius-8)),z=v.state.z+(int)(Math.sin(rad)*(v.state.radius-8));
            // The circumscribed radius reaches past the rectangle; a heightmap read must not load or generate a chunk.
            if(!v.level.hasChunkAt(new BlockPos(x,v.state.y,z)))continue;
            int y=v.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z);
            BlockPos p=new BlockPos(x,y,z);
            if(v.state.policy.allows("wood",p)&&v.ready(p,id)&&v.safeFeet(p)&&VillageIndex.naturalSoil(v.level.getBlockState(p.below()))&&p.distSqr(v.state.center())>100){
                int slot=Supplies.find(b,s->s.is(ItemTags.SAPLINGS));ItemStack stack=b.inventory().snapshot().slots().get(slot);
                if(stack.getItem()instanceof BlockItem item&&item.getBlock()instanceof SaplingBlock){build=new Construction.Step(p,item.getBlock().defaultBlockState(),item);assign("build",p);delays.put("sapling",now()+600);return true;}
            }
        }delays.put("sapling",now()+600);return false;
    }
    private void replantTree(BotHandle b,BlockPos p){
        int slot=Supplies.find(b,s->s.is(ItemTags.SAPLINGS));if(slot<0)return;
        ItemStack stack=b.inventory().snapshot().slots().get(slot);
        if(stack.getItem()instanceof BlockItem item&&item.getBlock()instanceof SaplingBlock&&item.getBlock().defaultBlockState().canSurvive(v.level,p))
            Construction.place(v,b,new Construction.Step(p,item.getBlock().defaultBlockState(),item));
    }
    private boolean infrastructure(BotHandle b,boolean steward){
        for(Item item:new Item[]{Items.CRAFTING_TABLE,Items.FURNACE}){
            if(b.inventory().count(item)==0)continue;
            if(item==Items.CRAFTING_TABLE){
                if(!needsWorkbench)continue;
                BlockPos shared=nearest(v.index.tables,p->v.level.getBlockState(p).is(Blocks.CRAFTING_TABLE)&&p.distSqr(b.entity().blockPosition())<32*32);
                if(shared!=null){if(!VillageWorkshop.nearTable(b,v,shared)&&ready("table")){assign("table",shared);return job!=null;}continue;}
            }else if(!steward)continue;
            boolean needed=item==Items.CRAFTING_TABLE?!v.index.tables.stream().anyMatch(p->VillageWorkshop.nearTable(b,v,p)):item==Items.CHEST?v.needsChest():v.state.furnaces.isEmpty();
            if(!needed)continue;
            for(int r=2;r<=6;r++)for(Direction d:Direction.Plane.HORIZONTAL){
                BlockPos p=b.entity().blockPosition().relative(d,r);
                for(int dy:new int[]{0,-1,1,-2,2}){BlockPos at=p.above(dy);
                    if(v.ready(at,id)&&v.safeFeet(at)&&(item!=Items.FURNACE||v.state.contains(at))&&!v.town.protects(at)&&!v.town.constructionCell(at.below())&&v.index.beds.stream().noneMatch(bed->bed.distSqr(at)<4)){
                        build=new Construction.Step(at,((BlockItem)item).getBlock().defaultBlockState(),item);assign("build",at);return true;}}
            }
        }
        if(!steward)return false;
        Construction.Step step=Construction.next(v);
        if(step!=null&&b.inventory().count(step.item())==0){
            if(step.item()==Items.OAK_DOOR&&VillageWorkshop.craft(b,v,new String[]{"PP","PP","PP"},VillageWorkshop.PLANK,s->s.is(ItemTags.WOODEN_DOORS))){status="изготавливает дверь";return true;}
            if(step.item()==Items.OAK_STAIRS&&VillageWorkshop.craft(b,v,new String[]{"P  ","PP ","PPP"},VillageWorkshop.PLANK,s->s.is(ItemTags.WOODEN_STAIRS))){status="изготавливает ступени";return true;}
        }
        return false;
    }
    private boolean recoverWorkbench(BotHandle b){
        BlockPos bench=nearest(v.state.temporaryWorkbenches.stream().map(BlockPos::of).toList(),p->
                v.level.getBlockState(p).is(Blocks.CRAFTING_TABLE)&&!v.town.protects(p)&&p.distSqr(b.entity().blockPosition())<36
                &&v.workers.values().stream().noneMatch(other->other!=this&&(other.needsWorkbench&&other.body().blockPosition().distSqr(p)<144
                ||"table".equals(other.job)&&p.equals(other.target))));
        if(bench==null)return false;
        assign("bench",bench);status="собирает временный верстак для следующей работы";return job!=null;
    }
    boolean depositOrFetch(BotHandle b,boolean steward){
        if(!ready("storage"))return false;
        var chests=v.chests();if(chests.isEmpty())return false;
        var bag=b.inventory().snapshot().slots();
        var extras=bag.stream().filter(s->depositAmount(b,s,steward)>0).toList();
        ChestBlockEntity chest=chests.stream().filter(c->{var contents=Supplies.copy(c);
            return contents.stream().anyMatch(s->unreservedWithdrawal(b,c,s,steward)>0)
                    ||v.state.orders.stream().anyMatch(o->o.open()&&!o.returnCargo()&&id.toString().equals(o.recipient)
                        &&o.kind()!=null&&v.claimableFromChests(o)>0&&Supplies.count(contents,o.kind().accepts)>0)
                    ||extras.stream().anyMatch(s->ManagedLand.acceptsDeposit(v,c.getBlockPos().asLong(),s)&&Supplies.insert(Supplies.copy(contents),s.copyWithCount(1)));
        }).min(Comparator.comparingDouble(c->{double dy=c.getBlockPos().getY()-b.entity().getY();
            return b.entity().position().distanceToSqr(Vec3.atCenterOf(c.getBlockPos()))+15*dy*dy
                    -(extras.stream().anyMatch(stack->ManagedLand.acceptsDeposit(v,c.getBlockPos().asLong(),stack))
                        &&(ManagedLand.seedChest(v,c.getBlockPos().asLong())||!ProductionBuildings.storagePurpose(v,c.getBlockPos().asLong()).equals("shared"))?256:0);})).orElse(null);
        if(chest==null)return false;
        if(b.entity().position().distanceToSqr(Vec3.atCenterOf(chest.getBlockPos()))>12||!lineOfSight(b,chest.getBlockPos())){assign("storage",chest.getBlockPos());return true;}
        boolean changed=storage(b,steward);delays.put("storage",now()+100);return changed;
    }
    private int reserve(ItemStack s,boolean steward){
        int base=baseReserve(s,steward);if(base==Integer.MAX_VALUE)return base;
        // Inputs already allocated to this resident's projects are not another
        // worker's surplus, even after their immediate delivery is complete.
        int ordered=v.state.orders.stream().filter(o->!o.returnCargo()&&id.toString().equals(o.recipient)
                &&o.kind()!=null&&o.kind().accepts.test(s)).mapToInt(o->o.quantity).sum();
        return Math.max(base,ordered);
    }
    private int baseReserve(ItemStack s,boolean steward){
        if(s.has(DataComponents.CUSTOM_NAME))return Integer.MAX_VALUE;
        for(var e:v.state.cargoKits.getOrDefault(id,Map.of()).entrySet())if(e.getKey().accepts.test(s))return e.getValue();
        if(Supplies.tool(s)||s.has(DataComponents.EQUIPPABLE))return 0;
        if(v.expeditions.owns(id)&&"unload".equals(v.state.expedition.phase)&&!Supplies.food(s)){
            if(s.is(ItemTags.PLANKS))return 8;
            if(s.is(Items.STICK))return 4;
            return 0;
        }
        if(v.builder(id)&&v.town.needs(this,s))return s.is(ItemTags.PLANKS)?(v.hasRole(id,ResidentRoles.Role.BUILDER)?BUILDING_TIMBER:16):16;
        int ingredient=TownCrafting.ingredientReserve(this,s);if(ingredient>0)return ingredient;
        if(s.is(ItemTags.BEDS))return steward?2:0;
        if(s.is(ItemTags.WOODEN_DOORS)||s.is(ItemTags.WOODEN_STAIRS))return steward?1:0;
        if(s.is(Items.TORCH))return steward?8:0;
        if(s.is(Items.CHEST))return steward&&v.needsChest()?1:0;
        if(s.is(Items.CRAFTING_TABLE))return 1;
        if(s.is(Items.FURNACE))return steward&&v.state.furnaces.isEmpty()?1:0;
        if(v.expeditions.preparingMine(id)&&s.is(ItemTags.PLANKS))return 16;
        if(v.expeditions.preparingMine(id)&&s.is(Items.COBBLESTONE))return 6;
        if(s.is(ItemTags.LOGS_THAT_BURN))return v.hasRole(id,ResidentRoles.Role.BUILDER)?BUILDING_TIMBER/4:needsBasicTools()?4:1;
        if(s.is(ItemTags.PLANKS))return v.hasRole(id,ResidentRoles.Role.BUILDER)?BUILDING_TIMBER:needsBasicTools()||steward?16:8;
        if(s.is(Items.STICK))return 8;
        if(s.is(Items.COBBLESTONE))return v.miner(id)?8:steward?8:((!v.builder(id)||v.workers.size()<3)&&bNeedsStonePick()?3:0)
                +((v.guard(id))&&!VillageWorkshop.hasDefensiveSword(bot())?2:0);
        if(s.is(Items.IRON_INGOT)){
            var need=IronSupply.next(v);if(need!=null)return need.recipient()==this?need.ingots():0;
            if(steward&&Supplies.find(bot(),st->st.is(Items.SHEARS)&&Supplies.usable(st))<0)return 2;
            if((v.miner(id))&&!VillageWorkshop.hasPickFor(bot(),Blocks.DIAMOND_ORE.defaultBlockState()))return 3;
            return v.guard(id)?8:0;
        }
        if(s.is(Items.SAND))return steward?16:0;
        if(s.is(Items.GLASS))return v.builder(id)?16:0;
        if(s.is(Items.STONE))return v.builder(id)?32:0;
        if(s.is(Items.BUCKET)||s.is(Items.WATER_BUCKET))return v.farmer(id)?1:0;
        if(s.is(Items.WHEAT_SEEDS)||s.is(Items.BEETROOT_SEEDS))return v.farmer(id)?8:0;
        if(s.is(Items.WHEAT)&&v.farm.wheatReserve(this)>0)return v.farm.wheatReserve(this);
        if(s.is(ItemTags.WOOL)&&this==v.farm.caretaker()&&v.state.orders.stream().anyMatch(o->o.purpose.equals("bed_wool")&&!o.recipient.equals(id.toString())))return 0;
        if(s.is(ItemTags.WOOL)&&v.builder(id)&&v.town.wantedPlans(this).stream().anyMatch(p->p.block().getBlock() instanceof BedBlock))return 6;
        if(v.farmer(id)&&(s.is(Items.CARROT)||s.is(Items.POTATO)))return 8;
        if(Supplies.food(s))return body().hasExcessFood()?((v.farmer(id))&&(s.is(Items.CARROT)||s.is(Items.POTATO))?4:0):s.is(Items.BREAD)?3:12;
        if(s.is(ItemTags.SAPLINGS))return 2;
        if(steward&&(s.is(Items.WHEAT)||s.is(ItemTags.WOOL)||s.is(Items.RAW_IRON)||s.is(Items.COAL)||s.is(Items.CHARCOAL)))return 32;
        if(s.getItem() instanceof BlockItem && !s.is(ItemTags.LOGS) && !s.is(ItemTags.PLANKS))return 0;
        return 0;
    }
    private int depositAmount(BotHandle b,ItemStack s,boolean steward){
        if(s.isEmpty())return 0;
        if(!PersonalKits.family(s).isEmpty()){
            var bag=b.inventory().snapshot().slots();var protectedSlots=protectedSlots();int total=0;
            for(int slot=0;slot<bag.size();slot++)if(!protectedSlots.contains(slot)&&ItemStack.isSameItemSameComponents(bag.get(slot),s))total+=bag.get(slot).getCount();
            return total;
        }
        return Math.max(0,Supplies.count(b.inventory().snapshot().slots(),st->st.is(s.getItem()))-reserve(s,steward));
    }
    private boolean bNeedsStonePick(){return !VillageWorkshop.hasPickFor(bot(),Blocks.IRON_ORE.defaultBlockState())
            ||(v.miner(id))&&VillageWorkshop.needsIronSurvey(v)&&!VillageWorkshop.hasSurveyPick(bot())&&v.count(s->s.is(Items.COBBLESTONE))>=6;}
    private boolean needsBasicTools(){return Supplies.find(bot(),s->s.is(ItemTags.PICKAXES)&&Supplies.usable(s))<0
            ||Supplies.find(bot(),s->s.is(ItemTags.AXES)&&Supplies.usable(s))<0;}
    boolean hasSurplus(){var b=bot();return b.inventory().snapshot().slots().stream().anyMatch(s->depositAmount(b,s,v.steward(id))>0);}
    boolean shouldUnloadBeforeWork(){
        var b=bot();var bag=b.inventory().snapshot().slots();var counted=new HashSet<Item>();int surplus=0,occupied=0;
        for(var stack:bag)if(!stack.isEmpty()){
            occupied++;if(counted.add(stack.getItem()))surplus+=depositAmount(b,stack,v.steward(id));
        }
        return surplus>=16||surplus>0&&occupied>=30;
    }
    static boolean mineral(ItemStack s){return s.is(Items.COBBLESTONE)||s.is(Items.COBBLED_DEEPSLATE)||s.is(Items.COAL)||s.is(Items.RAW_IRON)||s.is(Items.RAW_GOLD)||s.is(Items.RAW_COPPER)||s.is(Items.DIAMOND)||s.is(Items.EMERALD)||s.is(Items.REDSTONE)||s.is(Items.LAPIS_LAZULI);}
    private int shortage(BotHandle b,ItemStack s,boolean steward){
        if(!PersonalKits.family(s).isEmpty()){
            String family=PersonalKits.family(s);
            int wanted=PersonalKits.tools(v.primaryRole(id),family,v.farm.caretaker()==this);
            return Math.max(0,wanted-(int)b.inventory().snapshot().slots().stream().filter(t->Supplies.usable(t)&&PersonalKits.family(t).equals(family)).count());
        }
        int wanted=reserve(s,steward);
        if(wanted==Integer.MAX_VALUE||s.isEmpty())return 0;
        if(s.is(ItemTags.PLANKS))return Math.max(0,wanted-Supplies.count(b.inventory().snapshot().slots(),st->st.is(ItemTags.PLANKS)));
        if(s.is(ItemTags.LOGS))return Math.max(0,wanted-Supplies.count(b.inventory().snapshot().slots(),st->st.is(ItemTags.LOGS)));
        if(s.is(ItemTags.LOGS_THAT_BURN)&&Supplies.count(b.inventory().snapshot().slots(),st->st.is(ItemTags.PLANKS))>=16
                &&!(steward&&!v.state.furnaces.isEmpty()&&v.count(st->st.is(Items.COAL)||st.is(Items.CHARCOAL))<8))return 0;
        return Math.max(0,wanted-b.inventory().count(s.getItem()));
    }
    private boolean needed(BotHandle b,ItemStack s,boolean steward){return shortage(b,s,steward)>0;}
    private int unreservedWithdrawal(BotHandle b,ChestBlockEntity chest,ItemStack stack,boolean steward){
        int need=ManagedLand.withdrawLimit(this,chest.getBlockPos().asLong(),stack,shortage(b,stack,steward));
        for(var material:Orders.Material.values())if(material.accepts.test(stack))need=Math.min(need,v.ledger(material).free());
        return need;
    }
    private boolean storage(BotHandle b,boolean steward){
        boolean changed=false;
        for(ChestBlockEntity chest:v.chests()){
            if(b.entity().position().distanceToSqr(Vec3.atCenterOf(chest.getBlockPos()))>16||!lineOfSight(b,chest.getBlockPos()))continue;
            boolean used=false;
            for(ItemStack stack:b.inventory().snapshot().slots()){
                int extra=ManagedLand.depositLimit(v,chest.getBlockPos().asLong(),stack,depositAmount(b,stack,steward));
                if(extra>0&&ManagedLand.acceptsDeposit(v,chest.getBlockPos().asLong(),stack)){int amount=Supplies.transfer(b,chest,true,s->!PersonalKits.family(stack).isEmpty()?ItemStack.isSameItemSameComponents(s,stack):s.is(stack.getItem()),extra);used|=amount>0;if(amount>0){v.state.deposits.merge(id,(long)amount,Long::sum);if(mineral(stack))v.state.mineralDeposits.merge(id,(long)amount,Long::sum);}}
            }
            for(var order:v.state.orders)if(order.open()&&!order.returnCargo()&&id.toString().equals(order.recipient)&&order.kind()!=null){
                int moved=Supplies.transfer(b,chest,false,order.kind().accepts,v.claimableFromChests(order));
                order.delivered+=moved;used|=moved>0;
            }
            for(ItemStack stack:Supplies.copy(chest)){
                int need=unreservedWithdrawal(b,chest,stack,steward);
                if(need>0)used|=Supplies.transfer(b,chest,false,s->s.is(stack.getItem()),Math.min(need,stack.getCount()))>0;
            }
            if(used)v.presentation.chest(chest);changed|=used;
        }return changed;
    }
    private boolean shear(BotHandle b){
        int slot=Supplies.find(b,s->s.is(Items.SHEARS)&&Supplies.usable(s));if(slot<0||!v.farm.wantsWool()||!ready("shear"))return false;
        if(shearNearby(b))return true;
        Sheep sheep=v.level.getEntitiesOfClass(Sheep.class,b.entity().getBoundingBox().inflate(v.state.radius),s->s.readyForShearing()&&!s.hasCustomName()&&!s.isLeashed()&&(v.farm.state().flock.isEmpty()||v.farm.owns(s))&&v.ready(s.blockPosition(),id))
                .stream().min(Comparator.comparingDouble(b.entity()::distanceToSqr)).orElse(null);
        if(sheep!=null){assign("shear",sheep.blockPosition());delays.put("shear",now()+200);return true;}
        delays.put("shear",now()+400);return false;
    }
    private boolean shearNearby(BotHandle b){
        if(!v.farm.wantsWool())return false;
        int slot=Supplies.find(b,s->s.is(Items.SHEARS)&&Supplies.usable(s));if(slot<0)return false;
        for(Sheep sheep:v.level.getEntitiesOfClass(Sheep.class,b.entity().getBoundingBox().inflate(3),s->s.readyForShearing()&&!s.hasCustomName()&&!s.isLeashed()&&(v.farm.state().flock.isEmpty()||v.farm.owns(s)))){
            if(!v.canWork(sheep.blockPosition())||!b.entity().hasLineOfSight(sheep))continue;
            b.inventory().select(slot);sheep.shear(v.level,net.minecraft.sounds.SoundSource.NEUTRAL,b.entity().getMainHandItem());
            if("shear".equals(job))target=sheep.blockPosition();
            b.entity().getMainHandItem().hurtAndBreak(1,b.entity(),EquipmentSlot.MAINHAND);status="стрижёт овец для новых кроватей";
            if(v.expeditions.owns(id)&&v.state.expedition!=null)v.state.expedition.harvested++;return true;
        }return false;
    }
    private boolean salvage(BotHandle b){
        if(!ready("salvage"))return false;
        delays.put("salvage",now()+200);
        ItemEntity item=v.level.getEntitiesOfClass(ItemEntity.class,b.entity().getBoundingBox().inflate(v.state.radius),e->e.isAlive()&&!e.hasPickUpDelay()&&v.ready(e.blockPosition(),id)
                &&(e.getItem().is(ItemTags.SAPLINGS)||e.getItem().is(ItemTags.WOOL)||e.getItem().is(ItemTags.LOGS)||Supplies.food(e.getItem())||e.getItem().is(Items.WHEAT)||e.getItem().is(Items.WHEAT_SEEDS)))
                .stream().min(Comparator.comparingDouble(b.entity()::distanceToSqr)).orElse(null);
        if(item==null)return false;
        assign("pickup",item.blockPosition());return true;
    }
    private boolean recoverScaffolding(BotHandle b){
        if(!ready("scaffold"))return false;
        delays.put("scaffold",now()+200);
        BlockPos p=nearest(v.state.accessBlocks.stream().map(BlockPos::of).toList(),q->
                v.level.getBlockState(q).is(BlockTags.PLANKS)&&!v.town.protects(q)
                &&v.state.fellingTrees.stream().map(BlockPos::of).noneMatch(base->Math.abs(base.getX()-q.getX())<24&&Math.abs(base.getZ()-q.getZ())<24)
                &&v.state.accessBlocks.stream().map(BlockPos::of).noneMatch(other->other.getY()>q.getY()&&Math.abs(other.getX()-q.getX())<16&&Math.abs(other.getZ()-q.getZ())<16)
                &&v.level.getEntitiesOfClass(Villager.class,new AABB(q).move(0,1,0).inflate(.05),Entity::isAlive).isEmpty());
        if(p==null)return false;assign("scaffold",p);status="разбирает подмости, возвращает материалы";return true;
    }
    static final double SHELTER_CORRIDOR=4.0;
    static boolean approachExposed(Vec3 from,Vec3 to,Vec3 enemy){
        double dx=to.x-from.x,dz=to.z-from.z,lengthSquared=dx*dx+dz*dz;
        if(lengthSquared<=0)return false;
        double along=(enemy.x-from.x)*dx+(enemy.z-from.z)*dz;
        if(along<=0||along>=lengthSquared)return false;
        double perpendicular=(enemy.x-from.x)*dz-(enemy.z-from.z)*dx;
        return perpendicular*perpendicular<=SHELTER_CORRIDOR*SHELTER_CORRIDOR*lengthSquared;
    }
    static boolean standExposed(Vec3 from,BlockPos stand,Vec3 enemy){
        Vec3 point=Vec3.atBottomCenterOf(stand);
        return point.distanceToSqr(enemy)<from.distanceToSqr(enemy)||approachExposed(from,point,enemy);
    }
    static List<BlockPos> orderedShelters(Vec3 from,Vec3 enemy,List<BlockPos> beds,Predicate<BlockPos> knownIntact){
        Comparator<BlockPos> order=Comparator.comparingDouble(bed->{
            double dx=bed.getX()-from.x,dy=bed.getY()-from.y,dz=bed.getZ()-from.z;
            return dx*dx+dz*dz+15*dy*dy;
        });
        return beds.stream().filter(bed->{
            double dx=bed.getX()-enemy.x,dy=bed.getY()-enemy.y,dz=bed.getZ()-enemy.z;
            return dx*dx+dy*dy+dz*dz>16;
        }).filter(knownIntact).sorted(order).toList();
    }
    record Retreat(BlockPos bed,BlockPos stand){ }
    static Retreat shelteredStand(Vec3 from,Vec3 enemy,List<BlockPos> beds,Predicate<BlockPos> knownIntact,Function<BlockPos,BlockPos> standFor){
        for(BlockPos bed:orderedShelters(from,enemy,beds,knownIntact)){
            BlockPos stand=standFor.apply(bed);
            if(stand==null||Vec3.atBottomCenterOf(stand).distanceToSqr(from)<9||standExposed(from,stand,enemy))continue;
            return new Retreat(bed,stand);
        }
        return null;
    }
    private BlockPos safeStandNear(Vec3 from,BlockPos desired,Vec3 enemy,double reach,boolean sight){
        BlockPos stand=v.stand(desired,from,reach,sight);
        return stand!=null&&!standExposed(from,stand,enemy)?stand:null;
    }
    private boolean danger(BotHandle b){
        var e=b.entity();
        Monster enemy=v.level.getEntitiesOfClass(Monster.class,e.getBoundingBox().inflate(12),m->m.isAlive()&&threat(m)&&v.canWork(m.blockPosition())&&e.hasLineOfSight(m))
                .stream().min(Comparator.comparingDouble(e::distanceToSqr)).orElse(null);
        if(enemy==null){
            boolean shelterValid=shelter==null||v.town.knownShelter(shelter);
            if(fleeing&&navigation!=null&&!navigation.completion().isDone()&&now()-since<400&&shelterValid)return true;
            if(fleeing&&shelter!=null&&!shelterValid){cancel();return false;}
            fleeing=false;return false;
        }
        Vec3 enemyAt=enemy.position();
        boolean standThreatened=target!=null&&standExposed(e.position(),target,enemyAt);
        if(combat!=null&&!combat.completion().isDone())return true;
        boolean shelterValid=fleeing&&shelter!=null&&v.town.knownShelter(shelter);
        if(fleeing&&navigation!=null&&!navigation.completion().isDone()&&now()-since<400&&e.distanceToSqr(enemy)>9
                &&(shelter==null||shelterValid)&&!standThreatened)return true;
        if(shelterValid&&navigation==null&&target!=null&&e.blockPosition().distSqr(target)<9&&!standThreatened)return true;
        boolean trapped=fleeing&&navigation!=null&&navigation.completion().isDone()&&navigation.status()!=NavigationStatus.SUCCESS;
        if(!(enemy instanceof Creeper))v.raiseAlarm(this,enemy);
        cancel();
        boolean equipped=VillageWorkshop.hasDefensiveSword(b);
        if(!(enemy instanceof Creeper)&&(e.getHealth()>=16&&equipped||trapped)&&Supplies.find(b,s->s.is(ItemTags.SWORDS)&&Supplies.usable(s))>=0){
            defend(enemy);return true;
        }
        Retreat retreat=shelteredStand(e.position(),enemyAt,v.index.beds,bed->v.town.knownShelter(bed),bed->v.stand(bed,e.position(),2.5,true));
        BlockPos refuge=retreat==null?null:retreat.bed();
        BlockPos stand=retreat==null?null:retreat.stand();
        if(stand==null){
            Vec3 offset=e.position().subtract(enemyAt);
            double length=Math.hypot(offset.x,offset.z);
            Vec3 direction=length<=1e-4?new Vec3(1,0,0):new Vec3(offset.x/length,0,offset.z/length);
            for(double distance:new double[]{8,12,16}){
                stand=safeStandNear(e.position(),BlockPos.containing(e.position().add(direction.scale(distance))),enemyAt,3.0,true);
                if(stand!=null)break;
            }
        }
        if(stand==null)stand=safeStandNear(e.position(),v.state.center(),enemyAt,3.0,true);
        if(stand!=null){navigation=b.navigator().goTo(new NavigationGoal.PositionGoal(Vec3.atBottomCenterOf(stand),.7),TRAVEL);job="home";target=stand;shelter=refuge;since=now();fleeing=true;}
        status="укрывается от угрозы";return true;
    }
    private boolean threat(Monster m){
        return m.getType()==EntityTypes.ZOMBIE||m.getType()==EntityTypes.ZOMBIE_VILLAGER||m.getType()==EntityTypes.HUSK||m.getType()==EntityTypes.DROWNED
                ||m.getTarget() instanceof Villager target&&v.state.residents.contains(target.getUUID())
                ||m instanceof Creeper creeper&&creeper.getSwellDir()>0;
    }
    void secureDoors(BotHandle b){
        boolean emergencyRetreat=fleeing&&"home".equals(job);
        if(!(nightRestWindow()||emergencyRetreat)||combat!=null)return;
        boolean walking=navigation!=null&&!navigation.completion().isDone();
        // Native Brain can finish the last few steps to a bed after our navigation releases.
        // Even five ticks can miss the short interval between crossing and native sleep.
        // Visit only cached house doors, so checking each awake tick stays bounded and cheap.
        int interval=b.entity().isSleeping()?20:1;
        if(now()%interval!=Math.floorMod(id.hashCode(),interval))return;
        for(BlockPos p:v.town.doors()){
            if(b.entity().getEyePosition().distanceToSqr(Vec3.atCenterOf(p))>9)continue;
            var state=v.level.getBlockState(p);
            if(!(state.getBlock() instanceof DoorBlock door)||!state.is(BlockTags.WOODEN_DOORS)||!v.town.protects(p)
                    ||state.getValue(DoorBlock.HALF)!=net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER||!state.getValue(DoorBlock.OPEN)
                    ||b.entity().getEyePosition().distanceToSqr(Vec3.atCenterOf(p))>9)continue;
            // Close the door after crossing it, while it is still within actual hand reach.
            // Waiting for navigation to finish leaves a deep room's door open all night.
            if(walking&&(target==null||Vec3.atCenterOf(p).subtract(b.entity().position()).dot(Vec3.atCenterOf(target).subtract(b.entity().position()))>=0))continue;
            if(!walking&&b.entity().getDeltaMovement().horizontalDistanceSqr()>.0001
                    &&Vec3.atCenterOf(p).subtract(b.entity().position()).dot(b.entity().getDeltaMovement())>=0)continue;
            var doorway=new AABB(p).expandTowards(0,1,0);
            if(!v.level.getEntitiesOfClass(LivingEntity.class,doorway.inflate(.1),LivingEntity::isAlive).isEmpty())continue;
            door.setOpen(b.entity(),v.level,state,p,false);b.entity().swing(net.minecraft.world.InteractionHand.MAIN_HAND);
            var upper=v.level.getBlockState(p.above());
            if(upper.getBlock() instanceof DoorBlock&&upper.hasProperty(DoorBlock.OPEN)&&upper.getValue(DoorBlock.OPEN))v.level.setBlock(p.above(),upper.setValue(DoorBlock.OPEN,false),3);
        }
    }
    void defend(Monster enemy){
        cancel();combat=bot().combat().fight(enemy,new CombatPolicy(CombatSkill.EXPERT,16,3,24,false,true,false).swordOnly());since=now();status="защищает деревню";
    }
    void finish(){DeliveryRuns.release(this);if("herd".equals(job)){v.farm.state().leading=null;v.farm.gate(false);}if(deliverOrder!=null&&id.toString().equals(deliverOrder.carrier))Orders.release(deliverOrder);v.reservations.release(id);treeBase=null;job=null;target=null;shelter=null;navigation=null;mining=null;excavation=null;build=null;townPlan=null;collectingUntil=0;clearingLeaves=false;fleeing=false;supplyRecipient=null;deliverRecipient=null;deliverOrder=null;haulDonor=null;deliveryKit=-1;}
    void cancel(){workWait.reset();BotHandle b=bot();if(b!=null&&b.state()==BotState.ACTIVE)b.stopAll();v.reservations.release(id);combat=null;finish();}
    void fail(String reason){
        BlockPos destination=lastApproach!=null?lastApproach:target;boolean recovery=!"escape".equals(job)&&(reason.startsWith("маршрут")||reason.startsWith("цель недоступна")||reason.startsWith("нет безопасного подхода"));
        boolean nightHome="home".equals(job)&&nightRestWindow();
        if(nightHome){
            if(target!=null&&v.level.getBlockState(target).getBlock() instanceof BedBlock)homeRetries.put(target.asLong(),now()+200);
            // Surface shelter failure means try another doorway/room, not dig away from safety.
            int surface=v.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,body().blockPosition().getX(),body().blockPosition().getZ());
            if(surface-body().getY()<=6||v.index.beds.stream().anyMatch(p->p.distSqr(body().blockPosition())<64))recovery=false;
        }
        if(job!=null)AutonomousVillagesMod.LOG.info("WORK_INTERRUPTED village={} worker={} job={} target={} cause={}",v.state.name,id,job,target,reason);
        lastFailure=reason;
        if(deliverOrder!=null)deliverOrder.waiting=reason+"; физический груз сохранён";
        if(recovery)Infrastructure.stuck(v,destination);
        if(target!=null&&exclusiveJob(job))v.rejected.put(target.asLong(),now()+600);
        if(treeBase!=null)v.rejected.put(treeBase.asLong(),now()+1200);
        if(job!=null)delays.put(job,now()+(job.equals("storage")?400:100));
        cancel();if(nightHome)delays.put("home",now()+10);cooldown=now()+10;status=reason;
        if(recovery)TerrainRecovery.start(this,destination);
    }
    static String activity(String kind){
        if(kind==null)return "none";
        return switch(kind){case "wood"->"wood";case "harvest","plant","hoe","seeds","dig_irrigation","fill_bucket","irrigate"->"farm";case "build","renovate","grade"->"build";case "stone","ore","sand","mine"->"mine";default->"none";};
    }
    static String miningActivity(String kind){var a=activity(kind);return a.equals("none")?"mine":a;}
    static boolean exclusiveJob(String kind){return kind!=null&&!Set.of("home","native_work","storage","table","air","escape","farm_watch","supply","pickup","deliver","collect_order","collect_chest","deliver_chest","delivery_return","guard").contains(kind);}
}
