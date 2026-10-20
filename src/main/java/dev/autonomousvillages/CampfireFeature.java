package dev.autonomousvillages;

import java.util.*;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;

/**
 * Вечерние посиделки у костра на площади. Once the village has a store and beds for everyone, one
 * resident crafts a campfire from the store at a workbench (vanilla recipe) and sets it on open natural
 * ground 3-6 blocks from the bell. Every evening, until the night schedule sends people home, idle
 * adults stand round it in a ring, face the fire and chat; the first such evening goes into the chronicle.
 * Switch {@code campfire} in {@link ModConfig}, state in {@link VillageState#features}.
 */
final class CampfireFeature implements Features.Feature {
    static final String KEY="campfire";
    /** Clock hours: residents come to the fire from EVENING_START until LAST_JOIN and leave by EVENING_END, when the night rest of {@link Worker#nightRestWindow} begins. */
    static final int EVENING_START=10500,LAST_JOIN=11250,EVENING_END=11500;
    /** A fire that was broken may be rebuilt after a game day. */
    static final long REBUILD_DELAY=24_000;
    /** Standing cells round the fire, by horizontal distance; a site needs room for a small circle. */
    static final int RING_MIN=2,RING_MAX=4,MIN_RING_CELLS=6;
    static final int SITE_MIN=3,SITE_MAX=6;
    /** Logs the store keeps for the builders after the campfire took its own. */
    static final int LOG_RESERVE=8;
    /** Residents come to the fire from no further than this. */
    static final int GATHER_RANGE=48;
    static final String FIRST_EVENING="Вечером жители впервые собрались у костра на площади";

    @Override public String key(){return KEY;}
    @Override public boolean enabled(){return AutonomousVillagesMod.CONFIG.campfire;}

    /** Saved per village: where its fire is, when a broken one may be rebuilt, and whether its first evening was written down. */
    static final class State {
        public Long fire;
        public long rebuildAt;
        public boolean welcomed;
        public long lastEvening=-1;
        boolean mayBuild(long now){return fire==null&&now>=rebuildAt;}
        void built(BlockPos p){fire=p.asLong();welcomed=false;}
        void lost(long now){fire=null;rebuildAt=now+REBUILD_DELAY;}
    }
    private static final Map<Village,State> STATES=new WeakHashMap<>();
    /** No campfire job is offered before this game time (a failed search or a job just handed out). */
    private static final Map<Village,Long> NEXT_BUILD=new WeakHashMap<>();
    static State state(Village v){return STATES.computeIfAbsent(v,k->Features.load(k,KEY,State.class,State::new));}
    static void save(Village v){Features.store(v,KEY,state(v));}

    @Override public void tick(Village v,long time){
        if(time%200!=57)return;
        var s=state(v);
        if(s.fire==null)return;
        BlockPos p=BlockPos.of(s.fire);
        if(!v.level.hasChunkAt(p))return;
        if(!v.level.getBlockState(p).is(Blocks.CAMPFIRE)){s.lost(time);save(v);}
    }

    @Override public Job job(Village v,Worker w,ResidentRoles.Role role){
        if(v.alarm()||w.fleeing||w.combat!=null)return null;
        long now=v.level.getGameTime(),clock=v.level.getOverworldClockTime();
        var s=state(v);
        if(s.fire!=null)return evening(clock)?gather(v,w,s):null;
        if(!s.mayBuild(now)||now<NEXT_BUILD.getOrDefault(v,0L)||!buildHours(clock)||v.activeJobs("campfire")>0)return null;
        return build(v,w,now);
    }

    // --- building the fire ------------------------------------------------------------------------
    private Job build(Village v,Worker w,long now){
        NEXT_BUILD.put(v,now+1200);
        if(v.stock.chests().isEmpty()||homelessCritical(v.index.beds.size(),v.state.residents.size()))return null;
        var st=v.stock;
        int campfires=st.count(CampfireJob.CAMPFIRE_ITEM)+w.count(CampfireJob.CAMPFIRE_ITEM);
        if(!affordable(campfires,st.count(VillageCrafting.STICK)+w.count(VillageCrafting.STICK),st.count(VillageCrafting.PLANK)+w.count(VillageCrafting.PLANK),
                st.count(VillageCrafting.LOG)+w.count(VillageCrafting.LOG),st.count(VillageCrafting.FUEL)+w.count(VillageCrafting.FUEL),st.count(VillageCrafting.TORCH_ITEM)))return null;
        if(campfires==0&&v.index.tables.stream().noneMatch(p->v.level.hasChunkAt(p)&&v.level.getBlockState(p).is(Blocks.CRAFTING_TABLE)&&!Lighting.unreachable(v,p)))return null;
        BlockPos site=site(ground(v),v.meetingPoint());
        NEXT_BUILD.put(v,now+2400);
        return site==null?null:new CampfireJob(w,site);
    }
    /** A placed fire becomes the village's own; its first evening is still to come. */
    static void built(Village v,BlockPos p){state(v).built(p);save(v);v.chronicle.add("campfires",1);}
    /** Someone sleeps without a bed: no time for evenings by the fire. */
    static boolean homelessCritical(int beds,int residents){return beds<residents;}
    /** By day, so the fire stands before the evening; never in the night hours. */
    static boolean buildHours(long clock){long h=Math.floorMod(clock,24000);return h>=1000&&h<EVENING_START;}
    /** What one campfire takes when the store has no campfire ready: three sticks (or two planks for them, or a log more), three logs and a coal. */
    record Kit(int sticks,int planks,int logs,int fuel){}
    static Kit kit(int sticks,int planks){return sticks>=3?new Kit(3,0,3,1):planks>=2?new Kit(0,2,3,1):new Kit(0,0,4,1);}
    /** A ready campfire, or the materials with logs left for the builders and coal left for torches. */
    static boolean affordable(int campfires,int sticks,int planks,int logs,int fuel,int torches){
        if(campfires>0)return true;
        var k=kit(sticks,planks);
        return logs>=k.logs()+LOG_RESERVE&&(fuel>=2||fuel>=1&&torches>=8);
    }

