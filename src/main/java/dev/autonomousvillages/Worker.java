package dev.autonomousvillages;

import dev.navigationai.api.*;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * One adult resident. Order of concerns each tick: breathing, threats, the day schedule,
 * the current job, and only then a new job from the planner.
 */
final class Worker {
    final Village v;
    final UUID id;
    final BreathingSafety breathing=new BreathingSafety(this);
    Job job;
    /** Kind of the job that ended last; a gatherer carries its haul home right after. */
    String lastJob="";
    String status="осматривается";
    String lastFailure="";
    CombatTask combat;
    boolean fleeing;
    Job.Walk flight;
    long fleeSince;
    long nextDecision;
    long lastRestHeal;
    private long nextFailureLog;
    private long stuckAt;
    private int stuckCount;
    boolean trapped(){
        if(stuckCount<3||body().blockPosition().asLong()!=stuckAt||!ready("escape"))return false;
        stuckCount=0;cooldown("escape",1200);return true;
    }
    boolean released;
    final Map<String,Long> cooldowns=new HashMap<>();
    Worker(Village v,UUID id){this.v=v;this.id=id;}

    BotHandle bot(){return v.runtime.bots.find(id).orElse(null);}
    Villager body(){return (Villager)bot().entity();}
    long now(){return v.level.getGameTime();}
    boolean active(){var b=bot();return b!=null&&b.state()==BotState.ACTIVE&&b.entity().isAlive();}
    String jobKind(){return job==null?null:job.kind();}
    boolean ready(String key){return now()>=cooldowns.getOrDefault(key,0L);}
    /** A cooldown never gets shorter: a job's own long pause survives the generic one set on failure. */
    void cooldown(String key,long ticks){cooldowns.merge(key,now()+ticks,Math::max);}
    ResidentRoles.Role role(){return v.primaryRole(id);}

    void tick(boolean mayPlan){
        BotHandle b=bot();
        if(b==null||b.state()!=BotState.ACTIVE||!b.entity().isAlive()){status="не загружен";return;}
        Villager e=body();
        if(e.level()!=v.level){cancel();status="в другом измерении";return;}
        if(breathing.tick())return;
        restRecovery();
        secureDoors();
        if(e.isTrading()||e.isBaby()||e.isNoAi()){cancel();status="торгует";return;}
        if((combat!=null||fleeing||now()%10==Math.floorMod(id.hashCode(),10))&&danger())return;
        if(combat!=null){if(!combat.completion().isDone()&&now()-fleeSince<600)return;combat=null;}

        long day=Math.floorMod(v.level.getOverworldClockTime(),24_000);
        // During an alarm the unarmed take cover as at night; the armed keep their posts.
        boolean night=nightRest()||v.alarm()&&!armed();
        boolean social=day>=socialStart()&&day<socialStart()+1000;
        boolean profession=day>=professionStart()&&day<professionStart()+600
                &&e.getBrain().getMemory(MemoryModuleType.JOB_SITE).filter(p->p.dimension().equals(v.level.dimension())).isPresent();
        if(night||social||profession){
            if(job!=null&&!nightJob(job)&&!job.atomic()){endJob();}
            if(job==null&&trapped())start(new EscapeJob(this));
            if(job==null&&night&&watchman()&&ready("patrol"))start(new PatrolJob(this));
            if(job==null&&night&&!hasBed()&&ready("shelter")){var shelter=ShelterJob.find(this);if(shelter!=null)start(shelter);else cooldown("shelter",600);}
            if(job==null&&night&&!atHome()&&ready("home"))start(new HomeJob(this));
            if(job==null){
                if(!released){b.stopAll();released=true;}
                status=v.alarm()&&!nightRest()?"прячется: тревога":night?(e.isSleeping()?"спит":"идёт спать"):profession?"работает по профессии и обновляет торговлю":"общение и отдых";
                return;
            }
        }
        if(job!=null){
            boolean timedOut=now()-job.started>job.timeLimit();
            boolean running=!timedOut&&job.tick();
            status=job.status;
            if(!running){if(timedOut)job.failure="не уложился во время";endJob();}
            return;
        }
        if(!mayPlan||now()<nextDecision)return;
        nextDecision=now()+20;
        if(trapped()){start(new EscapeJob(this));return;}
        pickup();
        Job next=v.planner.next(this);
        if(next!=null)start(next);
        else{status=v.planner.idleReason(this);if(!released){b.stopAll();released=true;}}
    }
    void start(Job next){
        job=next;released=false;
        if(job.target()!=null&&!v.reservations.acquire(job.target().asLong(),id,now())){job=null;return;}
        status=job.status;
    }
    void endJob(){
        if(job==null)return;
        job.stop();
        if(!job.failure.isEmpty()){
            lastFailure=job.failure;
            if(job.target()!=null)v.rejected.put(job.target().asLong(),now()+600);
            cooldown(job.kind(),200);
            // Repeated route failures from one spot mean the worker itself is stuck, not the target.
            if(job.failure.startsWith("не подойти")||job.failure.startsWith("не нашёл дорогу")||job.failure.startsWith("не дойти")||job.failure.contains("уложился")){
                long here=body().blockPosition().asLong();
                stuckCount=here==stuckAt?stuckCount+1:1;stuckAt=here;
            }
            v.failures.merge(job.kind()+": "+job.failure,1,Integer::sum);
            if(now()>=nextFailureLog){
                nextFailureLog=now()+600;
                AutonomousVillagesMod.LOG.info("JOB_FAILED {} {} {} reason={} target={} at={} after={}t",v.state.name,v.presentation.personName(id),job.kind(),job.failure,
                        job.target()==null?"-":job.target().toShortString(),body().blockPosition().toShortString(),now()-job.started);
            }
        }
        v.reservations.release(id);
        lastJob=job.kind();
        job=null;
        nextDecision=now();
    }
    void cancel(){
        if(job!=null){job.stop();job=null;}
        v.reservations.release(id);
        var b=bot();if(b!=null&&b.state()==BotState.ACTIVE)b.stopAll();
        combat=null;fleeing=false;flight=null;released=true;
    }

