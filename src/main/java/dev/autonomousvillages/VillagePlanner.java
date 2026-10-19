package dev.autonomousvillages;

import java.util.*;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The single place that decides what a worker does next. It looks at the store, the active
 * project and the role, in a fixed order of urgency. No orders are kept between decisions.
 */
final class VillagePlanner {
    static final int WOOD_RESERVE=64,STONE_RESERVE=48;
    final Village v;
    private long refreshed=Long.MIN_VALUE;
    Snapshot s;
    VillagePlanner(Village v){this.v=v;}

    /** Store totals and project needs, refreshed at most every 40 ticks. */
    record Snapshot(int planks,int logs,int sticks,int cobble,int rubble,int bread,int wheat,int seeds,int doors,int bedItems,int torches,int fuel,int iron,int rawIron,
                    int freeSlots,int chests,Item woolColour,int wool,boolean shears,TownBuilding project,Map<String,Integer> missing,List<BlockPos> dark,
                    BlockPos table,BlockPos furnace,boolean furnaceOutput){
        int wood(){return planks+logs*4;}
        int need(String key){return missing.getOrDefault(key,0);}
    }
    Snapshot snapshot(){
        long now=v.level.getGameTime();
        if(s!=null&&now-refreshed<40)return s;
        refreshed=now;var st=v.stock;
        Item colour=null;int wool=0;
        for(var c:st.chests())for(int i=0;i<c.getContainerSize();i++){var item=c.getItem(i);if(item.is(ItemTags.WOOL)){int n=st.count(x->x.is(item.getItem()));if(n>wool){wool=n;colour=item.getItem();}}}
        var project=v.town.active();
        var missing=new TreeMap<String,Integer>();
        for(var b:v.town.unfinished())v.town.missing(b).forEach((k,n)->missing.merge(k,n,Integer::sum));
        boolean night=v.level.isDarkOutside()||Math.floorMod(v.level.getOverworldClockTime(),24000)>9000;
        var dark=new ArrayList<BlockPos>();
        if(v.state.lights.size()<160&&(night||now%1200<600)){
            if(AutonomousVillagesMod.CONFIG.lightHomes)dark.addAll(Lighting.darkRooms(v,4));
            for(var p:Lighting.darkSpots(v,8))if(dark.size()<8)dark.add(p);
        }
        BlockPos hub=v.meetingPoint();
        // The workbench nearest the bell that a route reaches; none such and the village makes its own by the bell.
        BlockPos table=v.index.tables.stream().filter(p->v.level.getBlockState(p).is(Blocks.CRAFTING_TABLE)&&p.distSqr(hub)<32*32&&v.rejected.getOrDefault(p.asLong(),0L)<=now)
                .min(Comparator.comparingDouble(p->p.distSqr(hub))).orElse(null);
        BlockPos furnace=null;boolean output=false;
        for(long packed:v.state.furnaces)if(v.level.getBlockEntity(BlockPos.of(packed)) instanceof FurnaceBlockEntity f&&!f.isLocked()){
            if(furnace==null)furnace=f.getBlockPos();if(!f.getItem(2).isEmpty()){output=true;furnace=f.getBlockPos();}
        }
        boolean shears=st.count(x->x.is(Items.SHEARS)&&Supplies.usable(x))>0||v.workers.values().stream().anyMatch(w->w.active()&&w.has(x->x.is(Items.SHEARS)&&Supplies.usable(x)));
        s=new Snapshot(st.count(VillageCrafting.PLANK),st.count(VillageCrafting.LOG),st.count(VillageCrafting.STICK),st.count(VillageCrafting.COBBLE),
                st.count(VillageCrafting.RUBBLE),st.count(x->x.is(Items.BREAD)),st.count(VillageCrafting.WHEAT),st.count(x->x.is(Items.WHEAT_SEEDS)||x.is(Items.BEETROOT_SEEDS)),
                st.count(x->x.is(ItemTags.WOODEN_DOORS)),st.count(x->x.is(ItemTags.BEDS)),st.count(x->x.is(Items.TORCH)),st.count(VillageCrafting.FUEL),
                st.count(VillageCrafting.IRON),st.count(x->x.is(Items.RAW_IRON)),st.freeSlots(),st.chests().size(),colour,wool,shears,project,missing,dark,table,furnace,output);
        return s;
    }