    /** The block facts the site rules need, so the rules can be checked without a level. */
    interface Ground extends Lighting.Cells {
        /** Open sky above: the smoke rises freely and the cell is no room. */
        boolean sky(BlockPos p);
        /** A wall, fence, door, chest, workbench or other fixture a fire must not touch. */
        boolean wooden(BlockPos p);
        /** Air or plants a block may replace, without fluid. */
        boolean clear(BlockPos p);
        /** Inside the village, allowed by its policy for building and not given up as unreachable. */
        boolean allowed(BlockPos p);
        /** A safe cell for a resident to stand at. */
        boolean stand(BlockPos p);
    }
    static Ground ground(Village v){
        var cells=Lighting.cells(v);
        return new Ground(){
            @Override public int top(int x,int z){return cells.top(x,z);}
            @Override public boolean solid(BlockPos p){return cells.solid(p);}
            @Override public boolean ground(BlockPos p){return cells.ground(p);}
            @Override public boolean path(BlockPos p){return cells.path(p);}
            @Override public boolean planned(BlockPos p){return cells.planned(p);}
            @Override public Direction door(BlockPos p){return cells.door(p);}
            @Override public boolean sky(BlockPos p){return v.level.hasChunkAt(p)&&v.level.canSeeSky(p);}
            @Override public boolean wooden(BlockPos p){
                if(!v.level.hasChunkAt(p))return true;
                var s=v.level.getBlockState(p);
                return s.is(BlockTags.LOGS)||VillageIndex.crafted(s)||s.is(Blocks.CRAFTING_TABLE)||s.hasBlockEntity();
            }
            @Override public boolean clear(BlockPos p){return v.level.hasChunkAt(p)&&Construction.clear(v.level.getBlockState(p))&&v.level.getBlockState(p).getFluidState().isEmpty();}
            @Override public boolean allowed(BlockPos p){return v.state.contains(p)&&v.canWork(p)&&v.state.policy.allows("build",p)&&!Lighting.unreachable(v,p);}
            @Override public boolean stand(BlockPos p){return v.level.hasChunkAt(p)&&v.safeFeet(p);}
        };
    }
    /**
     * Open natural ground for the fire: not on a path, a plan cell or a roof, under open sky, clear of
     * doorway approaches (three blocks round a door) and not touching walls, fences or the store.
     */
    static boolean fits(Ground g,BlockPos p){
        if(!g.allowed(p)||!g.clear(p)||!g.sky(p)||!Lighting.groundSample(g,p)||g.path(p.below())||g.planned(p.above()))return false;
        for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++)for(int dy=-1;dy<=1;dy++)if(g.door(p.offset(dx,dy,dz))!=null)return false;
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)for(int dy=0;dy<=1;dy++)if((dx!=0||dz!=0)&&g.wooden(p.offset(dx,dy,dz)))return false;
        return true;
    }
    /** The best fire site 3-6 blocks from the hub: nearest to four blocks out, with room for a circle round it; null when there is none. */
    static BlockPos site(Ground g,BlockPos hub){
        var candidates=new ArrayList<BlockPos>();
        for(int dx=-SITE_MAX;dx<=SITE_MAX;dx++)for(int dz=-SITE_MAX;dz<=SITE_MAX;dz++){
            int d2=dx*dx+dz*dz;
            if(d2<SITE_MIN*SITE_MIN||d2>SITE_MAX*SITE_MAX)continue;
            int y=g.top(hub.getX()+dx,hub.getZ()+dz);
            if(y==Integer.MIN_VALUE||Math.abs(y-hub.getY())>3)continue;
            var p=new BlockPos(hub.getX()+dx,y,hub.getZ()+dz);
            if(fits(g,p))candidates.add(p);
        }
        candidates.sort(Comparator.comparingDouble((BlockPos p)->Math.abs(Math.sqrt(p.distSqr(new BlockPos(hub.getX(),p.getY(),hub.getZ())))-4))
                .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        for(var p:candidates)if(ring(p,g::stand).size()>=MIN_RING_CELLS)return p;
        return null;
    }

    // --- the evening -------------------------------------------------------------------------------
    /** Residents may set out for the fire. */
    static boolean evening(long clock){long h=Math.floorMod(clock,24000);return h>=EVENING_START&&h<LAST_JOIN;}
    /** The gathering lasts; at EVENING_END everyone leaves at once. */
    static boolean gathering(long clock){long h=Math.floorMod(clock,24000);return h>=EVENING_START&&h<EVENING_END;}
    /** At most all residents but one sit by the fire. */
    static int cap(int adults){return Math.max(0,adults-1);}
    /** Ticks a resident stays by the fire: 200-400, fixed per resident and evening. */
    static int linger(UUID id,long day){return 200+Math.floorMod(Objects.hash(id,day),201);}
    /**
     * Standing cells round the fire, 2-4 blocks out, one per column (level with the fire, a step up or
     * down), nearest first and then by angle so a circle forms evenly; blocked columns are left out.
     */
    static List<BlockPos> ring(BlockPos fire,Predicate<BlockPos> stand){
        var out=new ArrayList<BlockPos>();
        for(int dx=-RING_MAX;dx<=RING_MAX;dx++)for(int dz=-RING_MAX;dz<=RING_MAX;dz++){
            int d2=dx*dx+dz*dz;
            if(d2<RING_MIN*RING_MIN||d2>RING_MAX*RING_MAX)continue;
            for(int dy:new int[]{0,1,-1}){var p=fire.offset(dx,dy,dz);if(stand.test(p)){out.add(p);break;}}
        }
        out.sort(Comparator.comparingInt((BlockPos p)->(p.getX()-fire.getX())*(p.getX()-fire.getX())+(p.getZ()-fire.getZ())*(p.getZ()-fire.getZ()))
                .thenComparingDouble(p->Math.atan2(p.getZ()-fire.getZ(),p.getX()-fire.getX())));
        return out;
    }
    /** The first ring cell nobody else holds, or null. */
    static BlockPos assign(List<BlockPos> ring,Predicate<BlockPos> free){
        for(var p:ring)if(free.test(p))return p;
        return null;
    }
    /** A lit campfire stands at the remembered place. */
    static boolean lit(Village v,BlockPos fire){
        if(!v.level.hasChunkAt(fire))return false;
        var s=v.level.getBlockState(fire);
        return s.is(Blocks.CAMPFIRE)&&s.getValue(CampfireBlock.LIT);
    }
    /** The fire is in plain sight from the cell: no wall or fence between, nobody stands behind a house corner. */
    static boolean sees(Village v,BlockPos cell,BlockPos fire){
        var eye=v.feetPosition(cell).add(0,1.5,0);
        var hit=v.level.clip(new net.minecraft.world.level.ClipContext(eye,new net.minecraft.world.phys.Vec3(fire.getX()+.5,fire.getY()+.6,fire.getZ()+.5),
                net.minecraft.world.level.ClipContext.Block.COLLIDER,net.minecraft.world.level.ClipContext.Fluid.NONE,net.minecraft.world.phys.shapes.CollisionContext.empty()));
        return hit.getType()==net.minecraft.world.phys.HitResult.Type.MISS||hit.getBlockPos().equals(fire);
    }
    /** Nobody sits out in the rain or a thunderstorm. */
    static boolean wet(Village v,BlockPos fire){return v.level.isThundering()||v.level.isRainingAt(fire.above());}
    private Job gather(Village v,Worker w,State s){
        if(!w.ready("fireside")||v.activeJobs("fireside")>=cap(v.workers.size()))return null;
        BlockPos fire=BlockPos.of(s.fire);
        if(w.body().blockPosition().distSqr(fire)>GATHER_RANGE*GATHER_RANGE||!lit(v,fire)||wet(v,fire))return null;
        // An armed guard keeps watch over the village instead.
        if(v.guard(w.id)&&w.armed())return null;
        BlockPos cell=assign(ring(fire,p->v.level.hasChunkAt(p)&&v.safeFeet(p)&&sees(v,p,fire)),p->v.ready(p,w.id));
        if(cell==null){w.cooldown("fireside",200);return null;}
        // Once an evening: whoever has been to the fire goes back to work.
        w.cooldown("fireside",2000);
        return new CampfireJob.Evening(w,fire,cell,linger(w.id,Math.floorDiv(v.level.getOverworldClockTime(),24000)));
    }
    /** The first resident at the fire this evening: one chronicle line for the first evening at this fire, a tally for the others. */
    static void arrived(Village v){
        var s=state(v);long day=Math.floorDiv(v.level.getOverworldClockTime(),24000);
        if(s.lastEvening==day)return;
        s.lastEvening=day;v.chronicle.add("campfireEvenings",1);
        if(!s.welcomed){s.welcomed=true;v.chronicle.event(FIRST_EVENING);v.chronicle.tell(FIRST_EVENING);}
        save(v);
    }
}