    /** An armed, healthy guard keeps watch at night instead of sleeping; two at most. */
    boolean watchman(){
        // A wooden sword is enough to fight back, not to walk the dark streets.
        return v.guard(id)&&body().getHealth()>=16&&VillageCrafting.hasDefensiveSword(bot())
                &&v.activeJobs("patrol")<2&&!v.index.beds.isEmpty();
    }
    int socialStart(){return 9000+v.roleIndex(id)%3*1000;}
    int professionStart(){return 2000+v.roleIndex(id)%8*600;}
    static boolean nightRestWindow(long clock,boolean dark){long day=Math.floorMod(clock,24000);return dark||day<1000||day>=11500;}
    boolean nightRest(){return nightRestWindow(v.level.getOverworldClockTime(),v.level.isDarkOutside());}

    // --- inventory -------------------------------------------------------------------------
    List<ItemStack> bag(){return bot().inventory().snapshot().slots();}
    int count(Predicate<ItemStack> test){return Supplies.count(bag(),test);}
    boolean has(Predicate<ItemStack> test){return Supplies.find(bot(),test)>=0;}
    boolean hasTool(net.minecraft.tags.TagKey<net.minecraft.world.item.Item> tag){return has(s->s.is(tag)&&Supplies.usable(s));}
    int freeSlots(){return (int)bag().stream().filter(ItemStack::isEmpty).count();}
    /** Picks up loose items next to the body. */
    void pickup(){
        var b=bot();
        for(ItemEntity item:v.level.getEntitiesOfClass(ItemEntity.class,b.entity().getBoundingBox().inflate(1.4,.7,1.4),i->i.isAlive()&&!i.hasPickUpDelay())){
            ItemStack rest=b.inventory().insert(item.getItem());
            if(rest.isEmpty())item.discard();else item.setItem(rest);
        }
    }
    /** Items a worker keeps instead of handing them to the store: the best tool of each kind and a little food. */
    Set<Integer> keptSlots(){
        var bag=bag();var kept=new HashSet<Integer>();var bestByFamily=new HashMap<String,Integer>();int food=0,seeds=0;
        for(int i=0;i<bag.size();i++){
            var s=bag.get(i);if(s.isEmpty())continue;
            String family=PersonalKit.family(s);
            if(!family.isEmpty()&&Supplies.usable(s)){
                Integer best=bestByFamily.get(family);
                if(best==null||PersonalKit.rank(s)>PersonalKit.rank(bag.get(best)))bestByFamily.put(family,i);
            }
            if(Supplies.food(s)&&food<8){kept.add(i);food+=s.getCount();}
            // Planting stock stays with the worker, or it is fetched and handed straight back.
            else if((s.is(Items.WHEAT_SEEDS)||s.is(Items.BEETROOT_SEEDS)||s.is(Items.CARROT)||s.is(Items.POTATO))&&seeds<32){kept.add(i);seeds+=s.getCount();}
            if(job!=null&&job.keeps(s))kept.add(i);
        }
        kept.addAll(bestByFamily.values());
        return kept;
    }
    boolean hasSurplus(){var bag=bag();var kept=keptSlots();for(int i=0;i<bag.size();i++)if(!bag.get(i).isEmpty()&&!kept.contains(i))return true;return false;}
    int surplusItems(){var bag=bag();var kept=keptSlots();int n=0;for(int i=0;i<bag.size();i++)if(!kept.contains(i))n+=bag.get(i).getCount();return n;}