    Job next(Worker w){
        var s=snapshot();
        boolean small=v.smallCrew();
        var role=small?null:w.role();
        // 1. Unload a heavy backpack.
        // Gathered goods only count once they are in the store, so even a modest load is carried home.
        boolean hauling=Set.of("chop","quarry","shaft","shear").contains(w.lastJob)&&w.surplusItems()>=4;
        if((hauling||w.surplusItems()>=16||w.freeSlots()<=4&&w.hasSurplus())&&s.chests()>0&&s.freeSlots()>0&&w.ready("deposit"))return new DepositJob(w);
        // 2. The store itself: a workbench and a chest at the meeting point, more chests when full.
        Job store=storeJob(w,s);if(store!=null)return store;
        // 3. Own tool for the role.
        Job tool=toolJob(w,s,role);if(tool!=null)return tool;
        // 4. Evening lights around homes.
        if((role==ResidentRoles.Role.GUARD||small||Math.floorMod(v.level.getOverworldClockTime(),24000)>8000)&&!s.dark().isEmpty()&&s.torches()+w.count(x->x.is(Items.TORCH))>0
                &&v.activeJobs("light")==0&&w.ready("light"))return new LightJob(w,s.dark());
        // 4b. Powder snow in the village freezes children and anyone walking home in the dark.
        if(!v.index.hazards.isEmpty()&&v.activeJobs("hazard")==0&&w.ready("hazard")){
            BlockPos from=w.body().blockPosition();
            // Drifts along paths and doorways first: that is where people slip in.
            var cells=v.index.hazards.stream().filter(p->v.ready(p,w.id)&&v.state.contains(p)).sorted(Comparator.comparingDouble(p->p.distSqr(from))).limit(48)
                    .sorted(Comparator.comparing(p->!besideWalkway(p))).limit(12).toList();
            if(!cells.isEmpty())return new HazardJob(w,cells);
        }
        // 4c. A visiting player gets a loaf from a well-stocked store, once a day.
        if(AutonomousVillagesMod.CONFIG.gifts&&s.bread()>=24&&v.activeJobs("gift")==0&&w.ready("gift")){
            var guest=GiftJob.guest(v);
            if(guest!=null&&guest.blockPosition().distSqr(w.body().blockPosition())<32*32){
                v.gifted.put(guest.getUUID(),v.level.getGameTime()+1200);w.cooldown("gift",1200);
                return new GiftJob(w,guest);
            }
        }
        // 5. The role's own work, then shared priorities.
        Job own=role==null?null:switch(role){
            case BUILDER->first(()->build(w,s),()->craftForProject(w,s),()->chop(w,s,true));
            case STEWARD->first(()->craftForProject(w,s),()->workshop(w,s),()->build(w,s));
            case LOGGER->chop(w,s,false);
            case MINER->first(()->mine(w,s,false),()->sand(w,s));
            case FARMER->first(()->farm(w),()->bread(w,s),()->compost(w),()->shear(w,s),()->herd(w,s));
            case GUARD->chop(w,s,true);
            default->null;
        };
        if(own!=null)return own;
        Job shared=first(()->build(w,s),()->craftForProject(w,s),()->workshop(w,s),()->shear(w,s),()->chop(w,s,true),
                ()->mine(w,s,true),()->sand(w,s),()->farm(w),()->bread(w,s),()->pave(w,s));
        if(shared==null&&w.hasSurplus()&&s.chests()>0&&s.freeSlots()>0&&w.ready("deposit"))return new DepositJob(w);
        return shared;
    }
    String idleReason(Worker w){
        var s=snapshot();
        if(s.chests()==0)return "ждёт первый общий сундук";
        if(s.project()!=null)return "стройке не хватает: "+String.join(", ",s.missing().keySet());
        return "свободен; запасы в норме";
    }
    @SafeVarargs private static Job first(java.util.function.Supplier<Job>... options){for(var o:options){var job=o.get();if(job!=null)return job;}return null;}

