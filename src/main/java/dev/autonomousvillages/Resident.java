package dev.autonomousvillages;

import dev.navigationai.api.*;
import java.util.*;
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
    BlockPos target, treeBase;
    NavigationTask navigation;
    MiningTask mining;
    ExcavationTask excavation;
    CombatTask combat;
    UUID supplyRecipient;
    Construction.Step build;
    TownBuilding.Plan townPlan;
    long since, cooldown, collectingUntil;
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
    void tick() {
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
        if(v.expeditions.work(this))return;
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
        if (job!=null) { work(b);return; }
        if (now()<cooldown) return;
        cooldown=now()+20;
        pickup(b);
        if (feed(b)) {status="раздаёт еду";return;}
        boolean steward=v.steward(id);
        if(v.expeditions.preparingSurveyPick(id)&&(prepareCrafting(b,steward)||depositOrFetch(b,steward)))return;
        if((Supplies.find(b,s->s.is(ItemTags.PICKAXES)&&Supplies.usable(s))<0
                ||Supplies.find(b,s->s.is(ItemTags.AXES)&&Supplies.usable(s))<0)&&prepareCrafting(b,steward))return;
        // Bring excess cargo to the shared store before accepting another project. Otherwise a
        // repeatable renovation can keep the equipped shearer ineligible for every wool outing.
        // depositAmount retains the tools, food and materials needed by the current building plan.
        if(shouldUnloadBeforeWork()&&depositOrFetch(b,steward))return;
        if(foodWork(b))return;
        if(v.builder(id)&&startBuilding(b,true))return;
        if(steward&&production(b))return;
        if(infrastructure(b,steward))return;
        if(!v.state.reforestation.isEmpty()&&plantTree(b))return;
        if(buildingTimber(b))return;
        if(Supplies.find(b,s->s.is(ItemTags.AXES)&&Supplies.usable(s))>=0&&startBuilding(b,true))return;
        if(prepareCrafting(b,steward))return;
        if(startBuilding(b))return;
        if (depositOrFetch(b,steward)) return;
        if(!needsWorkbench&&recoverWorkbench(b))return;
        // Wheat needs a workbench before it becomes food, and supplies need a depot before they can
        // be shared. Do not let endlessly renewed harvest jobs starve this bootstrap dependency.
        if(v.chests().isEmpty()&&Supplies.count(b.inventory().snapshot().slots(),s->s.is(ItemTags.LOGS)||s.is(ItemTags.PLANKS))<(steward?32:12)){
            BlockPos log=nearest(v.index.trees,this::treeCandidate);
            if(log!=null){assign("wood",log);return;}
        }
        BlockPos crop=nearest(v.index.crops,p->v.level.getBlockState(p).getBlock() instanceof CropBlock c&&c.isMaxAge(v.level.getBlockState(p)));
        if (crop!=null&&v.count(Supplies::food)<Math.max(64,v.state.residents.size()*24)&&v.activeJobs("harvest","plant","hoe")<Math.max(1,v.workers.size()/3)) {assign("harvest",crop);return;}
        BlockPos soil=nearest(v.index.soil,p->v.level.getBlockState(p).isAir()&&v.level.getBlockState(p.below()).is(Blocks.FARMLAND));
        if (soil!=null&&seed(b)!=null) {assign("plant",soil);return;}
        if(v.roleIndex(id)%6==2||v.workers.size()<3){
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
        if(v.roleIndex(id)%6==4&&v.state.expedition==null&&miningWork(b))return;
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
    private boolean miningWork(BotHandle b){
        if (Supplies.find(b,s->s.is(ItemTags.PICKAXES)&&Supplies.usable(s))>=0) {
            if(v.count(s->s.is(Items.COBBLESTONE))<Math.max(32,v.workers.size()*12)) {
                BlockPos stone=nearest(v.index.stone,p->v.level.getBlockState(p).is(Blocks.STONE)&&VillageIndex.exposed(v.level,p));
                if(stone!=null){assign("stone",stone);return job!=null;}
            }
            if(v.count(s->s.is(Items.RAW_IRON)||s.is(Items.IRON_INGOT))<Math.max(8,v.workers.size()*3)||v.count(s->s.is(Items.COAL))<16) {
                BlockPos ore=nearest(v.index.ore,p->VillageIndex.exposed(v.level,p)&&oreHarvestable(b,p));
                if(ore!=null){assign("ore",ore);return job!=null;}
            }
            if((v.count(s->s.is(Items.COBBLESTONE))<Math.max(32,v.workers.size()*12)
                    ||v.count(s->s.is(Items.IRON_INGOT)||s.is(Items.RAW_IRON))<12)&&startMine(b))return true;
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
        return VillageWorkshop.hasPickFor(b,v.level.getBlockState(p));
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
        if(v.town.protects(base))return false;
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
        if(exclusiveJob(kind)&&!v.reservations.acquire(p.asLong(),id,now()))return;
        if(kind.equals("wood")){
            if(!v.state.fellingTrees.contains(p.asLong())&&v.state.fellingTrees.size()>=128){v.reservations.release(id);return;}
            treeBase=p.immutable();
            BlockPos next=ForestWork.next(v,treeBase);if(next==null){ForestWork.complete(v,treeBase);return;}p=next;
            v.state.fellingTrees.add(treeBase.asLong());v.runtime.save();
        }
        job=kind;target=p.immutable();since=now();navigation=null;mining=null;collectingUntil=0;
        triedApproaches.clear();lastApproach=null;
        status=switch(kind){case "wood"->"лесозаготовка";case "stone","ore"->"добывает камень и руду";case "harvest","plant","hoe"->"работает на грядках";
            case "storage"->"идёт на общий склад";case "build"->"строит инфраструктуру";case "home"->"возвращается домой";
            case "table"->"идёт к верстаку";case "furnace"->"обслуживает печь";case "seeds"->"ищет семена";
            case "mine"->"идёт ко входу в шахту";case "native_work"->"возвращается к рабочему месту";
            case "pickup"->"собирает припасы";case "shear"->"идёт стричь овец";case "supply"->"несёт припасы застрявшему путнику";default->"работает";};
    }
    void work(BotHandle b){
        if(job.equals("supply")){
            var recipient=v.workers.get(supplyRecipient);
            if(recipient==null||recipient.bot()==null||!recipient.body().isAlive()||Supplies.find(recipient.bot(),s->s.is(ItemTags.PICKAXES)&&Supplies.usable(s))>=0){finish();return;}
            if(navigation==null||navigation.completion().isDone()){
                v.reservations.release(target.asLong(),id);target=recipient.body().blockPosition();
            }
        }
        if(now()-since>(job.equals("mine")||job.equals("escape")?6000:2400)){fail("цель недоступна; выбирает другую работу");return;}
        if((exclusiveJob(job)&&!v.reservations.acquire(target.asLong(),id,now())) || treeBase!=null&&!v.reservations.acquire(treeBase.asLong(),id,now())){fail("цель занята другим работником");return;}
        // A route's preferred standing cell is only a means to reach the actual block. Do not
        // overshoot a visible reachable ore, then reject it because that cell is inaccessible.
        if(navigation!=null&&mining==null&&excavation==null&&collectingUntil==0
                &&!Set.of("mine","escape","supply","pickup").contains(job)
                &&b.entity().getEyePosition().distanceToSqr(Vec3.atCenterOf(target))<=workReach()*workReach()
                &&lineOfSight(b,target)&&!(job.equals("build")&&build!=null&&Construction.intersectsActor(v,b,build))
                &&!(job.equals("renovate")&&b.entity().blockPosition().below().equals(target))){
            navigation.cancel();navigation=null;lastApproach=null;
        }
        if(excavation!=null){
            if(!excavation.completion().isDone())return;
            if(excavation.status()==ExcavationStatus.SUCCESS&&job.equals("mine")){v.state.mineLength=Math.min(32,v.state.mineLength+8);v.runtime.save();}
            else if(excavation.status()!=ExcavationStatus.SUCCESS){lastFailure="проходка: "+excavation.completion().join().reason();delays.put("mine",now()+1800);}
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
                    boolean productionRole=!v.builder(id)&&v.roleIndex(id)%6!=3&&v.roleIndex(id)%6!=5;
                    if((v.builder(id)&&buildingWood>=v.town.timberBatch())||(carriedWood>=4&&(productionRole||Supplies.find(b,s->s.is(ItemTags.AXES)&&Supplies.usable(s))<0))){
                        finish();status="несёт древесину в мастерскую и на стройку";return;
                    }
                    long day=Math.floorMod(v.level.getOverworldClockTime(),24_000);
                    int socialStart=9000+(v.roleIndex(id)%3)*1000;
                    if(nightRestWindow()||day>=socialStart&&day<socialStart+1000){finish();status="вернётся к отмеченному дереву после отдыха";return;}
                    if(!target.equals(treeBase))v.reservations.release(target.asLong(),id);target=next;navigation=null;collectingUntil=0;since=now();return;
                }
                replantTree(b,treeBase);ForestWork.complete(v,treeBase);
            }
            finish();return;
        }
        boolean nativeWork=job.equals("native_work");
        double reach=workReach();
        boolean close=b.entity().getEyePosition().distanceToSqr(Vec3.atCenterOf(target))<=reach*reach;
        if(job.equals("supply"))close=b.entity().distanceToSqr(v.workers.get(supplyRecipient).body())<=9&&b.entity().hasLineOfSight(v.workers.get(supplyRecipient).body());
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
            if(job.equals("pickup")){navigation=b.navigator().goTo(new NavigationGoal.PositionGoal(Vec3.atBottomCenterOf(target),.65),TRAVEL);return;}
            BlockPos stand=v.stand(target,b.entity().position(),reach,!job.equals("build")&&!job.equals("wood")&&!nativeWork,triedApproaches);
            if(stand==null){if(job.equals("wood")&&AccessWorks.tree(this))return;fail("нет безопасного подхода");return;}
            lastApproach=stand;navigation=b.navigator().goTo(new NavigationGoal.PositionGoal(v.feetPosition(stand),job.equals("build")||job.equals("renovate")?.15:.35),TRAVEL);return;
        }
        switch(job){
            case "wood","stone","ore","harvest","seeds","renovate","hazard","sand","scaffold","bench" -> {lastApproach=null;mining=b.miner().breakBlock(target);}
            case "mine" -> {
                BlockPos entrance=BlockPos.of(v.state.mineEntrance);
                var request=new ExcavationRequest(entrance,Direction.from2DDataValue(v.state.mineDirection),ExcavationMode.STAIRCASE_DOWN,
                        Math.min(32,v.state.mineLength+8),v.state.mineLength);
                if(!v.state.contains(request.positionAt(request.length()))){fail("шахта достигла границы деревни");return;}
                excavation=b.miner().excavate(request,new ExcavationPolicy(64,2400,4,true,true));status="прокладывает лестничную шахту";
            }
            case "plant" -> {plant(b,target);finish();}
            case "hoe" -> {
                int hoe=Supplies.find(b,s->s.is(ItemTags.HOES)&&Supplies.usable(s));
                if(!v.level.getBlockState(target).isAir()&&Construction.clear(v.level.getBlockState(target))){mining=b.miner().breakBlock(target);return;}
                if(hoe>=0&&v.level.getBlockState(target).isAir()&&VillageIndex.naturalSoil(v.level.getBlockState(target.below()))){
                    b.inventory().select(hoe);v.level.setBlock(target.below(),Blocks.FARMLAND.defaultBlockState(),3);
                    b.entity().getMainHandItem().hurtAndBreak(1,b.entity(),EquipmentSlot.MAINHAND);
                    v.index.soil.add(target);v.state.plantedCrops.add(target.asLong());plant(b,target);
                }finish();
            }
            case "storage" -> {storage(b,v.steward(id));finish();delays.put("storage",now()+100);}
            case "pickup" -> {pickup(b);finish();}
            case "shear" -> {if(shearNearby(b))collectingUntil=now()+400;else finish();}
            case "supply" -> {
                var recipient=v.workers.get(supplyRecipient);
                if(recipient.combat!=null||recipient.mining!=null||recipient.excavation!=null){finish();return;}
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
            case "furnace" -> {if(v.level.getBlockEntity(target)instanceof FurnaceBlockEntity f)VillageWorkshop.serviceFurnace(b,v,f);finish();delays.put("furnace",now()+200);}
            case "build" -> {if(build!=null&&!Construction.place(v,b,build))v.rejected.put(target.asLong(),now()+400);finish();}
            case "home" -> {finish();delays.put("home",now()+10);}
            case "native_work" -> {finish();delays.put("native_work",now()+400);}
            default -> finish();
        }
    }
    private double workReach(){return job.equals("home")?2.4:job.equals("native_work")?2.8:job.equals("build")||job.equals("renovate")||job.equals("wood")?4.2:job.equals("pickup")?1.3:job.equals("supply")?2.5:3.0;}
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
        if(v.count(Supplies::food)>=Math.max(24,v.workers.size()*6))return false;
        // Turn carried wheat into food before accepting another remote logging job.
        if(b.inventory().count(Items.WHEAT)>=3&&prepareCrafting(b,v.steward(id)))return true;
        boolean farmer=v.roleIndex(id)%6==2||v.workers.size()<3;
        // Early farm duty belongs to the farmer. Other roles can still help in the
        // ordinary idle-work pass, after their available projects and supplies.
        if(!farmer)return false;
        if(v.activeJobs("harvest","plant","hoe")<Math.max(1,v.workers.size()/3)){
            BlockPos ripe=nearest(v.index.crops,p->v.level.getBlockState(p).getBlock() instanceof CropBlock c&&c.isMaxAge(v.level.getBlockState(p)));
            if(ripe!=null){assign("harvest",ripe);return job!=null;}
        }
        if(Supplies.find(b,s->s.is(ItemTags.HOES)&&Supplies.usable(s))<0){if(prepareCrafting(b,v.steward(id)))return true;return false;}
        BlockPos field=nearest(v.index.crops,p->v.level.getBlockState(p).getBlock() instanceof CropBlock);
        if(v.workers.size()>=3&&field!=null&&body().blockPosition().distSqr(field)>16){assign("farm_watch",field);return job!=null;}
        BlockPos empty=nearest(v.index.soil,p->v.level.getBlockState(p).isAir()&&v.level.getBlockState(p.below()).is(Blocks.FARMLAND));
        if(empty!=null&&seed(b)!=null){assign("plant",empty);return job!=null;}
        if(farmExpand(b))return true;
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
        if(e.getInventory().countItem(Items.BREAD)>=3||e.getInventory().countItem(Items.CARROT)>=12||e.getInventory().countItem(Items.POTATO)>=12||e.getInventory().countItem(Items.BEETROOT)>=12)return false;
        int slot=Supplies.find(b,Supplies::food);if(slot<0)return false;
        ItemStack stack=b.inventory().snapshot().slots().get(slot);
        boolean plantingReserve=body().getHealth()>=body().getMaxHealth()&&(v.roleIndex(id)%6==2||v.workers.size()<3)
                &&(stack.is(Items.CARROT)||stack.is(Items.POTATO));
        int n=Math.min(Math.max(0,stack.getCount()-(plantingReserve?1:0)),stack.is(Items.BREAD)?3:12);
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
        Item item=seed(b);if(item==null||!v.state.contains(p)||!v.level.getBlockState(p).isAir()||!v.level.getBlockState(p.below()).is(Blocks.FARMLAND))return;
        Block block=item==Items.WHEAT_SEEDS?Blocks.WHEAT:item==Items.CARROT?Blocks.CARROTS:item==Items.POTATO?Blocks.POTATOES:Blocks.BEETROOTS;
        if(block.defaultBlockState().canSurvive(v.level,p)&&Supplies.consume(b,item,1)){
            v.level.setBlock(p,block.defaultBlockState(),3);v.index.crops.add(p);v.state.plantedCrops.add(p.asLong());
        }
    }
    private boolean watered(BlockPos soil){
        for(BlockPos p:BlockPos.betweenClosed(soil.offset(-4,0,-4),soil.offset(4,1,4)))if(v.level.getFluidState(p).is(FluidTags.WATER))return true;return false;
    }
    private boolean startMine(BotHandle b){
        if(!ready("mine")||v.state.mineLength>=32)return false;
        if(v.state.mineEntrance==null){
            for(Direction d:Direction.Plane.HORIZONTAL){
                BlockPos column=v.state.localMineColumn(d);int x=column.getX(),z=column.getZ();
                BlockPos p=new BlockPos(x,v.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z),z);
                if(v.state.contains(p)&&v.state.contains(p.relative(d.getOpposite(),8).below(8))&&v.safeFeet(p)
                        &&VillageIndex.naturalSoil(v.level.getBlockState(p.below()))&&v.index.beds.stream().allMatch(bed->bed.distSqr(p)>100)){
                    v.state.mineEntrance=p.asLong();v.state.mineDirection=d.getOpposite().get2DDataValue();v.runtime.save();break;
                }
            }
        }
        if(v.state.mineEntrance!=null){
            BlockPos p=BlockPos.of(v.state.mineEntrance);
            if(v.ready(p,id)){assign("mine",p);delays.put("mine",now()+400);return true;}
        }
        return false;
    }
    boolean farmExpand(BotHandle b){
        if(v.index.soil.size()>=Math.min(64,16+v.state.residents.size()*4)||seed(b)==null||Supplies.find(b,s->s.is(ItemTags.HOES)&&Supplies.usable(s))<0||!ready("hoe"))return false;
        BlockPos center=b.entity().blockPosition();
        for(BlockPos mutable:BlockPos.betweenClosed(center.offset(-5,-1,-5),center.offset(5,1,5))){BlockPos p=mutable.immutable();
            if(v.ready(p,id)&&VillageIndex.surfaceFarmSite(v.level,p)&&v.level.getBlockState(p).isAir()&&VillageIndex.naturalSoil(v.level.getBlockState(p.below()))&&watered(p.below())&&v.safeFeet(p)){
                assign("hoe",p);delays.put("hoe",now()+200);return true;}}
        BlockPos bank=nearest(v.index.farmSites,p->VillageIndex.surfaceFarmSite(v.level,p)&&Construction.clear(v.level.getBlockState(p))&&VillageIndex.naturalSoil(v.level.getBlockState(p.below()))
                &&watered(p.below())&&v.safeFeet(p)&&!v.town.protects(p.below()));
        if(bank!=null){assign("hoe",bank);delays.put("hoe",now()+100);return true;}
        for(BlockPos mutable:BlockPos.betweenClosed(center.offset(-6,-1,-6),center.offset(6,1,6))){BlockPos p=mutable.immutable();
            if(v.ready(p,id)&&VillageIndex.surfaceFarmSite(v.level,p)&&Construction.clear(v.level.getBlockState(p))&&VillageIndex.naturalSoil(v.level.getBlockState(p.below()))
                    &&v.safeFeet(p)&&!v.town.protects(p.below())&&v.index.beds.stream().noneMatch(bed->bed.distSqr(p)<9)){
                assign("hoe",p);delays.put("hoe",now()+100);return true;
            }
        }
        delays.put("hoe",now()+400);return false;
    }
    private boolean plantTree(BotHandle b){
        if(!ready("sapling")||Supplies.find(b,s->s.is(ItemTags.SAPLINGS))<0)return false;
        for(long position:List.copyOf(v.state.reforestation)){
            BlockPos p=BlockPos.of(position);if(!v.ready(p,id)||!Construction.clear(v.level.getBlockState(p)))continue;
            int slot=Supplies.find(b,s->s.is(ItemTags.SAPLINGS));var stack=b.inventory().snapshot().slots().get(slot);
            if(stack.getItem() instanceof BlockItem item&&item.getBlock().defaultBlockState().canSurvive(v.level,p)){
                build=new Construction.Step(p,item.getBlock().defaultBlockState(),item);assign("build",p);delays.put("sapling",now()+200);return true;
            }
        }
        BlockPos from=b.entity().blockPosition();
        for(int n=0;n<12;n++){
            int angle=(id.hashCode()+n*37)&255;double rad=angle*Math.PI/128;
            int x=v.state.x+(int)(Math.cos(rad)*(v.state.radius-8)),z=v.state.z+(int)(Math.sin(rad)*(v.state.radius-8));
            int y=v.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z);
            BlockPos p=new BlockPos(x,y,z);
            if(v.ready(p,id)&&v.safeFeet(p)&&VillageIndex.naturalSoil(v.level.getBlockState(p.below()))&&p.distSqr(v.state.center())>100){
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
        for(Item item:new Item[]{Items.CRAFTING_TABLE}){
            if(b.inventory().count(item)==0)continue;
            if(!needsWorkbench)continue;
            BlockPos shared=nearest(v.index.tables,p->v.level.getBlockState(p).is(Blocks.CRAFTING_TABLE)&&p.distSqr(b.entity().blockPosition())<32*32);
            if(shared!=null){if(!VillageWorkshop.nearTable(b,v,shared)&&ready("table")){assign("table",shared);return job!=null;}continue;}
            boolean needed=item==Items.CRAFTING_TABLE?!v.index.tables.stream().anyMatch(p->VillageWorkshop.nearTable(b,v,p)):item==Items.CHEST?v.needsChest():v.state.furnaces.isEmpty();
            if(!needed)continue;
            for(int r=2;r<=6;r++)for(Direction d:Direction.Plane.HORIZONTAL){
                BlockPos p=b.entity().blockPosition().relative(d,r);
                for(int dy:new int[]{0,-1,1,-2,2}){BlockPos at=p.above(dy);
                    if(v.ready(at,id)&&v.safeFeet(at)&&!v.town.protects(at)&&!v.town.constructionCell(at.below())&&v.index.beds.stream().noneMatch(bed->bed.distSqr(at)<4)){
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
            return contents.stream().anyMatch(s->needed(b,s,steward))||extras.stream().anyMatch(s->Supplies.insert(Supplies.copy(contents),s.copyWithCount(1)));
        }).min(Comparator.comparingDouble(c->{double dy=c.getBlockPos().getY()-b.entity().getY();
            return b.entity().position().distanceToSqr(Vec3.atCenterOf(c.getBlockPos()))+15*dy*dy;})).orElse(null);
        if(chest==null)return false;
        if(b.entity().position().distanceToSqr(Vec3.atCenterOf(chest.getBlockPos()))>12||!lineOfSight(b,chest.getBlockPos())){assign("storage",chest.getBlockPos());return true;}
        boolean changed=storage(b,steward);delays.put("storage",now()+100);return changed;
    }
    private int reserve(ItemStack s,boolean steward){
        if(Supplies.tool(s)||s.has(DataComponents.EQUIPPABLE))return Integer.MAX_VALUE;
        if(v.expeditions.owns(id)&&"unload".equals(v.state.expedition.phase)&&!Supplies.food(s)){
            if(s.is(ItemTags.PLANKS))return 8;
            if(s.is(Items.STICK))return 4;
            return 0;
        }
        if(v.builder(id)&&v.town.needs(this,s))return s.is(ItemTags.PLANKS)?BUILDING_TIMBER:16;
        if(s.is(ItemTags.BEDS))return steward?2:0;
        if(s.is(ItemTags.WOODEN_DOORS)||s.is(ItemTags.WOODEN_STAIRS))return steward?1:0;
        if(s.is(Items.TORCH))return steward?8:0;
        if(s.is(Items.CHEST))return steward&&v.needsChest()?1:0;
        if(s.is(Items.CRAFTING_TABLE))return 1;
        if(s.is(Items.FURNACE))return steward&&v.state.furnaces.isEmpty()?1:0;
        if(s.is(ItemTags.LOGS_THAT_BURN))return v.builder(id)?BUILDING_TIMBER/4:needsBasicTools()?4:1;
        if(s.is(ItemTags.PLANKS))return v.builder(id)?BUILDING_TIMBER:needsBasicTools()?16:8;
        if(s.is(Items.STICK))return 8;
        if(s.is(Items.COBBLESTONE))return steward?8:((!v.builder(id)||v.workers.size()<3)&&bNeedsStonePick()?3:0)
                +((v.roleIndex(id)%6==5||v.workers.size()<3)&&!VillageWorkshop.hasDefensiveSword(bot())?2:0);
        if(s.is(Items.IRON_INGOT)){
            if(steward&&Supplies.find(bot(),st->st.is(Items.SHEARS)&&Supplies.usable(st))<0)return 2;
            if((v.roleIndex(id)%6==4||v.workers.size()<3)&&!VillageWorkshop.hasPickFor(bot(),Blocks.DIAMOND_ORE.defaultBlockState()))return 3;
            return v.roleIndex(id)%6==5||v.workers.size()<3?8:0;
        }
        if(s.is(Items.SAND))return steward?16:0;
        if(s.is(Items.GLASS))return v.builder(id)?16:0;
        if(s.is(Items.STONE))return v.builder(id)?32:0;
        if(s.is(Items.WHEAT_SEEDS)||s.is(Items.BEETROOT_SEEDS))return 8;
        if(s.is(ItemTags.WOOL)&&v.builder(id)&&v.town.wantedPlans(this).stream().anyMatch(p->p.block().getBlock() instanceof BedBlock))return 6;
        if(Supplies.food(s))return body().hasExcessFood()?((v.roleIndex(id)%6==2||v.workers.size()<3)&&(s.is(Items.CARROT)||s.is(Items.POTATO))?4:0):s.is(Items.BREAD)?3:12;
        if(s.is(ItemTags.SAPLINGS))return 2;
        if(steward&&(s.is(Items.WHEAT)||s.is(ItemTags.WOOL)||s.is(Items.RAW_IRON)||s.is(Items.COAL)||s.is(Items.CHARCOAL)))return 32;
        if(s.getItem() instanceof BlockItem && !s.is(ItemTags.LOGS) && !s.is(ItemTags.PLANKS))return 0;
        return 0;
    }
    private int depositAmount(BotHandle b,ItemStack s,boolean steward){
        if(s.isEmpty())return 0;
        return Math.max(0,Supplies.count(b.inventory().snapshot().slots(),st->st.is(s.getItem()))-reserve(s,steward));
    }
    private boolean bNeedsStonePick(){return !VillageWorkshop.hasPickFor(bot(),Blocks.IRON_ORE.defaultBlockState())
            ||(v.roleIndex(id)%6==4||v.workers.size()<3)&&VillageWorkshop.needsIronSurvey(v)&&!VillageWorkshop.hasSurveyPick(bot())&&v.count(s->s.is(Items.COBBLESTONE))>=6;}
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
        int wanted=reserve(s,steward);
        if(wanted==Integer.MAX_VALUE||s.isEmpty())return 0;
        if(s.is(ItemTags.PLANKS))return Math.max(0,wanted-Supplies.count(b.inventory().snapshot().slots(),st->st.is(ItemTags.PLANKS)));
        if(s.is(ItemTags.LOGS))return Math.max(0,wanted-Supplies.count(b.inventory().snapshot().slots(),st->st.is(ItemTags.LOGS)));
        if(s.is(ItemTags.LOGS_THAT_BURN)&&Supplies.count(b.inventory().snapshot().slots(),st->st.is(ItemTags.PLANKS))>=16
                &&!(steward&&!v.state.furnaces.isEmpty()&&v.count(st->st.is(Items.COAL)||st.is(Items.CHARCOAL))<8))return 0;
        return Math.max(0,wanted-b.inventory().count(s.getItem()));
    }
    private boolean needed(BotHandle b,ItemStack s,boolean steward){return shortage(b,s,steward)>0;}
    private boolean storage(BotHandle b,boolean steward){
        boolean changed=false;
        for(ChestBlockEntity chest:v.chests()){
            if(b.entity().position().distanceToSqr(Vec3.atCenterOf(chest.getBlockPos()))>16||!lineOfSight(b,chest.getBlockPos()))continue;
            boolean used=false;
            for(ItemStack stack:b.inventory().snapshot().slots()){
                int extra=depositAmount(b,stack,steward);
                if(extra>0){int amount=Supplies.transfer(b,chest,true,s->s.is(stack.getItem()),extra);used|=amount>0;if(amount>0){v.state.deposits.merge(id,(long)amount,Long::sum);if(mineral(stack))v.state.mineralDeposits.merge(id,(long)amount,Long::sum);}}
            }
            for(ItemStack stack:Supplies.copy(chest)){
                int need=shortage(b,stack,steward);
                if(need>0)used|=Supplies.transfer(b,chest,false,s->s.is(stack.getItem()),Math.min(need,stack.getCount()))>0;
            }
            if(used)v.presentation.chest(chest);changed|=used;
        }return changed;
    }
    private boolean shear(BotHandle b){
        int slot=Supplies.find(b,s->s.is(Items.SHEARS)&&Supplies.usable(s));if(slot<0||v.count(s->s.is(ItemTags.WOOL))>=12||!ready("shear"))return false;
        if(shearNearby(b))return true;
        Sheep sheep=v.level.getEntitiesOfClass(Sheep.class,b.entity().getBoundingBox().inflate(v.state.radius),s->s.readyForShearing()&&!s.hasCustomName()&&!s.isLeashed()&&v.ready(s.blockPosition(),id))
                .stream().min(Comparator.comparingDouble(b.entity()::distanceToSqr)).orElse(null);
        if(sheep!=null){assign("shear",sheep.blockPosition());delays.put("shear",now()+200);return true;}
        delays.put("shear",now()+400);return false;
    }
    private boolean shearNearby(BotHandle b){
        int slot=Supplies.find(b,s->s.is(Items.SHEARS)&&Supplies.usable(s));if(slot<0)return false;
        for(Sheep sheep:v.level.getEntitiesOfClass(Sheep.class,b.entity().getBoundingBox().inflate(3),s->s.readyForShearing()&&!s.hasCustomName()&&!s.isLeashed())){
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
    private boolean danger(BotHandle b){
        var e=b.entity();
        Monster enemy=v.level.getEntitiesOfClass(Monster.class,e.getBoundingBox().inflate(12),m->m.isAlive()&&threat(m)&&v.canWork(m.blockPosition())&&e.hasLineOfSight(m))
                .stream().min(Comparator.comparingDouble(e::distanceToSqr)).orElse(null);
        if(enemy==null){fleeing=false;return false;}
        if(combat!=null&&!combat.completion().isDone())return true;
        // A path capture may take longer than the threat scan interval. Keep an existing retreat
        // alive instead of cancelling it every ten ticks while the attacker continues to move.
        if(fleeing&&navigation!=null&&!navigation.completion().isDone()&&now()-since<400&&e.distanceToSqr(enemy)>9)return true;
        boolean trapped=fleeing&&navigation!=null&&navigation.completion().isDone()&&navigation.status()!=NavigationStatus.SUCCESS;
        if(!(enemy instanceof Creeper))v.raiseAlarm(this,enemy);
        cancel();
        boolean equipped=VillageWorkshop.hasDefensiveSword(b);
        if(!(enemy instanceof Creeper)&&(e.getHealth()>=16&&equipped||trapped)&&Supplies.find(b,s->s.is(ItemTags.SWORDS)&&Supplies.usable(s))>=0){
            defend(enemy);return true;
        }
        BlockPos home=v.index.beds.stream().filter(p->p.distSqr(enemy.blockPosition())>16)
                .min(Comparator.comparingDouble(p->{double dy=p.getY()-e.getY();return p.distSqr(e.blockPosition())+15*dy*dy;})).orElse(null);
        BlockPos stand=home==null?null:v.stand(home,e.position(),2.5,false);
        if(stand!=null&&(stand.distSqr(e.blockPosition())<9||stand.distSqr(enemy.blockPosition())<e.blockPosition().distSqr(enemy.blockPosition())))stand=null;
        Vec3 away=e.position().subtract(enemy.position()).normalize().scale(8).add(e.position());
        BlockPos goal=BlockPos.containing(away);if(stand==null)stand=v.stand(goal,e.position());
        if(stand==null)stand=v.stand(v.state.center(),e.position());
        if(stand!=null){navigation=b.navigator().goTo(new NavigationGoal.PositionGoal(Vec3.atBottomCenterOf(stand),.7),TRAVEL);job="home";target=stand;since=now();fleeing=true;}
        status="укрывается от угрозы";return true;
    }
    private boolean threat(Monster m){
        return m.getType()==EntityTypes.ZOMBIE||m.getType()==EntityTypes.ZOMBIE_VILLAGER||m.getType()==EntityTypes.HUSK||m.getType()==EntityTypes.DROWNED
                ||m.getTarget() instanceof Villager target&&v.state.residents.contains(target.getUUID())
                ||m instanceof Creeper creeper&&creeper.getSwellDir()>0;
    }
    void secureDoors(BotHandle b){
        if(!nightRestWindow()||combat!=null)return;
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
    void finish(){v.reservations.release(id);treeBase=null;job=null;target=null;navigation=null;mining=null;excavation=null;build=null;townPlan=null;collectingUntil=0;clearingLeaves=false;fleeing=false;supplyRecipient=null;}
    void cancel(){BotHandle b=bot();if(b!=null&&b.state()==BotState.ACTIVE)b.stopAll();v.reservations.release(id);combat=null;finish();}
    void fail(String reason){
        BlockPos destination=lastApproach!=null?lastApproach:target;boolean recovery=!"escape".equals(job)&&(reason.startsWith("маршрут")||reason.startsWith("цель недоступна"));
        boolean nightHome="home".equals(job)&&nightRestWindow();
        if(nightHome){
            if(target!=null&&v.level.getBlockState(target).getBlock() instanceof BedBlock)homeRetries.put(target.asLong(),now()+200);
            // Surface shelter failure means try another doorway/room, not dig away from safety.
            int surface=v.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,body().blockPosition().getX(),body().blockPosition().getZ());
            if(surface-body().getY()<=6||v.index.beds.stream().anyMatch(p->p.distSqr(body().blockPosition())<64))recovery=false;
        }
        if(job!=null)AutonomousVillagesMod.LOG.info("WORK_INTERRUPTED village={} worker={} job={} target={} cause={}",v.state.name,id,job,target,reason);
        lastFailure=reason;
        if(target!=null&&exclusiveJob(job))v.rejected.put(target.asLong(),now()+600);
        if(treeBase!=null)v.rejected.put(treeBase.asLong(),now()+1200);
        if(job!=null)delays.put(job,now()+(job.equals("storage")?400:100));
        cancel();if(nightHome)delays.put("home",now()+10);cooldown=now()+10;status=reason;
        if(recovery)TerrainRecovery.start(this,destination);
    }
    static boolean exclusiveJob(String kind){return kind!=null&&!Set.of("home","native_work","storage","table","air","escape","farm_watch","supply","pickup").contains(kind);}
}