    // --- home and rest -----------------------------------------------------------------------
    static boolean nightJob(Job job){return job instanceof HomeJob||job instanceof PatrolJob||job instanceof ShelterJob||job instanceof EscapeJob;}
    /** True when vanilla gave this villager a bed of its own. */
    boolean hasBed(){
        return body().getBrain().getMemory(MemoryModuleType.HOME).filter(p->p.dimension().equals(v.level.dimension()))
                .map(GlobalPos::pos).filter(p->v.level.getBlockState(p).getBlock() instanceof BedBlock).isPresent();
    }
    BlockPos restHome(){
        var nativeHome=body().getBrain().getMemory(MemoryModuleType.HOME).filter(p->p.dimension().equals(v.level.dimension())).map(GlobalPos::pos)
                .filter(p->v.level.getBlockState(p).getBlock() instanceof BedBlock);
        if(nativeHome.isPresent())return nativeHome.get();
        return v.index.beds.stream().min(Comparator.comparingDouble(p->p.distSqr(body().blockPosition()))).orElse(v.meetingPoint());
    }
    boolean atHome(){return body().isSleeping()||body().blockPosition().distSqr(restHome())<=6*6;}
    private void restRecovery(){
        var e=body();
        if(e.getHealth()>=e.getMaxHealth()||now()-lastRestHeal<400||job!=null&&!(job instanceof HomeJob)&&!(job instanceof PatrolJob))return;
        var food=e.getInventory();
        for(int slot=0;slot<food.getContainerSize();slot++)if(Supplies.food(food.getItem(slot))){
            food.removeItem(slot,1);e.heal(2);lastRestHeal=now();return;
        }
        int slot=Supplies.find(bot(),Supplies::food);
        if(slot>=0){bot().inventory().extract(slot,1);e.heal(2);lastRestHeal=now();}
    }
    /** Closes wooden doors behind the worker at night or while fleeing. */
    private void secureDoors(){
        if(!(nightRest()||fleeing)||combat!=null||now()%5!=Math.floorMod(id.hashCode(),5))return;
        var e=body();
        for(BlockPos p:v.town.doors()){
            if(e.getEyePosition().distanceToSqr(Vec3.atCenterOf(p))>9)continue;
            var state=v.level.getBlockState(p);
            if(!(state.getBlock() instanceof DoorBlock door)||!state.is(BlockTags.WOODEN_DOORS)||state.getValue(DoorBlock.HALF)!=DoubleBlockHalf.LOWER||!state.getValue(DoorBlock.OPEN))continue;
            if(!v.level.getEntitiesOfClass(LivingEntity.class,new AABB(p).expandTowards(0,1,0).inflate(.1),LivingEntity::isAlive).isEmpty())continue;
            door.setOpen(e,v.level,state,p,false);
        }
    }