    // --- store ----------------------------------------------------------------------------------
    private Job storeJob(Worker w,Snapshot s){
        // Chests found in houses are shared too, but the store itself stands in the open by the bell.
        BlockPos hub=v.meetingPoint();
        // Every shared chest counts here, reachable at the moment or not: a chest no route reaches for a
        // while is no reason to make another one out of scarce wood.
        var all=v.chests();int allFree=0;for(var c:all)for(int i=0;i<c.getContainerSize();i++)if(c.getItem(i).isEmpty())allFree++;
        boolean noChest=all.stream().noneMatch(c->c.getBlockPos().distSqr(hub)<=10*10),full=!all.isEmpty()&&allFree<9&&all.size()<48;
        if(s.table()==null&&!busy(w,VillageCrafting.TABLE,Blocks.CRAFTING_TABLE)){
            if(w.has(x->x.is(Items.CRAFTING_TABLE))&&w.ready("place")){BlockPos spot=utilitySpot();if(spot!=null)return new PlaceJob(w,spot,Blocks.CRAFTING_TABLE.defaultBlockState(),"верстак");}
            if(w.ready("craft")&&CraftJob.affordable(v,VillageCrafting.TABLE,1,w))return new CraftJob(w,VillageCrafting.TABLE,1);
            if(noChest)return chop(w,s,true);
        }
        if((noChest||full)&&!busy(w,VillageCrafting.CHEST,Blocks.CHEST)){
            if(w.has(x->x.is(Items.CHEST))&&w.ready("place")){BlockPos spot=utilitySpot();if(spot!=null)return new PlaceJob(w,spot,Blocks.CHEST.defaultBlockState(),"сундук");}
            if(s.table()!=null&&w.ready("craft")&&CraftJob.affordable(v,VillageCrafting.CHEST,1,w))return new CraftJob(w,VillageCrafting.CHEST,1);
            if(noChest&&s.chests()==0)return chop(w,s,true);
        }
        if(s.furnace()==null&&s.cobble()>=8&&s.table()!=null&&!busy(w,VillageCrafting.FURNACE,Blocks.FURNACE)){
            if(w.has(x->x.is(Items.FURNACE))&&w.ready("place")){BlockPos spot=utilitySpot();if(spot!=null)return new PlaceJob(w,spot,Blocks.FURNACE.defaultBlockState(),"печь");}
            if(v.stock.count(x->x.is(Items.FURNACE))>0&&w.ready("fetch"))return new FetchJob(w,new Job.Need(x->x.is(Items.FURNACE),1,"печь"));
            if(w.ready("craft")&&CraftJob.affordable(v,VillageCrafting.FURNACE,1,w))return new CraftJob(w,VillageCrafting.FURNACE,1);
        }
        // A notice board by the bell tells players what the building site is waiting for.
        if(s.chests()>0&&s.table()!=null&&v.noticeBoard()==null&&!busy(w,VillageCrafting.SIGN,Blocks.OAK_SIGN)&&!boardCarried(w)&&w.ready("board")){
            int slot=Supplies.find(w.bot(),x->x.is(ItemTags.SIGNS));
            if(slot>=0&&w.bag().get(slot).getItem() instanceof net.minecraft.world.item.BlockItem sign){
                BlockPos spot=utilitySpot();if(spot!=null)return new PlaceJob(w,spot,sign.getBlock().defaultBlockState(),"табличка");
            }
            // One craft makes three signs: take one from the store or a neighbour's haul before making more.
            if(v.stock.count(x->x.is(ItemTags.SIGNS))>0)return new FetchJob(w,new Job.Need(x->x.is(ItemTags.SIGNS),1,"табличка"));
            if(owned(x->x.is(ItemTags.SIGNS))==0&&s.planks()+s.logs()*4>=12&&CraftJob.affordable(v,VillageCrafting.SIGN,1,w)){w.cooldown("board",600);return new CraftJob(w,VillageCrafting.SIGN,1);}
        }
        return null;
    }
    private boolean besideWalkway(BlockPos p){
        for(Direction d:Direction.Plane.HORIZONTAL)for(int dy=-1;dy<=1;dy++){
            var s=v.level.getBlockState(p.relative(d).above(dy));
            if(s.is(Blocks.DIRT_PATH)||s.getBlock() instanceof DoorBlock||s.getBlock() instanceof BedBlock||s.is(Blocks.SNOW_BLOCK))return true;
        }
        return false;
    }
    /** One resident puts up the board: nobody else fetches or makes a sign while another carries one. */
    private boolean boardCarried(Worker w){
        return v.workers.values().stream().anyMatch(o->o!=w&&(o.has(x->x.is(ItemTags.SIGNS))||o.job instanceof FetchJob f&&f.status.endsWith("табличка")));
    }
    /** Another worker is already making or placing this store block. */
    private boolean busy(Worker w,VillageCrafting.Recipe recipe,Block block){
        return v.workers.values().stream().anyMatch(o->o!=w&&(o.job instanceof CraftJob c&&c.recipe.name().equals(recipe.name())
                ||o.job instanceof PlaceJob p&&(p.state.is(block)||block==Blocks.OAK_SIGN&&p.state.is(net.minecraft.tags.BlockTags.STANDING_SIGNS))));
    }
    /** Free floor next to the meeting point for store blocks; existing chests and the workbench attract new ones. */
    BlockPos utilitySpot(){
        BlockPos hub=v.meetingPoint();BlockPos best=null;double score=Double.MAX_VALUE;
        for(BlockPos q:BlockPos.betweenClosed(hub.offset(-6,-2,-6),hub.offset(6,2,6))){
            BlockPos p=q.immutable();
            if(!v.state.contains(p)||!v.level.hasChunkAt(p)||!Construction.clear(v.level.getBlockState(p))||v.town.protects(p)||v.town.protects(p.below())
                    ||!v.level.getBlockState(p.below()).isFaceSturdy(v.level,p.below(),Direction.UP)||v.level.getBlockState(p.below()).is(Blocks.FARMLAND)
                    ||!Construction.clear(v.level.getBlockState(p.above()))||p.distSqr(hub)<4||!v.state.policy.allows("build",p))continue;
            // On the ground, not on the roof of the well or the bell's shelter, and with room to stand beside it.
            var ground=v.level.getBlockState(p.below());
            if(!(VillageIndex.naturalSoil(ground)||ground.is(Blocks.DIRT_PATH)||ground.is(Blocks.GRAVEL)||ground.is(Blocks.SAND)||ground.is(Blocks.COBBLESTONE)
                    ||ground.is(Blocks.SANDSTONE)||ground.is(Blocks.SNOW_BLOCK)))continue;
            boolean standable=false;for(Direction d:Direction.Plane.HORIZONTAL)if(v.safeFeet(p.relative(d)))standable=true;
            if(!standable)continue;
            boolean doorway=false;for(var door:v.town.doors())if(door.distSqr(p)<9)doorway=true;
            if(doorway||v.index.beds.stream().anyMatch(b->b.distSqr(p)<9))continue;
            // A spot beside an existing chest keeps the store compact, but never in a 1-wide corridor.
            double d=p.distSqr(hub)-(adjacentTo(p,Blocks.CHEST)?20:0)+Math.abs(p.getY()-hub.getY())*8;
            if(d<score){score=d;best=p;}
        }
        return best;
    }
    private boolean adjacentTo(BlockPos p,Block block){for(Direction d:Direction.Plane.HORIZONTAL)if(v.level.getBlockState(p.relative(d)).is(block))return true;return false;}

