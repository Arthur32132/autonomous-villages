package dev.autonomousvillages;

import dev.navigationai.api.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.tags.*;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.*;

/** An equipped party gathers, leaves, works a real site, returns and unloads before its next outing. */
final class ExpeditionManager {
    static final NavigationPolicy TRAVEL=new NavigationPolicy(false,false,true,true,32_000,1024,32,200,2);
    final Village v;
    private ExcavationTask excavation;
    private final Map<UUID,NavigationTask> moving=new HashMap<>();
    private final Map<UUID,BlockPos> approaches=new HashMap<>();
    private final Map<UUID,BlockPos> destinations=new HashMap<>();
    private final Map<UUID,Set<BlockPos>> failedApproaches=new HashMap<>();
    private final Map<UUID,BlockPos> waitingSpots=new HashMap<>();
    private final Map<UUID,Long> retryAt=new HashMap<>();
    private long lastDecision;
    ExpeditionManager(Village v){this.v=v;}
    static boolean departureWindow(long clock,boolean dark){long day=Math.floorMod(clock,24000);return !dark&&day>=1000&&day<=8000;}
    static int plannedMineLength(int entranceY,int completed,int pickDurability,boolean needsIron,long clock){
        long day=Math.floorMod(clock,24000);int additional=8;
        // A fresh stone-or-better pick can pay for at most72 new blocks and retain8 durability.
        // Keep bootstrap and late-day trips short; use early equipped trips to survey deeper rock.
        if(needsIron&&pickDurability>=80&&day>=1000&&day<=3000)additional=Math.min(24,Math.max(8,entranceY-16-completed));
        return Math.min(64,completed+additional);
    }
    int mineLength(ExpeditionState e){
        var leader=resident(e.team.getFirst());
        int durability=leader.bot().inventory().snapshot().slots().stream()
                .filter(s->s.is(ItemTags.PICKAXES)&&Supplies.usable(s)&&s.isCorrectToolForDrops(Blocks.IRON_ORE.defaultBlockState()))
                .mapToInt(s->s.getMaxDamage()-s.getDamageValue()).max().orElse(0);
        return plannedMineLength(BlockPos.of(e.target).getY(),e.completedSegments,durability,
                VillageWorkshop.needsIronSurvey(v),v.level.getOverworldClockTime());
    }
    boolean owns(UUID id){return v.state.expedition!=null&&v.state.expedition.team.contains(id)&&!v.state.expedition.phase.equals("prepare")&&!v.state.expedition.phase.equals("scout");}
    boolean permits(BlockPos p){var e=v.state.expedition;return e!=null&&Math.abs((long)p.getX()-BlockPos.of(e.target).getX())<=80&&Math.abs((long)p.getZ()-BlockPos.of(e.target).getZ())<=80;}
    Set<Long> chunks(){
        var e=v.state.expedition;if(e==null)return Set.of();
        var p=BlockPos.of(e.target);var result=new HashSet<Long>();
        for(int x=(p.getX()>>4)-2;x<=(p.getX()>>4)+2;x++)for(int z=(p.getZ()>>4)-2;z<=(p.getZ()>>4)+2;z++)result.add(ChunkPos.pack(x,z));
        if(e.purpose.equals("mine"))for(int n=0;n<=e.length;n+=8){
            BlockPos along=p.relative(Direction.from2DDataValue(e.direction),n);
            for(int x=(along.getX()>>4)-1;x<=(along.getX()>>4)+1;x++)for(int z=(along.getZ()>>4)-1;z<=(along.getZ()>>4)+1;z++)result.add(ChunkPos.pack(x,z));
        }
        return result;
    }
    long deposited(ExpeditionState e){return e.team.stream().mapToLong(id->v.state.deposits.getOrDefault(id,0L)).sum();}
    long mineralsDeposited(ExpeditionState e){return e.team.stream().mapToLong(id->v.state.mineralDeposits.getOrDefault(id,0L)).sum();}
    long carriedMinerals(ExpeditionState e){return e.team.stream().map(this::resident).filter(Objects::nonNull).mapToLong(r->Supplies.count(r.bot().inventory().snapshot().slots(),Resident::mineral)).sum();}
    int carriedWool(ExpeditionState e){return e.team.stream().map(this::resident).filter(Objects::nonNull).mapToInt(r->Supplies.count(r.bot().inventory().snapshot().slots(),s->s.is(ItemTags.WOOL))+3*Supplies.count(r.bot().inventory().snapshot().slots(),s->s.is(ItemTags.BEDS))).sum();}
    Resident resident(UUID id){var r=v.workers.get(id);return r!=null&&r.bot()!=null&&r.body().isAlive()?r:null;}
    boolean preparingSurveyPick(UUID id){
        var e=v.state.expedition;var r=resident(id);
        return e!=null&&e.purpose.equals("mine")&&e.phase.equals("prepare")&&!e.team.isEmpty()&&e.team.getFirst().equals(id)
                &&r!=null&&(v.roleIndex(id)%6==4||v.workers.size()<3)&&VillageWorkshop.needsIronSurvey(v)
                &&v.count(s->s.is(Items.COBBLESTONE))>=6&&!VillageWorkshop.hasSurveyPick(r.bot());
    }
    void tick(){
        long now=v.level.getGameTime();var e=v.state.expedition;
        if(now%100==0)for(Resident observer:v.workers.values())if(observer.bot()!=null)
            for(Sheep sheep:v.level.getEntitiesOfClass(Sheep.class,observer.body().getBoundingBox().inflate(64),this::wildAdult))rememberPasture(sheep.blockPosition().asLong());
        if(e==null){
            maybeStart(now);return;
        }
        // Only confirmed removal from settlement membership means death/release. Chunk loading
        // and controller restoration are asynchronous and must not erase a live traveller.
        e.team.removeIf(id->!v.state.residents.contains(id));
        e.returnTrails.keySet().retainAll(e.team);
        e.unloadedMembers.retainAll(e.team);
        if(e.team.isEmpty()){finish(e,false,"no_living_members");return;}
        if(e.team.stream().anyMatch(id->resident(id)==null))return;
        if(e.purpose.equals("wool")){
            if(!e.woolSnapshot){e.woolCargoStart=carriedWool(e);e.woolSnapshot=true;}
            e.woolCollected=Math.max(e.woolCollected,carriedWool(e)-e.woolCargoStart);
        }
        if(!e.cargoSnapshot&&!Set.of("scout","prepare","rally").contains(e.phase)){e.mineralDeliveryStart=mineralsDeposited(e);e.mineralCargoStart=carriedMinerals(e);e.cargoSnapshot=true;}
        long journeyBudget=routeBudget(Math.sqrt(BlockPos.of(e.target).distSqr(v.meetingPoint())));
        if(now-e.started>24000+2*journeyBudget){finish(e,false,"outing_timeout");return;}
        if(now-e.started>18000+journeyBudget&&!e.phase.equals("return")&&!e.phase.equals("unload"))phase(e,"return");
        if(now-e.phaseSince>Math.max(5000,e.phaseBudget)&&(e.phase.equals("return")||e.phase.equals("unload"))){finish(e,false,e.phase+"_timeout");return;}
        if(Set.of("travel","work").contains(e.phase)&&now%20==0)for(UUID id:e.team){
            var r=resident(id);if(r==null||!v.safeFeet(r.body().blockPosition()))continue;
            e.recordReturnPoint(id,r.body().blockPosition());
        }
        switch(e.phase){
            case "scout"->scout(e);
            case "prepare"->{
                boolean ready=e.team.stream().map(this::resident).allMatch(r->r!=null&&(!e.purpose.equals("mine")||!e.team.getFirst().equals(r.id)
                        ||Supplies.find(r.bot(),s->s.is(ItemTags.PICKAXES)&&Supplies.usable(s))>=0&&(v.count(s->s.is(Items.COBBLESTONE))<3||VillageWorkshop.hasPickFor(r.bot(),Blocks.IRON_ORE.defaultBlockState()))&&!preparingSurveyPick(r.id))
                        &&r.body().getHealth()>8);
                boolean atomicJobsFinished=e.team.stream().map(this::resident).allMatch(r->r!=null&&r.mining==null&&r.excavation==null&&r.collectingUntil==0);
                if(ready&&atomicJobsFinished){if(e.purpose.equals("mine"))e.length=mineLength(e);phase(e,"rally");}
                else if(now-e.phaseSince>2400)finish(e,false,"preparation_timeout");
            }
            case "rally"->{
                if(!departureWindow(v.level.getOverworldClockTime(),v.level.isDarkOutside())){finish(e,false,"nightfall_before_departure");return;}
                var rally=v.meetingPoint();boolean gathered=e.team.stream().map(this::resident).allMatch(r->r.body().blockPosition().distSqr(rally)<64);
                if(gathered){e.departingTogether=true;e.deliveryStart=deposited(e);e.mineralDeliveryStart=mineralsDeposited(e);e.mineralCargoStart=carriedMinerals(e);e.cargoSnapshot=true;if(e.purpose.equals("wool")){e.woolCargoStart=carriedWool(e);e.woolCollected=0;e.woolSnapshot=true;}phase(e,"travel");v.event("Вылазка отправилась: "+label(e)+", группа "+e.team.size());}
                else if(now-e.phaseSince>Math.max(1200,e.phaseBudget))finish(e,false,"rally_timeout");
            }
            case "travel"->{
                if(Resident.nightRestWindow(v.level.getOverworldClockTime(),v.level.isDarkOutside())){phase(e,"return");return;}
                Resident leader=resident(e.team.getFirst());if(leader.body().blockPosition().distSqr(BlockPos.of(e.target))<36)phase(e,"work");
                if(now-e.phaseSince>Math.max(3600,e.phaseBudget))phase(e,"return");
            }
            case "work"->{
                boolean atomic=e.team.stream().map(this::resident).anyMatch(r->r.mining!=null||r.excavation!=null||r.collectingUntil>0);
                if(Resident.nightRestWindow(v.level.getOverworldClockTime(),v.level.isDarkOutside())&&excavation==null&&!atomic)phase(e,"return");
                else if(e.purpose.equals("mine"))mine(e);
                else if(now-e.phaseSince>1600&&!atomic)phase(e,"return");
            }
            case "return"->{
                if(Resident.nightRestWindow(v.level.getOverworldClockTime(),v.level.isDarkOutside())&&e.team.stream().map(this::resident).allMatch(r->v.state.contains(r.body().blockPosition()))){e.returnTrails.clear();phase(e,"shelter");return;}
                BlockPos rally=v.meetingPoint();if(e.team.stream().map(this::resident).allMatch(r->r.body().blockPosition().distSqr(rally)<100))phase(e,"unload");
            }
            case "shelter"->{if(!Resident.nightRestWindow(v.level.getOverworldClockTime(),v.level.isDarkOutside()))phase(e,"return");}
            case "unload"->{
                if(Resident.nightRestWindow(v.level.getOverworldClockTime(),v.level.isDarkOutside())){phase(e,"shelter");return;}
                if(e.team.stream().allMatch(id->e.unloadedMembers.contains(id)&&!resident(id).hasSurplus())){
                    long delivered=deposited(e)-e.deliveryStart;
                    if(delivered>0)v.event("Вся группа вернулась и разгрузилась: "+delivered+" предметов");
                    finish(e,delivered>0&&(e.purpose.equals("mine")?e.brokenBlocks>0&&mineralsDeposited(e)-e.mineralDeliveryStart>e.mineralCargoStart:e.purpose.equals("wool")?e.woolCollected>0:e.brokenBlocks>0||e.harvested>0));
                }
            }
        }
    }
    private void maybeStart(long now){
            if(now<v.state.nextExpedition||v.workers.size()<2||!departureWindow(v.level.getOverworldClockTime(),v.level.isDarkOutside())||v.chests().isEmpty()||now-lastDecision<100)return;
            if(v.state.buildings.stream().anyMatch(b->b.kind.equals("warehouse")&&b.built(v)<16))return;
            lastDecision=now;
            String choice=null;
            if(v.index.trees.isEmpty()&&v.count(s->s.is(ItemTags.LOGS)||s.is(ItemTags.PLANKS))<v.town.woodDemand())choice="wood";
            else if(v.index.crops.isEmpty()&&v.count(s->s.is(Items.WHEAT_SEEDS)||s.is(Items.CARROT)||s.is(Items.POTATO))<4)choice="forage";
            else if(v.index.beds.size()<v.state.residents.size()+2&&bedStock()<Math.min(4,v.state.residents.size()+2-v.index.beds.size())
                    &&v.workers.values().stream().anyMatch(r->Supplies.find(r.bot(),s->s.is(Items.SHEARS)&&Supplies.usable(s))>=0))choice="wool";
            else if(v.count(s->s.is(Items.RAW_IRON)||s.is(Items.IRON_INGOT))<Math.max(8,v.workers.size()*3)
                    ||v.count(s->s.is(Items.COAL)||s.is(Items.CHARCOAL))<16||v.count(s->s.is(Items.COBBLESTONE))<128)choice="mine";
            final String purpose=choice;
            if(purpose==null)return;
            List<Resident> candidates=v.workers.values().stream().filter(r->(!v.builder(r.id)||v.workers.size()<3||purpose.equals("wool"))&&r.combat==null&&!r.body().isTrading()&&r.body().getHealth()>8)
                    .filter(r->v.state.contains(r.body().blockPosition())&&!r.shouldUnloadBeforeWork())
                    .filter(r->!purpose.equals("wool")||Supplies.find(r.bot(),s->s.is(Items.SHEARS)&&Supplies.usable(s))>=0)
                    .sorted(Comparator.comparingInt(r->v.roleIndex(r.id)%6==4?0:v.roleIndex(r.id)%6==3||v.roleIndex(r.id)%6==5?1:2)).toList();
            if(candidates.isEmpty())return;
            if(purpose.equals("mine")&&Supplies.find(candidates.getFirst().bot(),s->s.is(ItemTags.PICKAXES)&&Supplies.usable(s))<0)return;
            var e=new ExpeditionState();e.purpose=purpose;e.started=now;e.phaseSince=now;
            int teamSize=v.workers.size()>=6?2:1;
            for(Resident r:candidates){e.team.add(r.id);if(e.team.size()>=teamSize)break;}
            e.deliveryStart=deposited(e);chooseSite(e);v.state.expedition=e;v.event("Готовят "+label(e)+", участников "+e.team.size());v.runtime.save();return;
    }
    private static boolean purposeIsMine(String purpose){return "mine".equals(purpose);}
    private int bedStock(){
        int beds=v.count(s->s.is(ItemTags.BEDS));
        for(Item item:net.minecraft.core.registries.BuiltInRegistries.ITEM)if(new ItemStack(item).is(ItemTags.WOOL))beds+=v.count(s->s.is(item))/3;
        return beds;
    }
    private String label(ExpeditionState e){return e.purpose.equals("mine")?"поход в шахту":e.purpose.equals("wood")?"лесная вылазка":e.purpose.equals("wool")?"поиск овец и шерсти":"поиск семян";}
    private void chooseSite(ExpeditionState e){
        if(e.purpose.equals("wool")&&e.attempts==0&&!e.team.isEmpty()){
            Resident leader=resident(e.team.getFirst());
            if(leader!=null){
                Sheep known=v.level.getEntitiesOfClass(Sheep.class,leader.body().getBoundingBox().inflate(128),s->wildAdult(s)&&s.readyForShearing())
                        .stream().min(Comparator.comparingDouble(s->{double dy=s.getY()-leader.body().getY();return leader.body().distanceToSqr(s)+15*dy*dy;})).orElse(null);
                if(known!=null){e.target=known.blockPosition().asLong();rememberPasture(e.target);e.phase="prepare";return;}
            }
        }
        if(e.purpose.equals("mine")){
            var mine=v.state.mines.stream().filter(m->m.length<64&&!m.blocked).findFirst().orElse(null);
            if(mine!=null){e.target=mine.entrance;e.direction=mine.direction;e.length=Math.min(64,mine.length+8);e.completedSegments=mine.length;e.phase="prepare";return;}
        }
        int i=v.state.exploredSites++;double angle=i*2.399963229728653;
        if(e.purpose.equals("wool")&&e.attempts%3==0&&!v.state.sheepPastures.isEmpty()){
            var pastures=v.state.sheepPastures.stream().sorted(Comparator.comparingDouble(p->BlockPos.of(p).distSqr(v.state.center()))).toList();
            e.target=pastures.get(Math.floorMod(e.attempts/3,pastures.size()));e.phase="scout";return;
        }
        if(e.purpose.equals("wool")&&e.attempts%3==1&&!v.state.worksites.isEmpty()){
            var known=new ArrayList<>(v.state.worksites);
            e.target=known.get(Math.floorMod(i/2,known.size()));e.phase="scout";return;
        }
        if(e.purpose.equals("wool")){i=v.state.woolScouts++;angle=i*2.399963229728653;}
        double distance=Math.max(48,v.state.radius)+32+(i/12)*32;
        e.target=new BlockPos(v.state.x+(int)(Math.cos(angle)*distance),v.state.y,v.state.z+(int)(Math.sin(angle)*distance)).asLong();e.phase="scout";
    }
    private void scout(ExpeditionState e){
        BlockPos target=BlockPos.of(e.target);if(!v.level.hasChunkAt(target)||v.level.getGameTime()-e.phaseSince<80)return;
        if(e.purpose.equals("wool")){
            var flock=v.level.getEntitiesOfClass(Sheep.class,new AABB(target).inflate(24),this::wildAdult);
            for(Sheep animal:flock)rememberPasture(animal.blockPosition().asLong());
            Sheep sheep=flock.stream().filter(Sheep::readyForShearing).findFirst().orElse(null);
            if(sheep!=null){
                e.target=sheep.blockPosition().asLong();rememberPasture(e.target);
                phase(e,"prepare");return;
            }
            if(++e.attempts>24){finish(e,false);return;}chooseSite(e);e.phaseSince=v.level.getGameTime();return;
        }
        if(e.purpose.equals("wood")){
            for(int x=-8;x<=8;x++)for(int z=-8;z<=8;z++){
                int y=v.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,target.getX()+x,target.getZ()+z);
                for(int dy=-16;dy<=0;dy++){BlockPos p=new BlockPos(target.getX()+x,y+dy,target.getZ()+z);
                    if(VillageIndex.naturalLog(v.level,p)&&VillageIndex.naturalSoil(v.level.getBlockState(p.below()))){e.target=p.asLong();v.index.trees.add(p);phase(e,"prepare");return;}}
            }
        }else for(int x=-8;x<=8;x+=4)for(int z=-8;z<=8;z+=4){
            int y=v.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,target.getX()+x,target.getZ()+z);
            BlockPos p=new BlockPos(target.getX()+x,y,target.getZ()+z);var ground=v.level.getBlockState(p.below());
            if(!v.safeFeet(p)||(!VillageIndex.naturalSoil(ground)&&!ground.is(Blocks.STONE)))continue;
            if(e.purpose.equals("forage")){
                boolean found=false;for(BlockPos q:BlockPos.betweenClosed(p.offset(-6,0,-6),p.offset(6,1,6)))if(v.level.getBlockState(q).is(Blocks.SHORT_GRASS)){v.index.grass.add(q.immutable());found=true;}
                if(!found)continue;
            }
            int direction=Direction.from2DDataValue(Math.floorMod(v.state.exploredSites,4)).get2DDataValue();
            if(e.purpose.equals("mine")){direction=mineDirection(p);if(direction<0)continue;}
            e.target=p.asLong();e.direction=direction;e.length=8;e.completedSegments=0;phase(e,"prepare");return;
        }
        if(++e.attempts>24){finish(e,false);return;}chooseSite(e);e.phaseSince=v.level.getGameTime();v.runtime.save();
    }
    private int mineDirection(BlockPos entrance){
        int best=-1,bestStone=0;
        for(Direction direction:Direction.Plane.HORIZONTAL){
            var plan=new ExcavationRequest(entrance,direction,ExcavationMode.STAIRCASE_DOWN,8,0);int solid=0,stone=0;boolean safe=true;
            for(int n=1;n<=8&&safe;n++){
                BlockPos floor=plan.positionAt(n).below();
                if(!v.level.hasChunkAt(floor)||!v.level.getBlockState(floor).isFaceSturdy(v.level,floor,Direction.UP)){safe=false;break;}
                for(BlockPos cell:plan.clearanceAt(n)){
                    var state=v.level.getBlockState(cell);
                    if(v.town.protects(cell)||VillageIndex.hazard(state)||!state.getFluidState().isEmpty()){safe=false;break;}
                    if(!state.getCollisionShape(v.level,cell).isEmpty())solid++;
                    if(state.is(Blocks.STONE)||state.is(Blocks.DEEPSLATE))stone++;
                }
            }
            if(safe&&solid>=10&&stone>bestStone){bestStone=stone;best=direction.get2DDataValue();}
        }
        return bestStone>=3?best:-1;
    }
    private void rememberPasture(long position){
        BlockPos found=BlockPos.of(position);
        if(v.state.sheepPastures.stream().map(BlockPos::of).noneMatch(p->Math.abs(p.getX()-found.getX())<=24&&Math.abs(p.getZ()-found.getZ())<=24))v.state.sheepPastures.add(position);
        if(v.state.sheepPastures.size()>8)v.state.sheepPastures.stream().max(Comparator.comparingDouble(p->BlockPos.of(p).distSqr(v.state.center()))).ifPresent(v.state.sheepPastures::remove);
    }
    private boolean wildAdult(Sheep sheep){return sheep.isAlive()&&!sheep.isBaby()&&!sheep.isLeashed()&&!sheep.hasCustomName();}
    static long routeBudget(double distance){return 1200+(long)Math.ceil(Math.max(0,distance)*20);}
    private void mine(ExpeditionState e){
        Resident leader=resident(e.team.getFirst());
        if(e.dug){
            if(leader.job!=null)return;
            if(v.level.getGameTime()-e.oreSince>1600||e.oreCollected>=24){phase(e,"return");return;}
            var ore=e.oreTargets.stream().map(BlockPos::of).filter(p->safeOre(leader,p))
                    .min(Comparator.<BlockPos>comparingInt(p->v.level.getBlockState(p).is(Blocks.IRON_ORE)||v.level.getBlockState(p).is(Blocks.DEEPSLATE_IRON_ORE)?0:1)
                            .thenComparingDouble(p->p.distSqr(leader.body().blockPosition()))).orElse(null);
            if(ore==null){phase(e,"return");return;}
            leader.assign("ore",ore);return;
        }
        if(excavation==null){
            leader.cancel();
            var request=new ExcavationRequest(BlockPos.of(e.target),Direction.from2DDataValue(e.direction),ExcavationMode.STAIRCASE_DOWN,e.length,e.completedSegments);
            for(int i=e.completedSegments+1;i<=request.length();i++)for(BlockPos p:request.clearanceAt(i))if(v.town.protects(p)){phase(e,"return");return;}
            excavation=leader.bot().miner().excavate(request,new ExcavationPolicy(96,3600,4,true,true));
            v.event("Начата проходка шахты "+BlockPos.of(e.target).toShortString());
        }
        e.brokenBlocks=excavation.brokenBlocks();
        if(!excavation.completion().isDone())return;
        int completed=excavation.completedSegments();
        if(completed<=e.completedSegments&&excavation.status()!=ExcavationStatus.SUCCESS){
            var blocked=v.state.mines.stream().filter(m->m.entrance==e.target).findFirst().orElse(null);
            if(blocked==null){blocked=new ExpeditionState.Mine();blocked.entrance=e.target;blocked.direction=e.direction;v.state.mines.add(blocked);}
            blocked.blocked=true;v.event("Отмечен непроходимый участок шахты; выберут другой вход");
        }
        if(completed>e.completedSegments){
            var mine=v.state.mines.stream().filter(m->m.entrance==e.target).findFirst().orElse(null);
            if(mine==null){mine=new ExpeditionState.Mine();mine.entrance=e.target;mine.direction=e.direction;v.state.mines.add(mine);}
            mine.length=Math.max(mine.length,completed);
        }
        if(excavation.status()!=ExcavationStatus.SUCCESS)v.event("Шахтёры возвращаются: "+excavation.completion().join().reason());
        excavation=null;e.dug=true;e.workedSegments=completed;e.oreSince=v.level.getGameTime();discoverOres(e);v.runtime.save();
    }
    private void discoverOres(ExpeditionState e){
        var request=new ExcavationRequest(BlockPos.of(e.target),Direction.from2DDataValue(e.direction),ExcavationMode.STAIRCASE_DOWN,e.length);
        var queue=new ArrayDeque<BlockPos>();
        for(int step=0;step<=e.workedSegments;step++)for(var cell:request.clearanceAt(step))for(var side:Direction.values())queue.add(cell.relative(side));
        var visited=new HashSet<Long>();
        while(!queue.isEmpty()&&e.oreTargets.size()<128){
            BlockPos p=queue.removeFirst();if(!visited.add(p.asLong())||!v.level.hasChunkAt(p)||!permits(p)||v.town.protects(p)||!VillageIndex.oreResource(v.level.getBlockState(p)))continue;
            e.oreTargets.add(p.asLong());for(var side:Direction.values())queue.add(p.relative(side));
        }
    }
    private boolean safeOre(Resident miner,BlockPos p){
        if(!v.level.hasChunkAt(p)||!v.ready(p,miner.id)||v.town.protects(p)||!VillageIndex.oreResource(v.level.getBlockState(p))||!VillageIndex.exposed(v.level,p))return false;
        if(!v.level.getFluidState(p).isEmpty()||v.level.getBlockState(p.above()).getBlock() instanceof FallingBlock)return false;
        if((v.level.getBlockState(p).is(Blocks.COAL_ORE)||v.level.getBlockState(p).is(Blocks.DEEPSLATE_COAL_ORE))&&v.count(s->s.is(Items.COAL))>=32)return false;
        for(var side:Direction.values())if(!v.level.hasChunkAt(p.relative(side))||!v.level.getFluidState(p.relative(side)).isEmpty())return false;
        return Supplies.find(miner.bot(),s->s.is(ItemTags.PICKAXES)&&Supplies.usable(s)&&s.isCorrectToolForDrops(v.level.getBlockState(p)))>=0;
    }
    boolean work(Resident r){
        var e=v.state.expedition;if(e==null||!owns(r.id))return false;
        var movingTask=moving.get(r.id);
        if(r.job==null&&e.phase.equals("return")&&(movingTask==null||movingTask.completion().isDone())&&r.travelTools(r.bot()))return true;
        // Inventory insertion stops library tasks. A resident's emergency staircase is separate
        // from the party's mining task and must finish before opportunistic collection.
        if(excavation==null&&r.excavation==null&&r.mining==null&&r.navigation==null&&(movingTask==null||movingTask.completion().isDone()))r.pickup(r.bot());
        r.status=label(e)+" • "+switch(e.phase){case "rally"->"сбор группы";case "travel"->"путь к участку";case "work"->"добыча";case "return"->"возвращение";case "unload"->"разгрузка склада";case "shelter"->"ночует дома, груз сохранён";default->"подготовка";};
        if(e.phase.equals("work")&&e.team.getFirst().equals(r.id)&&e.purpose.equals("mine")&&r.job==null)return true;
        if(r.job!=null){if(r.job.equals("escape"))r.status="прокладывает безопасный путь для группы";r.work(r.bot());return true;}
        switch(e.phase){
            case "shelter"->{if(!r.atRestHome())r.assign("home",r.restHome());}
            case "rally"->travel(r,v.meetingPoint());
            case "return"->{
                var trail=e.returnTrails.get(r.id);
                while(trail!=null&&!trail.isEmpty()&&(r.body().blockPosition().distSqr(BlockPos.of(trail.getLast()))<9||!v.safeFeet(BlockPos.of(trail.getLast()))))trail.removeLast();
                travel(r,trail==null||trail.isEmpty()?v.meetingPoint():BlockPos.of(trail.getLast()));
            }
            case "travel"->{
                if(e.team.getFirst().equals(r.id))travel(r,BlockPos.of(e.target));
                else follow(r,resident(e.team.getFirst()));
            }
            case "work"->{
                if(!e.team.getFirst().equals(r.id)){if(e.purpose.equals("mine"))guardEntrance(r,e);else follow(r,resident(e.team.getFirst()));break;}
                if(e.purpose.equals("wood")){
                    var log=v.index.trees.stream().filter(p->permits(p)&&VillageIndex.naturalLog(v.level,p)&&v.ready(p,r.id)).findFirst().orElse(null);
                    if(log!=null)r.assign("wood",log);else phase(e,"return");
                }else if(e.purpose.equals("wool")){
                    Sheep sheep=v.level.getEntitiesOfClass(Sheep.class,new AABB(BlockPos.of(e.target)).inflate(24),s->s.readyForShearing()&&v.ready(s.blockPosition(),r.id))
                            .stream().min(Comparator.comparingDouble(r.body()::distanceToSqr)).orElse(null);
                    if(sheep!=null&&bedStock()<Math.max(2,Math.min(6,v.state.residents.size()+2-v.index.beds.size())))r.assign("shear",sheep.blockPosition());else phase(e,"return");
                }else{
                    var grass=v.index.grass.stream().filter(p->permits(p)&&v.level.getBlockState(p).is(Blocks.SHORT_GRASS)&&v.ready(p,r.id)).findFirst().orElse(null);
                    if(grass!=null)r.assign("seeds",grass);else phase(e,"return");
                }
            }
            case "unload"->{
                boolean cargo=r.hasSurplus();
                if(cargo)e.unloadedMembers.remove(r.id);else e.unloadedMembers.add(r.id);
                UUID next=e.team.stream().map(this::resident).filter(Objects::nonNull).filter(Resident::hasSurplus).map(member->member.id).findFirst().orElse(null);
                BlockPos waiting=waitingSpot(e,r);
                boolean aisleClear=e.team.stream().map(this::resident).filter(Objects::nonNull).filter(member->member!=r)
                        .allMatch(member->member.body().position().distanceToSqr(v.feetPosition(waitingSpot(e,member)))<=1);
                // Waiting inside a one-wide depot aisle blocks the next carrier even when
                // the chest has room. Keep the rest of the party at the exterior rally.
                if(!cargo||!r.id.equals(next)||!aisleClear){
                    r.status=cargo?"ждёт очереди разгрузки снаружи склада":"выгрузил припасы, ждёт остальных снаружи склада";
                    travel(r,waiting,1);
                    if(r.body().position().distanceToSqr(v.feetPosition(waiting))<=1&&r.bot().activities().status()!=ActivityStatus.RUNNING)r.bot().activities().run(List.of(new BotActivity.Wait(40)));
                    break;
                }
                var approach=moving.remove(r.id);if(approach!=null&&!approach.completion().isDone())approach.cancel();
                approaches.remove(r.id);destinations.remove(r.id);failedApproaches.remove(r.id);
                if(!r.depositOrFetch(r.bot(),v.steward(r.id)))r.status="выбирает доступное место разгрузки";
            }
        }
        return true;
    }
    void travel(Resident r,BlockPos target){
        travel(r,target,3);
    }
    private BlockPos waitingSpot(ExpeditionState e,Resident r){
        return waitingSpots.computeIfAbsent(r.id,id->{
            BlockPos rally=v.meetingPoint();int i=e.team.indexOf(id);
            BlockPos preferred=i==1?rally:rally.offset(i==0?-3:3,0,i>1?2:1);
            if(v.safeFeet(preferred))return preferred;
            BlockPos stand=v.stand(preferred,Vec3.atBottomCenterOf(rally),4,false,new HashSet<>(waitingSpots.values()));
            return stand==null?preferred:stand;
        });
    }
    private void travel(Resident r,BlockPos target,double arrivalRadius){
        if(!target.equals(destinations.put(r.id,target.immutable()))){
            var previous=moving.remove(r.id);if(previous!=null&&!previous.completion().isDone())previous.cancel();
            approaches.remove(r.id);failedApproaches.remove(r.id);
        }
        if(arrivalRadius==3?r.body().blockPosition().distSqr(target)<9:r.body().position().distanceToSqr(v.feetPosition(target))<=arrivalRadius*arrivalRadius)return;
        var task=moving.get(r.id);if(task!=null&&!task.completion().isDone())return;
        var failed=failedApproaches.computeIfAbsent(r.id,ignored->new HashSet<>());
        if(v.level.getGameTime()>=retryAt.getOrDefault(r.id,Long.MAX_VALUE)){failed.clear();retryAt.remove(r.id);}
        // SUCCESS describes the chosen stand, which can be several blocks below the waypoint.
        // If the real waypoint is still out of reach, that stand failed to advance the journey.
        // Retrying it forever used to exhaust the return budget while reporting SUCCESS.
        if(task!=null){
            BlockPos previous=approaches.get(r.id);if(previous!=null)failed.add(previous);moving.remove(r.id);
            if(failed.size()>=3&&TerrainRecovery.start(r,target))return;
        }
        BlockPos stand=v.safeFeet(target)&&!failed.contains(target)?target:v.stand(target,r.body().position(),6,false,failed);
        if(stand==null){retryAt.putIfAbsent(r.id,v.level.getGameTime()+100);r.status="проверяет безопасный путь для группы";return;}
        approaches.put(r.id,stand);
        moving.put(r.id,r.bot().navigator().goTo(new NavigationGoal.PositionGoal(v.feetPosition(stand),Math.min(1,arrivalRadius*.5)),TRAVEL));
    }
    private void follow(Resident follower,Resident leader){
        if(leader==null)return;
        var task=moving.get(follower.id);if(task!=null&&!task.completion().isDone())return;
        moving.put(follower.id,follower.bot().navigator().follow(new NavigationGoal.EntityFollowGoal(leader.body(),4,6),TRAVEL));
    }
    private void guardEntrance(Resident helper,ExpeditionState e){
        BlockPos flank=BlockPos.of(e.target).relative(Direction.from2DDataValue(e.direction).getClockWise(),3);
        if(!v.safeFeet(flank))flank=v.stand(flank,helper.body().position(),3,false);
        if(flank==null)return;
        var task=moving.get(helper.id);if(task!=null&&!task.completion().isDone())return;
        if(helper.body().position().distanceToSqr(v.feetPosition(flank))>.5)moving.put(helper.id,helper.bot().navigator().goTo(new NavigationGoal.PositionGoal(v.feetPosition(flank),.35),TRAVEL));
        else if(helper.bot().activities().status()!=ActivityStatus.RUNNING)helper.bot().activities().run(List.of(new BotActivity.Wait(40)));
        helper.status="охраняет вход, оставляет шахтёру свободный проход";
    }
    private void phase(ExpeditionState e,String phase){
        if(excavation!=null){excavation.cancel();excavation=null;}
        for(UUID id:e.team){var r=resident(id);if(r!=null)r.cancel();}
        moving.clear();approaches.clear();destinations.clear();failedApproaches.clear();waitingSpots.clear();retryAt.clear();e.phase=phase;e.phaseSince=v.level.getGameTime();
        if(Set.of("rally","travel","return").contains(phase)){
            BlockPos destination=phase.equals("travel")?BlockPos.of(e.target):v.meetingPoint();
            e.phaseBudget=routeBudget(e.team.stream().map(this::resident).filter(Objects::nonNull).mapToDouble(r->Math.sqrt(r.body().blockPosition().distSqr(destination))).max().orElse(0));
        }
        if(phase.equals("unload"))e.unloadedMembers.clear();
        if(!phase.equals("scout")&&!v.state.rememberWorksite(e.target)){finish(e,false,"worksite_budget");return;}
        v.runtime.save();
    }
    private void finish(ExpeditionState e,boolean delivered){
        finish(e,delivered,delivered?"cargo_delivered":"no_verified_cargo");
    }
    private void finish(ExpeditionState e,boolean delivered,String reason){
        AutonomousVillagesMod.LOG.info("EXPEDITION_END village={} purpose={} phase={} reason={} delivered={} members={} positions={}",
                v.state.name,e.purpose,e.phase,reason,delivered,e.team,e.team.stream().map(this::resident).filter(Objects::nonNull).map(r->r.id+"@"+r.body().position()).toList());
        v.event(delivered?"Вылазка завершена, добыча доставлена":"Вылазка прервана: "+reason+"; путники возвращаются самостоятельно");
        for(UUID id:e.team){var r=resident(id);if(r!=null&&!Set.of("prepare","scout").contains(e.phase))r.cancel();}
        if(excavation!=null){excavation.cancel();excavation=null;}moving.clear();approaches.clear();destinations.clear();failedApproaches.clear();waitingSpots.clear();retryAt.clear();
        if(delivered){v.state.completedExpeditions++;v.state.expeditionDeliveries+=Math.max(0,deposited(e)-e.deliveryStart);if(e.purpose.equals("mine")){v.state.completedMiningExpeditions++;v.state.verifiedMiningDeliveries++;}}
        v.state.expedition=null;v.state.nextExpedition=v.level.getGameTime()+(delivered?600:1200);v.runtime.save();
    }
    void stop(){if(excavation!=null){excavation.cancel();excavation=null;}moving.clear();approaches.clear();destinations.clear();failedApproaches.clear();waitingSpots.clear();retryAt.clear();}
}
