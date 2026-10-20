package dev.autonomousvillages;

import java.util.*;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/**
 * Фруктовый сад: oaks in a 3×3 grid, four blocks apart, on open grass at the edge of the village. Oak
 * saplings beyond the replanting reserve go there (fetched from the store and placed by a farmer or a
 * logger); once a day a farmer walks it, spreads spare bone meal from the store on the saplings and
 * carries the apples and other leaf drops lying under the trees to the store. Apples are meals
 * ({@link Food}). Its trees are listed in the feature state so felling can leave them be ({@link #isOrchardTree}).
 * Switch {@code orchard} in {@link ModConfig}, state in {@link VillageState#features}.
 */
final class OrchardFeature implements Features.Feature {
    static final String KIND="orchard";
    /** The planted square, a tree every SPACING blocks of it, and the MARGIN its crowns hang over. */
    static final int SIZE=9,SPACING=4,ROWS=3,MARGIN=2;
    /** The planted square may rise or fall this much; the corner this much from the bell's height. */
    static final int FLAT=2,MAX_RISE=12;
    /** The site's centre stays this far from the bell, and its crowns this close to the edge of the village. */
    static final int MIN_HUB=14,MAX_HUB=64,EDGE_BAND=16;
    /** Oak saplings beyond the reserve before a site is chosen; bone meal the store keeps for the fields. */
    static final int MIN_SAPLINGS=4,BONE_MEAL_RESERVE=2,MEAL_PER_DAY=6;
    /** Columns one site search may look at; a cemetery search looks at about as many. */
    static final int SEARCH_BUDGET=16_000;
    /** What counts as part of an orchard tree around its trunk: crown and branches. */
    static final int CROWN=3,TREE_HEIGHT=14;
    static final long LOOK_EVERY=200,TRIP_GAP=1200,COOLDOWN=2400,SEARCH_COOLDOWN=6000;
    /** Working hours of the orchard on the overworld clock: after the morning, before the evening gathering. */
    static final int FROM_HOUR=1000,TO_HOUR=9000;
    // Shared instances: VillageStock caches its counts per predicate instance.
    static final Predicate<ItemStack> OAK=s->s.is(Items.OAK_SAPLING);
    static final Predicate<ItemStack> SAPLINGS=s->s.is(ItemTags.SAPLINGS);
    static final Predicate<ItemStack> BONE_MEAL=CompostJob.BONE_MEAL;
    /** What oak leaves drop: picked up under the orchard trees. */
    static final Predicate<ItemStack> LEAF_DROP=s->s.is(Items.APPLE)||s.is(Items.OAK_SAPLING)||s.is(Items.STICK);

    /** Saved in the feature slot as JSON. */
    static final class State {
        /** The planted square's corner (lowest x and z) on the ground, packed; null until a site is chosen. */
        public Long site;
        /** Where the orchard's saplings were planted: the trunks of its trees. */
        public List<Long> trees=new ArrayList<>();
        public long careDay=-1,nextSearch,apples;
        public boolean announced,firstApple;
        State sane(){if(trees==null)trees=new ArrayList<>();trees.removeIf(Objects::isNull);while(trees.size()>ROWS*ROWS)trees.removeFirst();return this;}
    }
    private static final Map<Village,State> STATES=new WeakHashMap<>();
    private static final Map<String,Long> LOOKS=new HashMap<>(),TRIPS=new HashMap<>();

    @Override public String key(){return KIND;}
    @Override public boolean enabled(){return AutonomousVillagesMod.CONFIG.orchard;}

    static State state(Village v){return STATES.computeIfAbsent(v,k->Features.load(k,KIND,State.class,State::new).sane());}
    static void save(Village v,State s){Features.store(v,KIND,s);}