    // --- tools ------------------------------------------------------------------------------------
    static String toolFamily(ResidentRoles.Role role){
        if(role==null)return "axe";
        return switch(role){case MINER->"pick";case FARMER->"hoe";case GUARD->"sword";default->"axe";};
    }
    private Job toolJob(Worker w,Snapshot s,ResidentRoles.Role role){
        String family=toolFamily(role);
        var tag=switch(family){case "pick"->ItemTags.PICKAXES;case "hoe"->ItemTags.HOES;case "sword"->ItemTags.SWORDS;default->ItemTags.AXES;};
        if(w.hasTool(tag)){
            // A miner also needs an axe-free stone pick upgrade once cobble exists.
            if(family.equals("pick")&&!VillageCrafting.hasPickFor(w.bot(),Blocks.IRON_ORE.defaultBlockState())&&s.cobble()>=3&&s.table()!=null&&w.ready("tool"))
                {w.cooldown("tool",1200);return new CraftJob(w,VillageCrafting.tool("pick","stone"),1);}
            // Militia: every adult keeps a stone sword once the village has cobblestone to spare.
            if(!VillageCrafting.hasDefensiveSword(w.bot())&&s.table()!=null&&w.ready("militia")){
                w.cooldown("militia",1200);
                if(v.stock.count(x->x.is(ItemTags.SWORDS)&&Supplies.usable(x)&&!x.is(Items.WOODEN_SWORD)&&!x.is(Items.GOLDEN_SWORD))>0)
                    return new FetchJob(w,new Job.Need(x->x.is(ItemTags.SWORDS)&&Supplies.usable(x)&&!x.is(Items.WOODEN_SWORD)&&!x.is(Items.GOLDEN_SWORD),1,"меч"));
                if(s.cobble()>=2&&CraftJob.affordable(v,VillageCrafting.tool("sword","stone"),1,w))return new CraftJob(w,VillageCrafting.tool("sword","stone"),1);
            }
            return null;
        }
        if(!w.ready("tool"))return null;
        if(v.stock.count(x->x.is(tag)&&Supplies.usable(x))>0&&w.ready("fetch"))return new FetchJob(w,new Job.Need(x->x.is(tag)&&Supplies.usable(x),1,"инструмент"));
        if(s.table()==null)return null;
        // Two ingots stay for shears while beds wait for wool.
        int spareIron=s.iron()-(s.need("bed")>0&&!s.shears()?2:0);
        String tier=(family.equals("pick")||family.equals("sword"))&&spareIron>=3?"iron":s.cobble()>=3?"stone":"wood";
        var recipe=VillageCrafting.tool(family,tier);
        if(!CraftJob.affordable(v,recipe,1,w)){if(tier.equals("wood"))return null;recipe=VillageCrafting.tool(family,"wood");if(!CraftJob.affordable(v,recipe,1,w))return null;}
        w.cooldown("tool",200);
        return new CraftJob(w,recipe,1);
    }