    // --- threats -------------------------------------------------------------------------------
    private boolean threat(Monster m){
        return m.getType()==EntityTypes.ZOMBIE||m.getType()==EntityTypes.ZOMBIE_VILLAGER||m.getType()==EntityTypes.HUSK||m.getType()==EntityTypes.DROWNED
                ||m.getTarget() instanceof Villager target&&v.state.residents.contains(target.getUUID())
                ||m instanceof Creeper creeper&&creeper.getSwellDir()>0;
    }
    private boolean danger(){
        var e=body();
        Monster enemy=v.level.getEntitiesOfClass(Monster.class,e.getBoundingBox().inflate(12),m->m.isAlive()&&threat(m)&&e.hasLineOfSight(m))
                .stream().min(Comparator.comparingDouble(e::distanceToSqr)).orElse(null);
        if(enemy==null){
            if(fleeing&&flight!=null&&now()-fleeSince<300&&flight.tick()==Job.Approach.State.MOVING)return true;
            if(fleeing){fleeing=false;flight=null;}
            return false;
        }
        if(combat!=null&&!combat.completion().isDone())return true;
        // Asleep or sheltering under a roof: running out into the dark is worse than staying put.
        if((e.isSleeping()||job instanceof ShelterJob&&!e.level().canSeeSky(e.blockPosition()))&&e.distanceToSqr(enemy)>36){
            if(!(enemy instanceof Creeper))v.raiseAlarm(this,enemy);
            return false;
        }
        if(!(enemy instanceof Creeper))v.raiseAlarm(this,enemy);
        if(!(enemy instanceof Creeper)&&armed()&&(e.getHealth()>=14||v.guard(id)&&e.getHealth()>=8)){defend(enemy);return true;}
        // Keep running along the current route: planning a new one makes the body stand still.
        if(fleeing&&flight!=null&&now()-fleeSince<300&&flight.tick()==Job.Approach.State.MOVING)return true;
        if(job!=null){job.stop();job=null;v.reservations.release(id);}
        bot().stopAll();
        Vec3 away=e.position().subtract(enemy.position());double length=Math.max(.001,Math.hypot(away.x,away.z));
        BlockPos refuge=v.index.beds.stream().filter(bed->bed.distSqr(enemy.blockPosition())>e.blockPosition().distSqr(enemy.blockPosition())&&bed.distSqr(e.blockPosition())>4)
                .min(Comparator.comparingDouble(bed->bed.distSqr(e.blockPosition()))).orElse(null);
        Vec3 point=refuge!=null?Vec3.atBottomCenterOf(refuge):e.position().add(away.x/length*12,0,away.z/length*12);
        // Running blind into the dark can end in powder snow or off a cliff: without a bed to run to, run to the bell.
        if(refuge==null&&!v.safeFeet(BlockPos.containing(point)))point=v.feetPosition(v.meetingPoint());
        flight=new HomeJob(this).new Walk(point,1.5);fleeing=true;fleeSince=now();released=false;
        flight.tick();status="убегает от угрозы";
        return true;
    }
    /** A guard fights with any sword; others only with stone or better. */
    boolean armed(){return v.guard(id)?VillageCrafting.hasSword(bot()):VillageCrafting.hasDefensiveSword(bot());}
    void defend(Monster enemy){
        if(job!=null){job.stop();job=null;v.reservations.release(id);}
        combat=bot().combat().fight(enemy,new CombatPolicy(CombatSkill.EXPERT,16,3,24,false,true,false).swordOnly());
        fleeSince=now();released=false;status="защищает деревню";
    }
}
