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
    static final NavigationPolicy TRAVEL = new NavigationPolicy(false,false,true,false,16_000,256,32,150,2);
    final Village v; final UUID id;
    String status="осматривается", job;
    BlockPos target, treeBase;
    NavigationTask navigation;
    MiningTask mining;
    ExcavationTask excavation;
    CombatTask combat;
    Construction.Step build;
    long since, cooldown, collectingUntil;
    String lastFailure="";
    final Set<BlockPos> triedApproaches=new HashSet<>();
    BlockPos lastApproach;
    boolean fresh=true;
    private final Map<String,Long> delays=new HashMap<>();
    Resident(Village v, UUID id) { this.v=v;this.id=id; }
    BotHandle bot() { return v.runtime.bots.find(id).orElse(null); }
    Villager body() { return (Villager)bot().entity(); }
    long now() { return v.level.getGameTime(); }
    void tick() {
        BotHandle b=bot();
        if (b==null||b.state()!=BotState.ACTIVE||!b.entity().isAlive()) { status="не загружен";return; }
        if (fresh) { b.stopAll();fresh=false; }
        Villager e=(Villager)b.entity();
        if (e.level()!=v.level) { cancel();status="в другом измерении";return; }
        if (e.isTrading()||e.isBaby()||e.isNoAi()) { cancel();status="торгует или отдыхает";return; }
        if ((combat!=null||now()%10==Math.floorMod(id.hashCode(),10))&&danger(b)) return;
        if (combat!=null) { if (!combat.completion().isDone()&&now()-since<600)return;cancel(); }
        long day=Math.floorMod(v.level.getOverworldClockTime(),24_000);
        var jobSite=e.getBrain().getMemory(MemoryModuleType.JOB_SITE).filter(p->p.dimension().equals(v.level.dimension()));
        boolean professionTime=day>=2_000&&day<4_000&&jobSite.isPresent();
        if (v.level.isDarkOutside() || day>=9_000&&day<12_000 || professionTime) {
            String returnKind=professionTime?"native_work":"home";
            if (job!=null&&!job.equals(returnKind)) cancel();
            if (e.isSleeping()) { status="спит";return; }
            if (job==null) {
                BlockPos home=professionTime?jobSite.orElseThrow().pos():e.getBrain().getMemory(MemoryModuleType.HOME).filter(p->p.dimension().equals(v.level.dimension())).map(GlobalPos::pos).orElse(v.state.center());
                if ((v.level.isDarkOutside()||professionTime)&&e.blockPosition().distSqr(home)>64) { assign(returnKind,home);return; }
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
        if (depositOrFetch(b,steward)) return;
        if (VillageWorkshop.make(b,v,steward)) { status="изготавливает инструменты и припасы";return; }
        if (infrastructure(b,steward)) return;
        // Workbench recipes need a real nearby table. Bootstrap 2x2 recipes are possible anywhere.
        if (b.inventory().snapshot().slots().stream().anyMatch(s->s.is(ItemTags.PLANKS)||s.is(Items.COBBLESTONE)||s.is(Items.WHEAT))) {
            BlockPos table=nearest(v.index.tables,p->v.level.getBlockState(p).is(Blocks.CRAFTING_TABLE));
            if (table!=null&&e.position().distanceToSqr(Vec3.atCenterOf(table))>16&&ready("table")) { assign("table",table);return; }
        }
        // Wheat needs a workbench before it becomes food, and supplies need a depot before they can
        // be shared. Do not let endlessly renewed harvest jobs starve this bootstrap dependency.
        if(v.chests().isEmpty()&&Supplies.count(b.inventory().snapshot().slots(),s->s.is(ItemTags.LOGS)||s.is(ItemTags.PLANKS))<(steward?32:12)){
            BlockPos log=nearest(v.index.trees,this::treeCandidate);
            if(log!=null){assign("wood",log);return;}
        }
        BlockPos crop=nearest(v.index.crops,p->v.level.getBlockState(p).getBlock() instanceof CropBlock c&&c.isMaxAge(v.level.getBlockState(p)));
        if (crop!=null&&v.count(Supplies::food)<Math.max(64,v.state.residents.size()*24)) {assign("harvest",crop);return;}
        BlockPos soil=nearest(v.index.soil,p->v.level.getBlockState(p).isAir()&&v.level.getBlockState(p.below()).is(Blocks.FARMLAND));
        if (soil!=null&&seed(b)!=null) {assign("plant",soil);return;}
        if (steward&&v.state.shelter!=null) {
            Construction.Step requested=Construction.next(v);
            Construction.Step step=requested==null?null:Construction.available(b,requested);
            if (step!=null&&Construction.clear(v.level.getBlockState(step.pos()))&&v.ready(step.pos(),id)) {build=step;assign("build",step.pos());return;}
        }
        int wood=v.count(s->s.is(ItemTags.LOGS)||s.is(ItemTags.PLANKS));
        if (wood<Math.max(v.state.shelter!=null?140:48,v.workers.size()*24)) {
            BlockPos log=nearest(v.index.trees,this::treeCandidate);
            if(log!=null){assign("wood",log);return;}
        }
        if (steward&&ready("furnace")) for (long packed:v.state.furnaces) {
            BlockPos p=BlockPos.of(packed);
            if(v.level.getBlockEntity(p) instanceof FurnaceBlockEntity f && !f.isLocked() && VillageWorkshop.wantsFurnace(b,v,f)) {assign("furnace",p);return;}
        }
        if (Supplies.find(b,s->s.is(ItemTags.PICKAXES)&&Supplies.usable(s))>=0) {
            if(v.count(s->s.is(Items.COBBLESTONE))<Math.max(32,v.workers.size()*12)) {
                BlockPos stone=nearest(v.index.stone,p->v.level.getBlockState(p).is(Blocks.STONE)&&VillageIndex.exposed(v.level,p));
                if(stone!=null){assign("stone",stone);return;}
            }
            if(v.count(s->s.is(Items.RAW_IRON)||s.is(Items.IRON_INGOT)||s.is(Items.COAL))<32) {
                BlockPos ore=nearest(v.index.ore,p->VillageIndex.exposed(v.level,p)&&oreHarvestable(b,p));
                if(ore!=null){assign("ore",ore);return;}
            }
            if((v.count(s->s.is(Items.COBBLESTONE))<Math.max(32,v.workers.size()*12)
                    ||v.count(s->s.is(Items.IRON_INGOT)||s.is(Items.RAW_IRON))<12)&&startMine(b))return;
        }
        if (seed(b)==null&&v.count(s->s.is(Items.WHEAT_SEEDS))<16) {
            BlockPos grass=nearest(v.index.grass,p->v.level.getBlockState(p).is(Blocks.SHORT_GRASS)||v.level.getBlockState(p).is(Blocks.TALL_GRASS));
            if(grass!=null){assign("seeds",grass);return;}
        }
        if (farmExpand(b)) return;
        if (salvage(b)) return;
        if (plantTree(b)) return;
        if (steward&&shear(b)) return;
        if (steward&&now()%200<30) Construction.start(v);
        if (e.blockPosition().distSqr(v.state.center())>20*20&&ready("home")) {assign("home",v.state.center());return;}
        status="отдыхает; ждёт урожай и потребности деревни";
    }
    private boolean oreHarvestable(BotHandle b,BlockPos p) {
        var s=v.level.getBlockState(p);
        if(s.is(Blocks.COAL_ORE)||s.is(Blocks.DEEPSLATE_COAL_ORE))return true;
        return (s.is(Blocks.IRON_ORE)||s.is(Blocks.DEEPSLATE_IRON_ORE))&&Supplies.find(b,st->Supplies.usable(st)&&(st.is(Items.STONE_PICKAXE)||st.is(Items.IRON_PICKAXE)||st.is(Items.DIAMOND_PICKAXE)))>=0;
    }
    private boolean ready(String key){return now()>=delays.getOrDefault(key,0L);}
    private boolean treeCandidate(BlockPos base){
        if(VillageIndex.naturalLog(v.level,base))return true;
        if(!v.state.fellingTrees.contains(base.asLong()))return false;
        for(int i=0;i<8;i++)if(v.level.getBlockState(base.above(i)).is(BlockTags.LOGS))return true;
        v.state.fellingTrees.remove(base.asLong());return false;
    }
    private BlockPos nearest(Collection<BlockPos> positions,Predicate<BlockPos> valid){
        BotHandle b=bot();return positions.stream().filter(p->v.ready(p,id)&&valid.test(p))
                .min(Comparator.comparingDouble(p->b.entity().position().distanceToSqr(Vec3.atCenterOf(p)))).orElse(null);
    }
    void assign(String kind,BlockPos p){
        if(!v.reservations.acquire(p.asLong(),id,now()))return;
        if(kind.equals("wood")){
            treeBase=p.immutable();
            for(int dy=0;dy<8;dy++)if(v.level.getBlockState(treeBase.above(dy)).is(BlockTags.LOGS)){p=treeBase.above(dy);break;}
            v.state.fellingTrees.add(treeBase.asLong());v.runtime.save();
        }
        job=kind;target=p.immutable();since=now();navigation=null;mining=null;collectingUntil=0;
        triedApproaches.clear();lastApproach=null;
        status=switch(kind){case "wood"->"лесозаготовка";case "stone","ore"->"добывает камень и руду";case "harvest","plant","hoe"->"работает на грядках";
            case "storage"->"идёт на общий склад";case "build"->"строит инфраструктуру";case "home"->"возвращается домой";
            case "table"->"идёт к верстаку";case "furnace"->"обслуживает печь";case "seeds"->"ищет семена";
            case "mine"->"идёт ко входу в шахту";case "native_work"->"возвращается к рабочему месту";
            case "pickup"->"собирает припасы";case "shear"->"идёт стричь овец";default->"работает";};
    }
    private void work(BotHandle b){
        if(now()-since>(job.equals("mine")?2600:1000)){fail("цель недоступна; выбирает другую работу");return;}
        if(!v.reservations.acquire(target.asLong(),id,now()) || treeBase!=null&&!v.reservations.acquire(treeBase.asLong(),id,now())){fail("цель занята другим работником");return;}
        if(excavation!=null){
            if(!excavation.completion().isDone())return;
            if(excavation.status()==ExcavationStatus.SUCCESS){v.state.mineLength=Math.min(32,v.state.mineLength+8);v.runtime.save();}
            else delays.put("mine",now()+1800);
            finish();return;
        }
        if(navigation!=null){
            if(!navigation.completion().isDone())return;
            if(navigation.status()!=NavigationStatus.SUCCESS){fail("маршрут: "+navigation.completion().join().reason());return;}
            if(lastApproach!=null&&!job.equals("build")&&!lineOfSight(b,target))triedApproaches.add(lastApproach);
            navigation=null;
        }
        if(mining!=null){
            if(!mining.completion().isDone())return;
            if(mining.status()!=MiningStatus.SUCCESS){fail("добыча: "+mining.completion().join().reason());return;}
            mining=null;collectingUntil=now()+80;
        }
        if(collectingUntil>0){
            pickup(b);
            // Walk to stable ground, not to the instantaneous position of a falling item or a sapling
            // resting on the canopy. Those points can become unreachable before pathfinding finishes.
            BlockPos collectAt=treeBase!=null?treeBase:target;
            if(now()<collectingUntil&&v.safeFeet(collectAt)&&b.entity().position().distanceToSqr(Vec3.atBottomCenterOf(collectAt))>1.44){
                lastApproach=null;navigation=b.navigator().goTo(new NavigationGoal.BlockGoal(collectAt,1.0),TRAVEL);return;
            }
            if(now()<collectingUntil){b.activities().run(List.of(new BotActivity.Wait(10)));return;}
            if(job.equals("harvest")) plant(b,target);
            if(job.equals("wood")&&treeBase!=null){
                for(int dy=target.getY()-treeBase.getY()+1;dy<8;dy++)if(v.level.getBlockState(treeBase.above(dy)).is(BlockTags.LOGS)){
                    if(!target.equals(treeBase))v.reservations.release(target.asLong(),id);target=treeBase.above(dy);navigation=null;collectingUntil=0;since=now();return;
                }
                replantTree(b,treeBase);v.state.fellingTrees.remove(treeBase.asLong());v.runtime.save();
            }
            finish();return;
        }
        double reach=job.equals("build")||job.equals("wood")?4.2:job.equals("pickup")?1.3:3.0;
        boolean close=b.entity().getEyePosition().distanceToSqr(Vec3.atCenterOf(target))<=reach*reach;
        if(job.equals("build")&&build!=null&&Construction.intersectsActor(v,b,build))close=false;
        // Builders assemble paid adjacent blocks in reach; aiming at the center of an empty roof cell
        // would incorrectly reject it whenever a previously placed roof edge crosses that ray.
        if(!close||!job.equals("build")&&!lineOfSight(b,target)){
            if(job.equals("pickup")){navigation=b.navigator().goTo(new NavigationGoal.PositionGoal(Vec3.atBottomCenterOf(target),.65),TRAVEL);return;}
            BlockPos stand=v.stand(target,b.entity().position(),reach,!job.equals("build"),triedApproaches);
            if(stand==null){fail("нет безопасного подхода");return;}
            lastApproach=stand;navigation=b.navigator().goTo(new NavigationGoal.PositionGoal(Vec3.atBottomCenterOf(stand),.18),TRAVEL);return;
        }
        switch(job){
            case "wood","stone","ore","harvest","seeds" -> {lastApproach=null;mining=b.miner().breakBlock(target);}
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
                if(hoe>=0&&v.level.getBlockState(target).isAir()&&watered(target.below())&&VillageIndex.naturalSoil(v.level.getBlockState(target.below()))){
                    b.inventory().select(hoe);v.level.setBlock(target.below(),Blocks.FARMLAND.defaultBlockState(),3);
                    b.entity().getMainHandItem().hurtAndBreak(1,b.entity(),EquipmentSlot.MAINHAND);
                    v.index.soil.add(target);v.state.plantedCrops.add(target.asLong());plant(b,target);
                }finish();
            }
            case "storage" -> {storage(b,v.steward(id));finish();delays.put("storage",now()+100);}
            case "pickup" -> {pickup(b);finish();}
            case "shear" -> {shearNearby(b);finish();}
            case "table" -> {finish();delays.put("table",now()+200);}
            case "furnace" -> {if(v.level.getBlockEntity(target)instanceof FurnaceBlockEntity f)VillageWorkshop.serviceFurnace(b,v,f);finish();delays.put("furnace",now()+200);}
            case "build" -> {if(build!=null&&!Construction.place(v,b,build))v.rejected.put(target.asLong(),now()+400);finish();}
            case "home","native_work" -> {finish();delays.put("home",now()+400);}
            default -> finish();
        }
    }
    private boolean lineOfSight(BotHandle b,BlockPos p){
        var hit=v.level.clip(new ClipContext(b.entity().getEyePosition(),Vec3.atCenterOf(p),ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,b.entity()));
        return hit.getType()==HitResult.Type.MISS||hit.getBlockPos().equals(p);
    }
    private void pickup(BotHandle b){
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
    }
    private boolean feed(BotHandle b){
        Villager e=(Villager)b.entity();
        if(e.getInventory().countItem(Items.BREAD)>=3||e.getInventory().countItem(Items.CARROT)>=12||e.getInventory().countItem(Items.POTATO)>=12||e.getInventory().countItem(Items.BEETROOT)>=12)return false;
        int slot=Supplies.find(b,Supplies::food);if(slot<0)return false;
        ItemStack stack=b.inventory().snapshot().slots().get(slot);
        int n=Math.min(Math.max(0,stack.getCount()-(stack.is(Items.CARROT)||stack.is(Items.POTATO)?1:0)),stack.is(Items.BREAD)?3:12);
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
                int x=v.state.x+d.getStepX()*(v.state.radius-8),z=v.state.z+d.getStepZ()*(v.state.radius-8);
                BlockPos p=new BlockPos(x,v.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z),z);
                if(v.safeFeet(p)&&VillageIndex.naturalSoil(v.level.getBlockState(p.below()))&&v.index.beds.stream().allMatch(bed->bed.distSqr(p)>100)){
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
    private boolean farmExpand(BotHandle b){
        if(v.index.soil.size()>=Math.min(64,16+v.state.residents.size()*4)||seed(b)==null||Supplies.find(b,s->s.is(ItemTags.HOES)&&Supplies.usable(s))<0||!ready("hoe"))return false;
        BlockPos center=b.entity().blockPosition();
        for(BlockPos mutable:BlockPos.betweenClosed(center.offset(-5,-1,-5),center.offset(5,1,5))){BlockPos p=mutable.immutable();
            if(v.ready(p,id)&&v.level.getBlockState(p).isAir()&&VillageIndex.naturalSoil(v.level.getBlockState(p.below()))&&watered(p.below())&&v.safeFeet(p)){
                assign("hoe",p);delays.put("hoe",now()+200);return true;}}
        delays.put("hoe",now()+400);return false;
    }
    private boolean plantTree(BotHandle b){
        if(!ready("sapling")||Supplies.find(b,s->s.is(ItemTags.SAPLINGS))<0)return false;
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
        if(!steward)return false;
        for(Item item:new Item[]{Items.CRAFTING_TABLE,Items.CHEST,Items.FURNACE}){
            if(b.inventory().count(item)==0)continue;
            boolean needed=item==Items.CRAFTING_TABLE?v.index.tables.isEmpty():item==Items.CHEST?v.needsChest():v.state.furnaces.isEmpty();
            if(!needed)continue;
            for(int r=2;r<=6;r++)for(Direction d:Direction.Plane.HORIZONTAL){
                BlockPos p=v.state.center().relative(d,r);
                for(int dy:new int[]{0,-1,1,-2,2}){BlockPos at=p.above(dy);
                    if(v.ready(at,id)&&v.safeFeet(at)&&at.distSqr(v.state.center())>4){
                        build=new Construction.Step(at,((BlockItem)item).getBlock().defaultBlockState(),item);assign("build",at);return true;}}
            }
        }
        Construction.Step step=Construction.next(v);
        if(step!=null&&b.inventory().count(step.item())==0){
            if(step.item()==Items.OAK_DOOR&&VillageWorkshop.craft(b,v,new String[]{"PP","PP","PP"},VillageWorkshop.PLANK,s->s.is(ItemTags.WOODEN_DOORS))){status="изготавливает дверь";return true;}
            if(step.item()==Items.OAK_STAIRS&&VillageWorkshop.craft(b,v,new String[]{"P  ","PP ","PPP"},VillageWorkshop.PLANK,s->s.is(ItemTags.WOODEN_STAIRS))){status="изготавливает ступени";return true;}
        }
        return false;
    }
    private boolean depositOrFetch(BotHandle b,boolean steward){
        if(!ready("storage"))return false;
        var chests=v.chests();if(chests.isEmpty())return false;
        ChestBlockEntity chest=chests.stream().min(Comparator.comparingDouble(c->b.entity().position().distanceToSqr(Vec3.atCenterOf(c.getBlockPos())))).orElseThrow();
        var bag=b.inventory().snapshot().slots();
        boolean surplus=bag.stream().anyMatch(s->depositAmount(b,s,steward)>0);
        boolean want=Supplies.copy(chest).stream().anyMatch(s->needed(b,s,steward));
        if(!surplus&&!want)return false;
        if(b.entity().position().distanceToSqr(Vec3.atCenterOf(chest.getBlockPos()))>12){assign("storage",chest.getBlockPos());return true;}
        boolean changed=storage(b,steward);delays.put("storage",now()+100);return changed;
    }
    private int reserve(ItemStack s,boolean steward){
        if(Supplies.tool(s)||s.has(DataComponents.EQUIPPABLE))return Integer.MAX_VALUE;
        if(s.is(ItemTags.BEDS))return steward?2:0;
        if(s.is(ItemTags.WOODEN_DOORS)||s.is(ItemTags.WOODEN_STAIRS))return steward?1:0;
        if(s.is(Items.TORCH))return steward?8:0;
        if(s.is(Items.CHEST))return steward&&v.needsChest()?1:0;
        if(s.is(Items.CRAFTING_TABLE))return steward&&v.index.tables.isEmpty()?1:0;
        if(s.is(Items.FURNACE))return steward&&v.state.furnaces.isEmpty()?1:0;
        if(s.is(ItemTags.LOGS_THAT_BURN))return 4;
        if(s.is(ItemTags.PLANKS))return steward&&v.state.shelter!=null?96:16;
        if(s.is(Items.STICK))return 8;
        if(s.is(Items.COBBLESTONE))return 16;
        if(s.is(Items.IRON_INGOT))return 8;
        if(s.is(Items.WHEAT_SEEDS)||s.is(Items.BEETROOT_SEEDS))return 8;
        if(Supplies.food(s))return 6;
        if(s.is(ItemTags.SAPLINGS))return 2;
        if(steward&&(s.is(Items.WHEAT)||s.is(ItemTags.WOOL)||s.is(Items.RAW_IRON)||s.is(Items.COAL)||s.is(Items.CHARCOAL)))return 32;
        if(s.getItem() instanceof BlockItem && !s.is(ItemTags.LOGS) && !s.is(ItemTags.PLANKS))return Integer.MAX_VALUE;
        return 0;
    }
    private int depositAmount(BotHandle b,ItemStack s,boolean steward){
        if(s.isEmpty())return 0;
        return Math.max(0,Supplies.count(b.inventory().snapshot().slots(),st->st.is(s.getItem()))-reserve(s,steward));
    }
    private int shortage(BotHandle b,ItemStack s,boolean steward){
        int wanted=reserve(s,steward);
        if(wanted==Integer.MAX_VALUE||s.isEmpty())return 0;
        if(s.is(ItemTags.LOGS_THAT_BURN)&&Supplies.count(b.inventory().snapshot().slots(),st->st.is(ItemTags.PLANKS))>=16)return 0;
        return Math.max(0,wanted-b.inventory().count(s.getItem()));
    }
    private boolean needed(BotHandle b,ItemStack s,boolean steward){return shortage(b,s,steward)>0;}
    private boolean storage(BotHandle b,boolean steward){
        boolean changed=false;
        for(ChestBlockEntity chest:v.chests()){
            if(b.entity().position().distanceToSqr(Vec3.atCenterOf(chest.getBlockPos()))>16||!lineOfSight(b,chest.getBlockPos()))continue;
            for(ItemStack stack:b.inventory().snapshot().slots()){
                int extra=depositAmount(b,stack,steward);
                if(extra>0)changed|=Supplies.transfer(b,chest,true,s->s.is(stack.getItem()),extra)>0;
            }
            for(ItemStack stack:Supplies.copy(chest)){
                int need=shortage(b,stack,steward);
                if(need>0)changed|=Supplies.transfer(b,chest,false,s->s.is(stack.getItem()),Math.min(need,stack.getCount()))>0;
            }
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
            if(!v.state.contains(sheep.blockPosition())||!b.entity().hasLineOfSight(sheep))continue;
            b.inventory().select(slot);sheep.shear(v.level,net.minecraft.sounds.SoundSource.NEUTRAL,b.entity().getMainHandItem());
            b.entity().getMainHandItem().hurtAndBreak(1,b.entity(),EquipmentSlot.MAINHAND);status="стрижёт овец для новых кроватей";return true;
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
    private boolean danger(BotHandle b){
        var e=b.entity();
        Monster enemy=v.level.getEntitiesOfClass(Monster.class,e.getBoundingBox().inflate(10),m->m.isAlive()&&v.state.contains(m.blockPosition())&&e.hasLineOfSight(m))
                .stream().min(Comparator.comparingDouble(e::distanceToSqr)).orElse(null);
        if(enemy==null)return false;
        if(combat!=null&&!combat.completion().isDone())return true;
        cancel();
        if(!(enemy instanceof Creeper)&&e.getHealth()>=12&&Supplies.find(b,s->s.is(ItemTags.SWORDS)&&Supplies.usable(s))>=0){
            combat=b.combat().fight(enemy,new CombatPolicy(CombatSkill.EXPERT,16,3,24,false,true,false).swordOnly());since=now();status="защищает деревню";return true;
        }
        Vec3 away=e.position().subtract(enemy.position()).normalize().scale(8).add(e.position());
        BlockPos goal=BlockPos.containing(away);BlockPos stand=v.stand(goal,e.position());
        if(stand==null)stand=v.stand(v.state.center(),e.position());
        if(stand!=null){navigation=b.navigator().goTo(new NavigationGoal.PositionGoal(Vec3.atBottomCenterOf(stand),.7),TRAVEL);job="home";target=stand;since=now();}
        status="укрывается от угрозы";return true;
    }
    void finish(){v.reservations.release(id);treeBase=null;job=null;target=null;navigation=null;mining=null;excavation=null;build=null;collectingUntil=0;}
    void cancel(){BotHandle b=bot();if(b!=null&&b.state()==BotState.ACTIVE)b.stopAll();v.reservations.release(id);combat=null;finish();}
    void fail(String reason){
        lastFailure=reason;
        if(target!=null)v.rejected.put(target.asLong(),now()+600);
        if(treeBase!=null)v.rejected.put(treeBase.asLong(),now()+1200);
        cancel();cooldown=now()+100;status=reason;
    }
}