    // --- construction -----------------------------------------------------------------------------
    /** The first unfinished building with something to clear or to place from the store. */
    private Job build(Worker w,Snapshot s){
        if(s.project()==null||!w.ready("build")||v.activeJobs("build")>=Math.max(1,v.workers.size()/2))return null;
        // Homes first: a watchtower only gets builders nobody needs on a house.
        var projects=v.town.unfinished().stream().sorted(Comparator.comparing(b->!b.kind.equals("house"))).toList();
        for(var project:projects){
            var job=new BuildJob(w,project);
            if(v.town.pendingCuts(project,1).isEmpty()&&job.affordable(1).isEmpty()){v.town.unpaid(project);continue;}
            v.town.paid(project);
            return job;
        }
        return null;
    }
    /** Items in the store and in every worker's backpack: a crafted door waits in the crafter's bag first. */
    private int owned(Predicate<ItemStack> test){
        int n=v.stock.count(test);
        for(var o:v.workers.values())if(o.active())n+=o.count(test);
        return n;
    }
    /** Another worker is crafting this already; its output is not counted anywhere yet. */
    private boolean crafting(Worker w,VillageCrafting.Recipe recipe){return busy(w,recipe,Blocks.AIR);}
    /** Doors, beds, torches and slabs a project waits for, crafted from store materials. */
    private Job craftForProject(Worker w,Snapshot s){
        if(!w.ready("craft"))return null;
        // Sand into sandstone (a 2×2 hand recipe) while a village with no way to quarry stone lacks it.
        if(sandstoneVillage(s)&&!crafting(w,VillageCrafting.SANDSTONE_BLOCK)){
            int sand=v.stock.count(VillageCrafting.PLAIN_SAND)+w.count(VillageCrafting.PLAIN_SAND);
            if(sand>=4)return new CraftJob(w,VillageCrafting.SANDSTONE_BLOCK,Math.min(8,sand/4));
        }
        if(s.table()==null)return null;
        if(s.need("door")>owned(x->x.is(ItemTags.WOODEN_DOORS))&&!crafting(w,VillageCrafting.DOOR)&&CraftJob.affordable(v,VillageCrafting.DOOR,1,w))return new CraftJob(w,VillageCrafting.DOOR,1);
        if(s.need("bed")>owned(x->x.is(ItemTags.BEDS))&&s.woolColour()!=null&&s.wool()>=3&&!crafting(w,VillageCrafting.bed(s.woolColour()))&&CraftJob.affordable(v,VillageCrafting.bed(s.woolColour()),1,w))
            return new CraftJob(w,VillageCrafting.bed(s.woolColour()),1);
        // Roof shapes come out of wood the walls do not need: building goes bottom-up, and 24 slabs
        // made first left a mountain village with no planks for the floor (seed 42).
        // At most 24 planks are held back: enough for the next walls, without making a roof wait for the whole house.
        boolean spareWood=s.wood()>=Math.min(24,s.need("planks")+4*s.need("logs"))+6;
        if(spareWood&&s.need("slab")>owned(x->x.is(ItemTags.WOODEN_SLABS))&&!crafting(w,VillageCrafting.SLAB)&&CraftJob.affordable(v,VillageCrafting.SLAB,2,w))return new CraftJob(w,VillageCrafting.SLAB,2);
        if(spareWood&&s.need("stairs")>owned(x->x.is(ItemTags.WOODEN_STAIRS))&&!crafting(w,VillageCrafting.STAIRS)&&CraftJob.affordable(v,VillageCrafting.STAIRS,2,w))return new CraftJob(w,VillageCrafting.STAIRS,2);
        // Stone shapes from cobblestone, or from sandstone where the village builds from sand.
        if(s.need("stone_stairs")>owned(x->x.is(Items.COBBLESTONE_STAIRS)||x.is(Items.SANDSTONE_STAIRS))){
            if(!crafting(w,VillageCrafting.COBBLE_STAIRS)&&CraftJob.affordable(v,VillageCrafting.COBBLE_STAIRS,2,w))return new CraftJob(w,VillageCrafting.COBBLE_STAIRS,2);
            if(!crafting(w,VillageCrafting.SANDSTONE_STAIRS)&&CraftJob.affordable(v,VillageCrafting.SANDSTONE_STAIRS,2,w))return new CraftJob(w,VillageCrafting.SANDSTONE_STAIRS,2);
        }
        if(s.need("stone_slab")>owned(x->x.is(Items.COBBLESTONE_SLAB)||x.is(Items.SANDSTONE_SLAB))){
            if(!crafting(w,VillageCrafting.COBBLE_SLAB)&&CraftJob.affordable(v,VillageCrafting.COBBLE_SLAB,2,w))return new CraftJob(w,VillageCrafting.COBBLE_SLAB,2);
            if(!crafting(w,VillageCrafting.SANDSTONE_SLAB)&&CraftJob.affordable(v,VillageCrafting.SANDSTONE_SLAB,2,w))return new CraftJob(w,VillageCrafting.SANDSTONE_SLAB,2);
        }
        if(s.need("stone_wall")>owned(x->x.is(Items.COBBLESTONE_WALL)||x.is(Items.SANDSTONE_WALL))){
            if(!crafting(w,VillageCrafting.COBBLE_WALL)&&CraftJob.affordable(v,VillageCrafting.COBBLE_WALL,1,w))return new CraftJob(w,VillageCrafting.COBBLE_WALL,1);
            if(!crafting(w,VillageCrafting.SANDSTONE_WALL)&&CraftJob.affordable(v,VillageCrafting.SANDSTONE_WALL,1,w))return new CraftJob(w,VillageCrafting.SANDSTONE_WALL,1);
        }

        if(s.need("trapdoor")>owned(x->x.is(ItemTags.WOODEN_TRAPDOORS))&&!crafting(w,VillageCrafting.TRAPDOOR)&&CraftJob.affordable(v,VillageCrafting.TRAPDOOR,1,w))return new CraftJob(w,VillageCrafting.TRAPDOOR,1);
        if(s.need("ladder")>owned(x->x.is(Items.LADDER))&&!crafting(w,VillageCrafting.LADDER)&&CraftJob.affordable(v,VillageCrafting.LADDER,1,w))return new CraftJob(w,VillageCrafting.LADDER,1);
        if(s.need("glass_pane")>owned(x->x.is(Items.GLASS_PANE))&&!crafting(w,VillageCrafting.GLASS_PANE)&&CraftJob.affordable(v,VillageCrafting.GLASS_PANE,1,w))return new CraftJob(w,VillageCrafting.GLASS_PANE,1);
        if(s.need("fence")>owned(x->x.is(ItemTags.WOODEN_FENCES))&&!crafting(w,VillageCrafting.FENCE)&&CraftJob.affordable(v,VillageCrafting.FENCE,1,w))return new CraftJob(w,VillageCrafting.FENCE,1);
        if(s.need("chest")>owned(x->x.is(Items.CHEST))&&!crafting(w,VillageCrafting.CHEST)&&CraftJob.affordable(v,VillageCrafting.CHEST,1,w))return new CraftJob(w,VillageCrafting.CHEST,1);
        if(s.need("crafting_table")>owned(x->x.is(Items.CRAFTING_TABLE))&&!crafting(w,VillageCrafting.TABLE)&&CraftJob.affordable(v,VillageCrafting.TABLE,1,w))return new CraftJob(w,VillageCrafting.TABLE,1);
        if(s.need("furnace")>owned(x->x.is(Items.FURNACE))&&!crafting(w,VillageCrafting.FURNACE)&&CraftJob.affordable(v,VillageCrafting.FURNACE,1,w))return new CraftJob(w,VillageCrafting.FURNACE,1);
        if((s.need("torch")>owned(x->x.is(Items.TORCH))||!s.dark().isEmpty()&&s.torches()<8)&&s.fuel()>0&&!crafting(w,VillageCrafting.TORCH)){
            for(int n=Math.min(4,s.fuel());n>=1;n--)if(CraftJob.affordable(v,VillageCrafting.TORCH,n,w))return new CraftJob(w,VillageCrafting.TORCH,n);
        }
        if(s.need("planks")>s.planks()&&s.logs()>=2)return new CraftJob(w,VillageCrafting.PLANKS,Math.min(8,s.logs()));
        // Fences turn the village lights into lamp posts once wood is plentiful.
        if(!s.dark().isEmpty()&&s.torches()>0&&s.wood()>=WOOD_RESERVE&&v.stock.count(x->x.is(ItemTags.WOODEN_FENCES))<4&&CraftJob.affordable(v,VillageCrafting.FENCE,2,w))
            return new CraftJob(w,VillageCrafting.FENCE,2);
        return null;
    }
    /** Furnace work and village supplies that no project asks for yet. */
    private Job workshop(Worker w,Snapshot s){
        if(s.furnace()!=null&&w.ready("smelt")&&v.activeJobs("smelt")==0){
            if(s.furnaceOutput())return new SmeltJob(w,s.furnace(),x->false,0,"забирает готовое");
            if(s.rawIron()>0)return new SmeltJob(w,s.furnace(),x->x.is(Items.RAW_IRON),Math.min(16,s.rawIron()),"железо");
            int glassWanted=s.need("glass_pane")>owned(x->x.is(Items.GLASS_PANE))?6:0,sand=v.stock.count(VillageCrafting.SAND);
            if(glassWanted>owned(VillageCrafting.GLASS)&&sand>0)return new SmeltJob(w,s.furnace(),VillageCrafting.SAND,Math.min(16,sand),"стекло");
            if((s.need("torch")>0||!s.dark().isEmpty())&&s.fuel()<2&&s.logs()>=6)return new SmeltJob(w,s.furnace(),VillageCrafting.LOG,4,"древесный уголь");
        }
        if(s.table()!=null&&w.ready("craft")){
            if(s.need("bed")>0&&!s.shears()&&s.iron()>=2&&CraftJob.affordable(v,VillageCrafting.SHEARS,1,w))return new CraftJob(w,VillageCrafting.SHEARS,1);
            if(s.planks()<24&&s.logs()>=4)return new CraftJob(w,VillageCrafting.PLANKS,4);
        }
        return null;
    }
    /** Footpaths from new houses, once there is nothing more urgent; a shovel is made for it. */
    private Job pave(Worker w,Snapshot s){
        if(!w.ready("path")||v.activeJobs("path")>0)return null;
        var cells=v.town.unpaved(24);
        if(cells.isEmpty()){w.cooldown("path",1200);return null;}
        boolean shovel=w.hasTool(ItemTags.SHOVELS)||v.stock.count(x->x.is(ItemTags.SHOVELS)&&Supplies.usable(x))>0;
        if(!shovel){
            if(s.table()==null||busy(w,VillageCrafting.tool("shovel","wood"),Blocks.AIR))return null;
            var recipe=VillageCrafting.tool("shovel",s.cobble()>=1?"stone":"wood");
            if(!CraftJob.affordable(v,recipe,1,w)){w.cooldown("path",600);return null;}
            return new CraftJob(w,recipe,1);
        }
        return new PathJob(w,cells);
    }
    private Job bread(Worker w,Snapshot s){
        if(s.wheat()>=3&&s.bread()<32&&s.table()!=null&&w.ready("craft")&&!busy(w,VillageCrafting.BREAD,Blocks.AIR))
            return new CraftJob(w,VillageCrafting.BREAD,Math.min(4,s.wheat()/3));
        return null;
    }
    /** Spare saplings and seeds into the composter, its bone meal onto young crops. */
    private Job compost(Worker w){
        if(!AutonomousVillagesMod.CONFIG.compost||!w.ready("compost")||v.activeJobs("compost")>0)return null;
        w.cooldown("compost",1200);
        if(v.index.crops.stream().noneMatch(p->CompostJob.young(v,p)))return null;
        BlockPos hub=v.meetingPoint();
        BlockPos composter=v.level.getPoiManager().getInRange(h->h.is(net.minecraft.world.entity.ai.village.poi.PoiTypes.FARMER),hub,Math.min(256,v.state.radius+16),
                        net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.ANY).map(net.minecraft.world.entity.ai.village.poi.PoiRecord::getPos)
                .filter(v.state::contains).filter(p->v.level.getBlockState(p).getBlock() instanceof ComposterBlock)
                .min(Comparator.comparingDouble(p->p.distSqr(w.body().blockPosition()))).orElse(null);
        if(composter==null)return null;
        boolean ready=v.level.getBlockState(composter).getValue(ComposterBlock.LEVEL)>=7;
        int spare=Math.min(24,CompostJob.spare(v)),meal=Math.min(8,v.stock.count(CompostJob.BONE_MEAL));
        if(!ready&&spare<8&&meal==0&&!w.has(CompostJob.BONE_MEAL))return null;
        return new CompostJob(w,composter,spare<8?0:spare,meal);
    }
    /** A small flock grows with wheat from the store while beds still wait for wool. */
    private Job herd(Worker w,Snapshot s){
        if(!w.ready("herd")||s.need("bed")<=s.bedItems()||s.wheat()<4||v.activeJobs("herd")>0)return null;
        var flock=HerdJob.flock(v,w.body(),48);
        if(flock.size()<2||flock.size()>=8){w.cooldown("herd",2400);return null;}
        w.cooldown("herd",6000); // vanilla animals breed again after five minutes
        return new HerdJob(w);
    }
    private Job shear(Worker w,Snapshot s){
        int woolNeeded=3*Math.max(0,s.need("bed")-s.bedItems());
        if(woolNeeded==0||s.wool()>=woolNeeded||!s.shears()||!w.ready("shear")||v.activeJobs("shear")>0)return null;
        if(ShearJob.nearestSheep(v,w.body(),48)==null){w.cooldown("shear",1200);return null;}
        return new ShearJob(w);
    }

