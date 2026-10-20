package dev.autonomousvillages;

import java.util.*;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.LightningRodBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Гроза: lightning turns a villager into a witch, so residents caught under the open sky go under a village roof
 * until the thunder stops, and the village crowns its watchtower (else the barn) with a lightning rod crafted from
 * three copper ingots of the store. Switch {@code storm} in {@link ModConfig}.
 *
 * Thunder darkens the sky enough for {@link Worker#hiding()}: the night routine ends work and sends residents home,
 * vanilla walks them back out by day, and those it leaves under the sky are taken into a house ({@link ShelterJob},
 * a night job the routine keeps). Under a bright sky {@link StormShelterJob} does it through the planner instead.
 * The rod draws every bolt within 128 blocks onto the tower, whose lookout is the watch's first stop, so no
 * round is walked under thunder. On Normal and Hard a bolt's stray sparks can still catch the oak railing (or the
 * barn's ridge) beside the rod; rain usually puts them out, and nobody repairs what burns.
 */
final class StormFeature implements Features.Feature {
    static final String KEY="storm";
    /** Anything solid this many blocks above the feet is a roof. */
    static final int ROOF=6;
    /** Vanilla's lightning rod: three copper ingots in a column. */
    static final int ROD_COPPER=3;
    /** A rod found gone is made again only after a day: a player who took it may have had a reason. */
    static final long REBUILD_AFTER=24_000;
    /** Pause after a rod job; each failed attempt doubles it up to a day, so an unreachable roof is not climbed for every minute. */
    static final long ROD_RETRY=1200,ROD_RETRY_MAX=24_000;
    /** Shelter searches per pass at most; each reads a few hundred cells. */
    static final int SEARCHES=3;
    static final String SHELTERED="Гроза прошла, все укрылись";
    static final Predicate<ItemStack> COPPER=s->s.is(Items.COPPER_INGOT);
    static final Predicate<ItemStack> RAW_COPPER=s->s.is(Items.RAW_COPPER);
    static final VillageCrafting.Recipe ROD=new VillageCrafting.Recipe("lightning_rod",new String[]{"M","M","M"},COPPER,
            s->s.is(Items.LIGHTNING_ROD.weathering().unaffected()),"громоотвод");

    /** Saved: where the village's rod stands and since when it has been seen missing. */
    static final class State { public boolean built; public long rod; public long missingSince=-1; }
    /** Not saved: the storm under way, the shelters this feature started, the rod site while one is wanted. */
    private static final class Storm {
        final StormLog log=new StormLog();
        final Set<Job> started=Collections.newSetFromMap(new WeakHashMap<>());
        /** Where each active resident stood at the last strike scan: a resident turned witch is gone before the bolt lists it. */
        final Map<UUID,Vec3> seen=new HashMap<>();
        BlockPos rodSite;
        boolean tower;
        long nextRodTry=Long.MIN_VALUE,rodDelay=ROD_RETRY;
    }
    private static final Map<Village,Storm> STORMS=new WeakHashMap<>();
    private static Storm storm(Village v){return STORMS.computeIfAbsent(v,k->new Storm());}

    @Override public String key(){return KEY;}
    @Override public boolean enabled(){return AutonomousVillagesMod.CONFIG.storm;}

    @Override public void tick(Village v,long time){
        boolean thunder=v.level.isThundering();
        var s=storm(v);
        if(thunder&&(time&1)==1)watchStrikes(v,s);
        if(time%20!=13)return;
        boolean night=Worker.nightRestWindow(v.level.getOverworldClockTime(),false);
        // At nightfall those sheltered by day go to their beds as on any night.
        if(night)for(var w:List.copyOf(v.workers.values()))if(w.active()&&w.job instanceof ShelterJob&&s.started.contains(w.job))w.endJob();
        if(thunder){s.log.begin();shelter(v,s,night);}
        else{s.seen.clear();String line=s.log.end();if(line!=null)v.chronicle.announce(line);}
        if(time%200==113)watchRod(v,s,v.level.getGameTime());
    }

    @Override public Job job(Village v,Worker w,ResidentRoles.Role role){
        var s=storm(v);
        if(v.level.isThundering()){
            // Reached only while the sky stays bright: nobody walks out to work, those outside go in.
            if(!w.ready("storm"))return null;
            BlockPos feet=w.body().blockPosition();boolean outside=outdoors(v,feet);
            Job j=outside?StormShelterJob.find(w):ShelterJob.indoor(v,feet)?StormShelterJob.stay(w,feet):null;
            if(j==null){if(outside)w.cooldown("storm",600);return null;}
            s.log.sheltered(w.id);s.started.add(j);
            return j;
        }
        return rodJob(v,w,s);
    }

    // --- shelter -------------------------------------------------------------------------------------
    private static void shelter(Village v,Storm s,boolean night){
        int searches=0;boolean exposed=false;
        for(var w:List.copyOf(v.workers.values())){
            if(!w.active())continue;
            // The rod draws the bolts onto the tower and its lookout, the round's first stop: the watch waits indoors.
            if(w.job instanceof PatrolJob)w.endJob();
            if(v.guard(w.id))w.cooldown("patrol",600);
            Job job=w.job;var body=w.body();
            // On the way in already, asleep, or a child (vanilla minds those).
            if(job instanceof ShelterJob||job instanceof StormShelterJob||job instanceof HomeJob||body.isSleeping()||body.isBaby())continue;
            if(!outdoors(v,body.blockPosition()))continue;
            boolean hiding=w.hiding();
            // At night vanilla takes them to bed; on a dark day the routine ends work itself, and the bright
            // social and trade hours belong to vanilla, which would end a shelter at once.
            boolean left=job!=null&&(job.atomic()||Worker.nightJob(job))||!w.ready("storm")||(hiding?night||job!=null||!w.ready("shelter"):busyHour(w));
            if(left||searches>=SEARCHES){exposed=true;continue;}
            searches++;
            Job shelter=hiding?ShelterJob.find(w):StormShelterJob.find(w);
            if(shelter==null){w.cooldown("storm",600);exposed=true;continue;}
            if(job!=null)w.endJob();
            w.start(shelter);
            if(w.job==shelter){s.started.add(shelter);s.log.sheltered(w.id);}else exposed=true;
        }
        s.log.exposed(exposed);
    }
    /** The hours the worker gives to vanilla (meeting and trade), when it ends any job that is not a night job. */
    private static boolean busyHour(Worker w){
        long day=Math.floorMod(w.v.level.getOverworldClockTime(),24000);
        return day>=w.socialStart()&&day<w.socialStart()+1000||day>=w.professionStart()&&day<w.professionStart()+600;
    }
    /** Under the open sky: the sky is seen from the head and nothing solid is within {@link #ROOF} blocks above the feet. */
    static boolean outdoors(Village v,BlockPos feet){
        if(!v.level.hasChunkAt(feet)||!v.level.canSeeSky(feet.above()))return false;
        return outdoors(p->v.level.hasChunkAt(p)&&v.level.getBlockState(p).blocksMotion(),feet);
    }
    static boolean outdoors(Predicate<BlockPos> solid,BlockPos feet){
        for(int k=1;k<=ROOF;k++)if(solid.test(feet.above(k)))return false;
        return true;
    }

    /** One storm's account: whom this feature took under a roof, whether anyone stayed out, whether lightning struck anyone. */
    static final class StormLog {
        private boolean active,struck,exposed;
        private final Set<UUID> sheltered=new HashSet<>(),hit=new HashSet<>();
        void begin(){if(active)return;active=true;struck=exposed=false;sheltered.clear();hit.clear();}
        void sheltered(UUID id){if(active)sheltered.add(id);}
        /** Whether an adult stood under the open sky, not on the way in, at the storm's latest pass. */
        void exposed(boolean any){if(active)exposed=any;}
        /** True the first time this resident is reported struck in the storm. */
        boolean struck(UUID id){struck=true;return hit.add(id);}
        /** The line for the chronicle as the storm ends: only once, only when someone sheltered, nobody stayed out and nobody was struck. */
        String end(){
            if(!active)return null;
            active=false;
            return !struck&&!exposed&&!sheltered.isEmpty()?SHELTERED:null;
        }
    }
    /** A bolt that hit a resident (it becomes a witch) is told once; it also spoils the storm's line. */
    private static void watchStrikes(Village v,Storm s){
        var area=VillageDiscovery.area(v.state,8);int y=v.state.center().getY();
        var box=new AABB(area.minX,y-48,area.minZ,area.maxX,y+64,area.maxZ);
        for(var bolt:v.level.getEntitiesOfClass(LightningBolt.class,box,e->true)){
            // On Peaceful the bolt only burns and lists the villager; elsewhere the witch replaces it at once, unlisted.
            var hit=new LinkedHashSet<UUID>();
            bolt.getHitEntities().map(Entity::getUUID).filter(v.state.residents::contains).forEach(hit::add);
            hit.addAll(vanished(s.seen,strikeBox(bolt.position()),id->{var e=v.level.getEntity(id);return e==null||!e.isAlive();}));
            for(UUID id:hit)if(s.log.struck(id))v.chronicle.announce("Молния ударила в жителя: "+v.presentation.personName(id));
        }
        s.seen.clear();
        for(var w:v.workers.values())if(w.active())s.seen.put(w.id,w.body().position());
    }
    /** The box a bolt standing at this point hits, as {@link LightningBolt#tick} measures it. */
    static AABB strikeBox(Vec3 bolt){return new AABB(bolt.x-3,bolt.y-3,bolt.z-3,bolt.x+3,bolt.y+9,bolt.z+3);}
    /** Residents last seen in the strike (a block of slack for the body and two ticks of walking) whose body is gone since. */
    static List<UUID> vanished(Map<UUID,Vec3> seen,AABB strike,Predicate<UUID> gone){
        var box=strike.inflate(1);
        return seen.entrySet().stream().filter(e->box.contains(e.getValue())&&gone.test(e.getKey())).map(Map.Entry::getKey).toList();
    }

    // --- the lightning rod ---------------------------------------------------------------------------
    /** True when the store holds enough copper ingots for a rod. */
    static boolean rodAffordable(int ingots){return ingots>=ROD_COPPER;}
    /** Raw copper to smelt for a rod: what the ingots lack, and only when the store holds that much. */
    static int copperToSmelt(int ingots,int raw){int need=ROD_COPPER-ingots;return need>0&&raw>=need?need:0;}

    private static Job rodJob(Village v,Worker w,Storm s){
        BlockPos site=s.rodSite;long now=v.level.getGameTime();
        if(site==null||now<s.nextRodTry||!w.ready("rod")||v.activeJobs("rod")>0)return null;
        long day=Math.floorMod(v.level.getOverworldClockTime(),24000);
        if(day<1000||day>=9000)return null;
        boolean inHand=v.stock.count(LightningRodJob.ROD)>0||v.workers.values().stream().anyMatch(x->x.active()&&x.has(LightningRodJob.ROD));
        if(!inHand){
            int ingots=v.stock.count(COPPER)+w.count(COPPER);
            if(!rodAffordable(ingots)){
                // Copper comes from the miners as raw ore: the furnace makes the ingots, the workshop collects them.
                int smelt=copperToSmelt(ingots,v.stock.count(RAW_COPPER));
                var snap=v.planner.snapshot();
                // Counting the store for every idle resident is not free: the copper is looked for again in half a minute.
                if(smelt==0||snap.furnace()==null||snap.furnaceOutput()||v.activeJobs("smelt")>0||!w.ready("smelt")){s.nextRodTry=now+600;return null;}
                s.nextRodTry=now+2400;
                return new SmeltJob(w,snap.furnace(),RAW_COPPER,smelt,"медь для громоотвода");
            }
            if(v.index.tables.isEmpty()){s.nextRodTry=now+600;return null;}
        }
        s.nextRodTry=now+ROD_RETRY;
        return new LightningRodJob(w,site,s.tower);
    }
    /** The pause after another failed attempt. */
    static long backoff(long delay){return Math.min(delay*2,ROD_RETRY_MAX);}
    /** A rod job failed: the next try waits twice as long as the last, a day at most. */
    static void rodFailed(Village v){var s=storm(v);s.rodDelay=backoff(s.rodDelay);s.nextRodTry=v.level.getGameTime()+s.rodDelay;}
    /** Every ten seconds: is the rod still there, and if one is wanted, where it goes. */
    private static void watchRod(Village v,Storm s,long now){
        var st=Features.load(v,KEY,State.class,State::new);
        s.rodSite=null;
        if(st.built){
            BlockPos at=BlockPos.of(st.rod);
            if(!v.level.hasChunkAt(at))return;
            if(isRod(v,at)){if(st.missingSince>=0){st.missingSince=-1;Features.store(v,KEY,st);}return;}
            if(st.missingSince<0||st.missingSince>now){st.missingSince=now;Features.store(v,KEY,st);}
            if(now-st.missingSince<REBUILD_AFTER)return;
            st.built=false;st.missingSince=-1;Features.store(v,KEY,st);
        }
        TownBuilding b=completed(v,"tower");s.tower=b!=null;
        if(b==null)b=completed(v,"storehouse");
        BlockPos site=b==null?null:site(v,b);
        if(site==null)return;
        if(isRod(v,site)){rodPlaced(v,site,s.tower,false);return;}
        s.rodSite=site;
    }
    private static TownBuilding completed(Village v,String kind){
        return v.state.buildings.stream().filter(b->b.completed&&b.kind.equals(kind)).findFirst().orElse(null);
    }
    /** The cell above the building's rod site, when it stands, is free (or already holds a rod) and may be built on. */
    private static BlockPos site(Village v,TownBuilding b){
        var plan=new ArrayList<BlockPos>();var solid=new HashSet<BlockPos>();
        for(var p:b.blocks){plan.add(p.position());if(!p.optional&&p.block().blocksMotion())solid.add(p.position());}
        BlockPos top=rodSite(plan,solid::contains);
        if(top==null)return null;
        BlockPos rod=top.above();
        if(!v.level.hasChunkAt(rod)||!v.level.getBlockState(top).blocksMotion())return null;
        if(!Construction.clear(v.level.getBlockState(rod))&&!isRod(v,rod))return null;
        if(v.town.protects(rod)||!v.state.policy.allows("build",rod)||!v.canWork(rod))return null;
        return rod;
    }
    /**
     * The cell the rod stands on: one of the plan's topmost solid cells with nothing planned above it, on the
     * eaves (beside a column the plan leaves empty, so a builder reaches it from the ground) when there is
     * one, nearest the middle of that top layer; null for a plan without solid cells.
     */
    static BlockPos rodSite(Collection<BlockPos> plan,Predicate<BlockPos> solid){
        var cells=new HashSet<BlockPos>(plan);var columns=new HashSet<Long>();
        for(BlockPos p:plan)columns.add(BlockPos.asLong(p.getX(),0,p.getZ()));
        var top=plan.stream().filter(solid).mapToInt(BlockPos::getY).max();
        if(top.isEmpty())return null;
        var roof=plan.stream().filter(p->p.getY()==top.getAsInt()&&solid.test(p)).distinct().toList();
        double cx=roof.stream().mapToInt(BlockPos::getX).average().orElse(0),cz=roof.stream().mapToInt(BlockPos::getZ).average().orElse(0);
        var free=roof.stream().filter(p->!cells.contains(p.above())).toList();
        var eaves=free.stream().filter(p->Direction.Plane.HORIZONTAL.stream().anyMatch(d->!columns.contains(BlockPos.asLong(p.getX()+d.getStepX(),0,p.getZ()+d.getStepZ())))).toList();
        return (eaves.isEmpty()?free:eaves).stream().min(Comparator.comparingDouble((BlockPos p)->(p.getX()-cx)*(p.getX()-cx)+(p.getZ()-cz)*(p.getZ()-cz))
                .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ)).orElse(null);
    }
    static boolean isRod(Village v,BlockPos p){return v.level.hasChunkAt(p)&&v.level.getBlockState(p).getBlock() instanceof LightningRodBlock;}
    /** Remembers the rod; told in the chronicle when the village set it up itself. */
    static void rodPlaced(Village v,BlockPos rod,boolean tower,boolean ours){
        var st=Features.load(v,KEY,State.class,State::new);
        st.built=true;st.rod=rod.asLong();st.missingSince=-1;Features.store(v,KEY,st);
        var s=storm(v);s.rodSite=null;s.rodDelay=ROD_RETRY;
        if(!ours)return;
        v.chronicle.add("lightningRods",1);
        v.chronicle.announce(tower?"Над вышкой поставлен громоотвод":"Над амбаром поставлен громоотвод");
        v.level.sendParticles(ParticleTypes.ELECTRIC_SPARK,rod.getX()+.5,rod.getY()+.7,rod.getZ()+.5,24,.25,.5,.25,.05);
    }
}