    @Override public void tick(Village v,long time){
        if(time%200!=97)return;
        var s=state(v);boolean changed=false;
        // A sapling someone took or a tree felled anyway leaves its cell free for a new one.
        changed|=s.trees.removeIf(p->{BlockPos q=BlockPos.of(p);return v.level.hasChunkAt(q)&&!(v.level.getBlockState(q).getBlock() instanceof SaplingBlock)&&!v.level.getBlockState(q).is(BlockTags.LOGS);});
        if(!s.announced&&!s.trees.isEmpty()&&v.activeJobs(KIND)==0){
            s.announced=changed=true;
            v.chronicle.announce("Посажен яблоневый сад: "+MorningNews.plural(s.trees.size(),"дерево","дерева","деревьев"));
            if(AutonomousVillagesMod.CONFIG.ambience&&s.site!=null){BlockPos c=centre(s);v.level.sendParticles(ParticleTypes.HAPPY_VILLAGER,c.getX()+.5,c.getY()+1,c.getZ()+.5,30,4,1,4,0);}
        }
        if(!s.firstApple&&s.apples>0){s.firstApple=changed=true;v.chronicle.announce("В яблоневом саду собрали первое яблоко");}
        if(changed)save(v,s);
    }

    @Override public Job job(Village v,Worker w,ResidentRoles.Role role){
        if(role!=null&&role!=ResidentRoles.Role.FARMER&&role!=ResidentRoles.Role.LOGGER)return null;
        if(!w.ready(KIND)||v.activeJobs(KIND)>0||v.alarm())return null;
        long clock=v.level.getOverworldClockTime(),now=v.level.getGameTime();
        if(!workingHours(Math.floorMod(clock,24000))||Features.throttled(LOOKS,v.state.name,now,LOOK_EVERY))return null;
        var s=state(v);
        long day=Math.floorDiv(clock,24000);
        if(s.site!=null&&!s.trees.isEmpty()&&careDue(s.careDay,day)&&carer(v,role)){
            s.careDay=day;save(v,s);
            var saplings=s.trees.stream().map(BlockPos::of).filter(p->young(v,p)).toList();
            int meal=saplings.isEmpty()?0:spareMeal(v.stock.count(BONE_MEAL));
            w.cooldown(KIND,COOLDOWN);
            return OrchardJob.care(w,walkway(s),area(s),saplings,meal);
        }
        return plant(v,w,s,now);
    }
    private Job plant(Village v,Worker w,State s,long now){
        int spare=spareSaplings(v.stock.count(OAK),v.stock.count(SAPLINGS));
        if(spare<=0)return null;
        if(s.site==null){
            if(spare<MIN_SAPLINGS||now<s.nextSearch&&s.nextSearch-now<=SEARCH_COOLDOWN)return null;
            BlockPos site=chooseSite(v);
            if(site==null){s.nextSearch=now+SEARCH_COOLDOWN;save(v,s);return null;}
            s.site=site.asLong();save(v,s);
        }
        var open=new ArrayList<BlockPos>();boolean later=false;
        for(BlockPos cell:grid(BlockPos.of(s.site))){
            if(s.trees.stream().anyMatch(t->BlockPos.getX(t)==cell.getX()&&BlockPos.getZ(t)==cell.getZ()))continue;
            BlockPos column=new BlockPos(cell.getX(),v.state.y,cell.getZ());
            if(!v.level.hasChunkAt(column)){later=true;continue;}
            BlockPos p=new BlockPos(cell.getX(),v.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,cell.getX(),cell.getZ()),cell.getZ());
            if(!v.ready(p,w.id)){later=true;continue;}
            if(plantable(v,p))open.add(p);
        }
        if(open.isEmpty()){
            // Nothing grows there and nothing can: built over or flooded, so another site is chosen later.
            if(s.trees.isEmpty()&&!later){s.site=null;s.nextSearch=now+SEARCH_COOLDOWN;save(v,s);}
            return null;
        }
        if(Features.throttled(TRIPS,v.state.name,now,TRIP_GAP))return null;
        w.cooldown(KIND,COOLDOWN);
        return OrchardJob.plant(w,open.subList(0,Math.min(spare,open.size())));
    }

    // --- what the jobs report ----------------------------------------------------------------------
    static void planted(Village v,BlockPos p){
        var s=state(v);
        if(!s.trees.contains(p.asLong())&&s.trees.size()<ROWS*ROWS){s.trees.add(p.asLong());save(v,s);}
        v.chronicle.add("orchardTrees",1);
    }
    static void gathered(Village v,int apples){
        if(apples<=0)return;
        var s=state(v);s.apples+=apples;save(v,s);
        v.chronicle.add("apples",apples);
    }

    /** True for a block of one of the orchard's trees (trunk, branches, crown): loggers leave these standing. */
    static boolean isOrchardTree(Village v,BlockPos pos){
        if(v==null||pos==null||AutonomousVillagesMod.CONFIG==null||!AutonomousVillagesMod.CONFIG.orchard)return false;
        var s=state(v);
        return !s.trees.isEmpty()&&nearTrunk(s.trees,pos);
    }
    static boolean nearTrunk(Collection<Long> trunks,BlockPos p){
        for(long t:trunks){
            int dy=p.getY()-BlockPos.getY(t);
            if(Math.abs(p.getX()-BlockPos.getX(t))<=CROWN&&Math.abs(p.getZ()-BlockPos.getZ(t))<=CROWN&&dy>=-1&&dy<=TREE_HEIGHT)return true;
        }
        return false;
    }

    // --- pure rules ------------------------------------------------------------------------------
    /** Oak saplings the orchard may take: those beyond the store's replanting reserve of saplings of any kind. */
    static int spareSaplings(int oak,int allSaplings){return Math.max(0,Math.min(oak,allSaplings-CompostJob.SAPLING_RESERVE));}
    /** Bone meal for one round: what the store holds beyond a little for the fields, a few at most. */
    static int spareMeal(int stored){return Math.clamp(stored-BONE_MEAL_RESERVE,0,MEAL_PER_DAY);}
    /** A round is due on any other day than the last one (a clock set back counts as another day). */
    static boolean careDue(long lastDay,long day){return day!=lastDay;}
    static boolean workingHours(long hour){return hour>=FROM_HOUR&&hour<TO_HOUR;}
    /** The farmers walk the orchard; a logger only where there is no farmer, and anyone in a small crew. */
    static boolean carer(Village v,ResidentRoles.Role role){
        return role==null||role==ResidentRoles.Role.FARMER||role==ResidentRoles.Role.LOGGER&&v.workers.keySet().stream().noneMatch(id->v.primaryRole(id)==ResidentRoles.Role.FARMER);
    }
    /** The tree cells of a site, row by row: ROWS×ROWS, SPACING apart, inside the SIZE square. */
    static List<BlockPos> grid(BlockPos corner){
        var out=new ArrayList<BlockPos>(ROWS*ROWS);
        for(int row=0;row<ROWS;row++)for(int col=0;col<ROWS;col++)out.add(corner.offset(col*SPACING,0,row*SPACING));
        return out;
    }
    static BlockPos centre(State s){return BlockPos.of(s.site).offset(SIZE/2,0,SIZE/2);}
    /** Open ground between the trees where the round starts. */
    static BlockPos walkway(State s){return BlockPos.of(s.site).offset(SPACING/2,0,SPACING/2);}
    static AABB area(State s){
        BlockPos c=BlockPos.of(s.site);
        return new AABB(c.getX()-MARGIN,c.getY()-4,c.getZ()-MARGIN,c.getX()+SIZE+MARGIN,c.getY()+TREE_HEIGHT,c.getZ()+SIZE+MARGIN);
    }

    /** The block facts a site needs, so the rules can be checked without a level. */
    interface Ground {
        /** The first open cell of a column, or Integer.MIN_VALUE where it is not loaded. */
        int top(int x,int z);
        /** Natural grass or dirt under the open cell: no paving, block or structure stands there. */
        boolean ground(BlockPos cell);
        /** A sapling can be planted in that cell now: clear, open sky, natural soil under it. */
        boolean plantable(BlockPos cell);
        /** A path, field, doorway, water, plan or no-felling zone there: no tree on it and no crown over it. */
        boolean busy(BlockPos cell);
    }
    /** Column facts remembered for one search: neighbouring candidates overlap almost entirely. */
    static final class Survey {
        final Ground g;
        private final Map<Long,Integer> tops=new HashMap<>();
        private final Map<Long,Boolean> ground=new HashMap<>(),plantable=new HashMap<>(),busy=new HashMap<>();
        Survey(Ground g){this.g=g;}
        int top(int x,int z){return tops.computeIfAbsent(BlockPos.asLong(x,0,z),k->g.top(x,z));}
        boolean ground(BlockPos p){return ground.computeIfAbsent(p.asLong(),k->g.ground(p));}
        boolean plantable(BlockPos p){return plantable.computeIfAbsent(p.asLong(),k->g.plantable(p));}
        boolean busy(BlockPos p){return busy.computeIfAbsent(p.asLong(),k->g.busy(p));}
        int columns(){return tops.size();}
    }
    /** Corners worth a look, best first: the whole site with its crowns inside the bounds, near the edge, not on the plaza. */
    static List<BlockPos> candidates(int minX,int minZ,int maxX,int maxZ,BlockPos hub){
        var out=new ArrayList<BlockPos>();
        int fromX=Math.max(minX+MARGIN,hub.getX()-MAX_HUB-SIZE/2),toX=Math.min(maxX-MARGIN-SIZE+1,hub.getX()+MAX_HUB-SIZE/2);
        int fromZ=Math.max(minZ+MARGIN,hub.getZ()-MAX_HUB-SIZE/2),toZ=Math.min(maxZ-MARGIN-SIZE+1,hub.getZ()+MAX_HUB-SIZE/2);
        for(int x=fromX;x<=toX;x+=2)for(int z=fromZ;z<=toZ;z+=2){
            double d=Math.hypot(x+SIZE/2-hub.getX(),z+SIZE/2-hub.getZ());
            if(d>=MIN_HUB&&d<=MAX_HUB&&edgeGap(x,z,minX,minZ,maxX,maxZ)<=EDGE_BAND)out.add(new BlockPos(x,hub.getY(),z));
        }
        out.sort(Comparator.comparingDouble((BlockPos p)->score(p,minX,minZ,maxX,maxZ,hub)).thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
        return out;
    }
    /** Blocks between the crowns of a site at this corner and the nearest edge of the village. */
    static int edgeGap(int x0,int z0,int minX,int minZ,int maxX,int maxZ){
        return Math.min(Math.min(x0-MARGIN-minX,maxX-(x0+SIZE-1+MARGIN)),Math.min(z0-MARGIN-minZ,maxZ-(z0+SIZE-1+MARGIN)));
    }
    static double score(BlockPos corner,int minX,int minZ,int maxX,int maxZ,BlockPos hub){
        return edgeGap(corner.getX(),corner.getZ(),minX,minZ,maxX,maxZ)*2+Math.hypot(corner.getX()+SIZE/2-hub.getX(),corner.getZ()+SIZE/2-hub.getZ())/4;
    }
    /**
     * The ground height of a site at this corner, or null: every tree cell plantable, the whole square
     * natural ground within {@link #FLAT} of the corner, and nothing busy under the square or its crowns.
     */
    static Integer fits(Survey s,int x0,int z0,int hubY){
        int y0=s.top(x0,z0);
        if(y0==Integer.MIN_VALUE||Math.abs(y0-hubY)>MAX_RISE)return null;
        for(BlockPos t:grid(new BlockPos(x0,y0,z0))){
            int y=s.top(t.getX(),t.getZ());
            if(y==Integer.MIN_VALUE||Math.abs(y-y0)>FLAT)return null;
            BlockPos cell=new BlockPos(t.getX(),y,t.getZ());
            if(!s.plantable(cell)||s.busy(cell))return null;
        }
        for(int dx=-MARGIN;dx<SIZE+MARGIN;dx++)for(int dz=-MARGIN;dz<SIZE+MARGIN;dz++){
            int x=x0+dx,z=z0+dz,y=s.top(x,z);
            if(y==Integer.MIN_VALUE)return null;
            BlockPos cell=new BlockPos(x,y,z);
            boolean inner=dx>=0&&dx<SIZE&&dz>=0&&dz<SIZE;
            if(inner&&(Math.abs(y-y0)>FLAT||!s.ground(cell))||s.busy(cell))return null;
        }
        return y0;
    }
    /** The best site in the bounds, or null when none fits or the search looked at {@code budget} columns. */
    static BlockPos chooseSite(int minX,int minZ,int maxX,int maxZ,BlockPos hub,Ground g,int budget){
        var survey=new Survey(g);
        for(BlockPos c:candidates(minX,minZ,maxX,maxZ,hub)){
            if(survey.columns()>budget)return null;
            Integer y=fits(survey,c.getX(),c.getZ(),hub.getY());
            if(y!=null)return new BlockPos(c.getX(),y,c.getZ());
        }
        return null;
    }
    static BlockPos chooseSite(Village v){
        var b=v.state.bounds;
        int minX=b==null?v.state.x-v.state.radius:b.minX,maxX=b==null?v.state.x+v.state.radius:b.maxX;
        int minZ=b==null?v.state.z-v.state.radius:b.minZ,maxZ=b==null?v.state.z+v.state.radius:b.maxZ;
        return chooseSite(minX,minZ,maxX,maxZ,v.meetingPoint(),ground(v),SEARCH_BUDGET);
    }

    // --- the world ---------------------------------------------------------------------------------
    static boolean young(Village v,BlockPos p){return v.level.hasChunkAt(p)&&v.level.getBlockState(p).getBlock() instanceof SaplingBlock;}
    /** An oak sapling may go into this cell now: inside the village, clear, open sky, grass or dirt under it, no plan or zone against it. */
    static boolean plantable(Village v,BlockPos cell){
        if(!v.level.hasChunkAt(cell)||!v.state.contains(cell)||v.town.protects(cell)||v.town.protects(cell.below())||!v.state.policy.allows("wood",cell))return false;
        var s=v.level.getBlockState(cell);
        return Construction.clear(s)&&s.getFluidState().isEmpty()&&VillageIndex.naturalSoil(v.level.getBlockState(cell.below()))&&v.level.canSeeSky(cell)
                &&Blocks.OAK_SAPLING.defaultBlockState().canSurvive(v.level,cell);
    }
    static Ground ground(Village v){
        // Footpaths and doorways by column: a path's ground cell may sit a block off the surface the search sees.
        var paths=new HashSet<Long>();
        for(var b:v.state.buildings)if(b.path!=null)for(Long p:b.path)if(p!=null)paths.add(BlockPos.asLong(BlockPos.getX(p),0,BlockPos.getZ(p)));
        var doors=new HashSet<Long>();
        for(BlockPos d:v.town.doors())for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)doors.add(BlockPos.asLong(d.getX()+dx,0,d.getZ()+dz));
        BlockPos cemetery=v.state.cemetery==null?null:BlockPos.of(v.state.cemetery);
        var level=v.level;
        return new Ground(){
            @Override public int top(int x,int z){return level.hasChunkAt(new BlockPos(x,v.state.y,z))?level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z):Integer.MIN_VALUE;}
            @Override public boolean ground(BlockPos cell){return level.getBlockState(cell).getFluidState().isEmpty()&&VillageIndex.naturalSoil(level.getBlockState(cell.below()));}
            @Override public boolean plantable(BlockPos cell){return OrchardFeature.plantable(v,cell);}
            @Override public boolean busy(BlockPos cell){
                BlockPos under=cell.below();long column=BlockPos.asLong(cell.getX(),0,cell.getZ());
                if(paths.contains(column)||doors.contains(column)||v.town.protects(cell)||v.town.protects(under)||v.town.protects(cell.above()))return true;
                if(v.index.crops.contains(cell)||v.index.soil.contains(cell)||v.index.farmSites.contains(cell)||!v.state.policy.allows("wood",cell))return true;
                if(cemetery!=null&&cell.getX()>=cemetery.getX()-1&&cell.getX()<=cemetery.getX()+7&&cell.getZ()>=cemetery.getZ()-1&&cell.getZ()<=cemetery.getZ()+7)return true;
                var s=level.getBlockState(cell);var g=level.getBlockState(under);
                return g.is(Blocks.DIRT_PATH)||g.is(Blocks.FARMLAND)||s.getBlock() instanceof CropBlock||!s.getFluidState().isEmpty()||!g.getFluidState().isEmpty()
                        ||s.getBlock() instanceof DoorBlock||s.getBlock() instanceof BedBlock||VillageIndex.hazard(s)||VillageIndex.hazard(g);
            }
        };
    }
}