    // --- resources ----------------------------------------------------------------------------------
    private Job chop(Worker w,Snapshot s,boolean helping){
        int wanted=WOOD_RESERVE+s.need("planks")+4*s.need("logs")+(s.chests()==0?16:0);
        if(s.wood()>=wanted&&(!helping||s.chests()>0))return null;
        if(!w.ready("chop"))return null;
        BlockPos from=w.body().blockPosition();
        BlockPos tree=v.index.trees.stream().filter(p->treeCandidate(w,p)).min(Comparator.comparingDouble(p->{double dy=p.getY()-from.getY();return p.distSqr(from)+15*dy*dy;})).orElse(null);
        if(tree==null){w.cooldown("chop",400);return null;}
        return new ChopJob(w,tree);
    }
    private boolean treeCandidate(Worker w,BlockPos base){
        if(!v.ready(base,w.id)||v.town.protects(base)||!v.state.policy.allows("wood",base))return false;
        return VillageIndex.naturalLog(v.level,base)||v.state.fellingTrees.contains(base.asLong())&&ForestWork.next(v,base)!=null;
    }
    private Job mine(Worker w,Snapshot s,boolean helping){
        boolean ironWanted=s.need("bed")>0&&!s.shears()&&s.iron()+s.rawIron()<2;
        // Stone shapes are cut from cobblestone: 6 make 4 stairs, 3 make 6 slabs, 6 make 6 walls.
        // Rubble (andesite, diorite...) stands in for cobblestone blocks; stairs, slabs and walls need real cobblestone.
        int wanted=STONE_RESERVE+Math.max(0,s.need("cobblestone")-s.rubble())+(s.need("stone_stairs")*3+1)/2+(s.need("stone_slab")+1)/2+s.need("stone_wall");
        if(s.cobble()>=wanted&&!ironWanted)return null;
        if(!w.ready("mine")||!w.hasTool(ItemTags.PICKAXES))return null;
        BlockPos from=w.body().blockPosition();
        // Cave walls far below are seen by the survey but not reachable on foot: stay near the worker's level.
        Predicate<BlockPos> usable=p->v.ready(p,w.id)&&v.state.policy.allows("mine",p)&&!v.town.protects(p)&&VillageIndex.exposed(v.level,p)
                &&(Math.abs(p.getY()-from.getY())<=8||v.index.shaftOre.contains(p))&&VillageCrafting.hasPickFor(w.bot(),v.level.getBlockState(p))&&p.distSqr(from)<80*80;
        Comparator<BlockPos> near=Comparator.comparingDouble(p->{double dy=p.getY()-from.getY();return p.distSqr(from)+15*dy*dy;});
        var ores=v.index.ore.stream().filter(p->MineKnowledge.iron(v,p)||v.level.getBlockState(p).is(Blocks.COAL_ORE)||v.level.getBlockState(p).is(Blocks.DEEPSLATE_COAL_ORE))
                .filter(usable).sorted(near).limit(6).toList();
        if(!ores.isEmpty())return new MineJob(w,ores);
        // Iron for shears lies deeper than the surface stone: the staircase mine reaches it and
        // yields cobblestone on the way, so it goes first while no iron ore is known.
        if(ironWanted&&(!helping||v.smallCrew())&&v.activeJobs("shaft")==0){
            BlockPos entrance=mineEntrance();
            if(entrance!=null)return new MineJob(w,entrance);
        }
        if(s.cobble()<wanted){
            var stone=v.index.stone.stream().filter(p->v.level.getBlockState(p).is(Blocks.STONE)).filter(usable).sorted(near).limit(12).toList();
            if(stone.size()>=4)return new MineJob(w,stone);
        }
        if(helping&&v.activeJobs("shaft")>0)return null;
        BlockPos entrance=mineEntrance();
        if(entrance==null||v.activeJobs("shaft")>0){w.cooldown("mine",600);return null;}
        return new MineJob(w,entrance);
    }
    /** Sand for window glass, dug by hand. */
    /**
     * Stone blocks the projects still lack, counted in cobblestone: rubble and sandstone fill
     * plain blocks, shapes take stone too (6 make 4 stairs, 3 make 6 slabs, 6 make 6 walls).
     */
    private int stoneShort(Snapshot s){
        return s.need("cobblestone")+(s.need("stone_stairs")*3+1)/2+(s.need("stone_slab")+1)/2+s.need("stone_wall")-s.cobble()-s.rubble();
    }
    /**
     * A village that lacks stone and cannot quarry it: no pickaxe anywhere and no wood to make one
     * (a desert without trees). It builds from sand turned into sandstone instead.
     */
    private boolean sandstoneVillage(Snapshot s){
        if(stoneShort(s)<=0||s.wood()>=3||!v.index.trees.isEmpty())return false;
        Predicate<ItemStack> pick=x->x.is(ItemTags.PICKAXES)&&Supplies.usable(x);
        return v.stock.count(pick)==0&&v.workers.values().stream().noneMatch(o->o.active()&&o.has(pick));
    }
    private Job sand(Worker w,Snapshot s){
        boolean glass=s.need("glass_pane")>owned(x->x.is(Items.GLASS_PANE))&&owned(VillageCrafting.GLASS)+owned(VillageCrafting.SAND)<6;
        boolean sandstone=sandstoneVillage(s)&&owned(VillageCrafting.PLAIN_SAND)<Math.min(64,4*stoneShort(s));
        if(!w.ready("mine")||!glass&&!sandstone)return null;
        BlockPos from=w.body().blockPosition(),hub=v.meetingPoint();
        // Sand for sandstone comes in bulk: a shallow pit away from homes and the bell, never a deep hole.
        Predicate<BlockPos> quarry=p->(glass||p.getY()>=v.state.y-1&&p.distSqr(hub)>=12*12&&v.index.beds.stream().noneMatch(b->b.distSqr(p)<6*6)
                &&v.town.doors().stream().noneMatch(d->d.distSqr(p)<6*6)&&!v.town.protects(p.above()))&&!nearCactus(p);
        var sand=v.index.sand.stream().filter(p->Math.abs(p.getY()-from.getY())<=8&&p.distSqr(from)<64*64)
                .filter(p->v.level.getBlockState(p).is(Blocks.SAND)||v.level.getBlockState(p).is(Blocks.RED_SAND)&&glass)
                .filter(p->v.ready(p,w.id)&&v.state.policy.allows("mine",p)&&!v.town.protects(p))
                .sorted(Comparator.comparingDouble(p->{double dy=p.getY()-from.getY();return p.distSqr(from)+15*dy*dy;}))
                .filter(quarry).limit(8).toList();
        if(sand.size()<2){w.cooldown("mine",200);return null;}
        return new MineJob(w,sand);
    }
    /** Digging sand beside a cactus leaves the digger wedged against it (rc7: a miner was pricked to death). */
    private boolean nearCactus(BlockPos p){
        for(BlockPos q:BlockPos.betweenClosed(p.offset(-2,0,-2),p.offset(2,2,2)))if(v.level.getBlockState(q).is(Blocks.CACTUS))return true;
        return false;
    }
    /** A staircase mine starts at the edge of the claim on natural ground, away from beds. */
    BlockPos mineEntrance(){
        if(v.state.mineEntrance!=null)return v.state.mineLength>=64?null:BlockPos.of(v.state.mineEntrance);
        for(Direction d:Direction.Plane.HORIZONTAL){
            BlockPos column=v.state.localMineColumn(d);
            if(!v.level.hasChunkAt(column))continue;
            BlockPos p=new BlockPos(column.getX(),v.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,column.getX(),column.getZ()),column.getZ());
            if(v.state.contains(p)&&v.state.contains(p.relative(d.getOpposite(),12).below(12))&&v.safeFeet(p)&&VillageIndex.naturalSoil(v.level.getBlockState(p.below()))
                    &&v.index.beds.stream().allMatch(bed->bed.distSqr(p)>100)&&!v.town.protects(p)){
                v.state.mineEntrance=p.asLong();v.state.mineDirection=d.getOpposite().get2DDataValue();v.state.mineLength=0;
                v.chronicle.event("Выбрано место для шахты у "+p.toShortString());v.runtime.save();
                return p;
            }
        }
        return null;
    }
    private Job farm(Worker w){
        if(!w.ready("farm"))return null;
        BlockPos from=w.body().blockPosition();
        BlockPos ripe=closest(w,v.index.crops,p->v.state.policy.allows("farm",p)&&v.level.getBlockState(p).getBlock() instanceof CropBlock c&&c.isMaxAge(v.level.getBlockState(p)));
        if(ripe!=null)return new FarmJob(w,FarmJob.Kind.HARVEST,ripe);
        if(FarmJob.seed(w)==null&&v.stock.count(x->x.is(Items.WHEAT_SEEDS)||x.is(Items.CARROT)||x.is(Items.POTATO)||x.is(Items.BEETROOT_SEEDS))>0)
            return new FetchJob(w,new Job.Need(x->x.is(Items.WHEAT_SEEDS)||x.is(Items.CARROT)||x.is(Items.POTATO)||x.is(Items.BEETROOT_SEEDS),16,"семена"));
        if(FarmJob.seed(w)!=null){
            BlockPos empty=closest(w,v.index.soil,p->v.state.policy.allows("farm",p)&&v.level.getBlockState(p).isAir()&&v.level.getBlockState(p.below()).is(Blocks.FARMLAND));
            if(empty!=null)return new FarmJob(w,FarmJob.Kind.PLANT,empty);
            if(w.hasTool(ItemTags.HOES)&&v.index.soil.size()<Math.min(96,24+v.state.residents.size()*6)){
                BlockPos bank=closest(w,v.index.farmSites,p->v.state.policy.allows("farm",p)&&Construction.clear(v.level.getBlockState(p))&&VillageIndex.naturalSoil(v.level.getBlockState(p.below()))
                        &&FarmJob.watered(v.level,p.below())&&!v.town.protects(p.below())&&v.index.beds.stream().noneMatch(b->b.distSqr(p)<9));
                if(bank!=null)return new FarmJob(w,FarmJob.Kind.TILL,bank);
            }
        }else{
            BlockPos grass=closest(w,v.index.grass,p->v.level.getBlockState(p).is(Blocks.SHORT_GRASS)||v.level.getBlockState(p).is(Blocks.TALL_GRASS));
            if(grass!=null&&v.stock.count(x->x.is(Items.WHEAT_SEEDS))<32)return new FarmJob(w,FarmJob.Kind.SEEDS,grass);
        }
        w.cooldown("farm",200);
        return null;
    }
    private BlockPos closest(Worker w,Collection<BlockPos> positions,Predicate<BlockPos> valid){
        BlockPos from=w.body().blockPosition();
        return positions.stream().filter(p->v.ready(p,w.id)&&p.distSqr(from)<80*80&&valid.test(p))
                .min(Comparator.comparingDouble(p->{double dy=p.getY()-from.getY();return p.distSqr(from)+15*dy*dy;})).orElse(null);
    }

    /** Short text for /avillage plan. */
    List<String> describe(){
        var s=snapshot();var lines=new ArrayList<String>();
        lines.add("Склад: сундуков "+s.chests()+", свободно ячеек "+s.freeSlots()+(s.table()==null?", нет верстака":"")+(s.furnace()==null?", нет печи":""));
        lines.add("Запасы: доски "+s.planks()+", брёвна "+s.logs()+", булыжник "+s.cobble()+", хлеб "+s.bread()+", пшеница "+s.wheat()+", шерсть "+s.wool()
                +", железо "+s.iron()+"+"+s.rawIron()+" руды, уголь "+s.fuel()+", факелы "+s.torches()+", двери "+s.doors()+", кровати "+s.bedItems());
        if(s.project()!=null)lines.add("Стройка: "+Projects.label(s.project())+" "+v.town.built(s.project())+"/"+v.town.size(s.project())+"; не хватает "+s.missing());
        else lines.add("Стройка: нет активной ("+v.town.stage+")");
        lines.add("Тёмных мест рядом с домами: "+s.dark().size()+(s.dark().isEmpty()?"":", ближайшее "+s.dark().getFirst().toShortString()));
        lines.add("Рост: свободных кроватей "+v.breeding.freeBeds()+(v.breeding.blocker().isEmpty()?", ждут пару":", "+v.breeding.blocker()));
        return lines;
    }
}
