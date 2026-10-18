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
    record Snapshot(int planks,int logs,int sticks,int cobble,int bread,int wheat,int seeds,int doors,int bedItems,int torches,int fuel,int iron,int rawIron,
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
        Map<String,Integer> missing=project==null?Map.of():v.town.missing(project);
        boolean night=v.level.isDarkOutside()||Math.floorMod(v.level.getOverworldClockTime(),24000)>9000;
        var dark=v.state.lights.size()<160&&(night||now%1200<600)?Lighting.darkSpots(v,8):List.<BlockPos>of();
        BlockPos hub=v.meetingPoint();
        BlockPos table=v.index.tables.stream().filter(p->v.level.getBlockState(p).is(Blocks.CRAFTING_TABLE)&&p.distSqr(hub)<32*32).findFirst().orElse(null);
        BlockPos furnace=null;boolean output=false;
        for(long packed:v.state.furnaces)if(v.level.getBlockEntity(BlockPos.of(packed)) instanceof FurnaceBlockEntity f&&!f.isLocked()){
            if(furnace==null)furnace=f.getBlockPos();if(!f.getItem(2).isEmpty()){output=true;furnace=f.getBlockPos();}
        }
        boolean shears=st.count(x->x.is(Items.SHEARS)&&Supplies.usable(x))>0||v.workers.values().stream().anyMatch(w->w.active()&&w.has(x->x.is(Items.SHEARS)&&Supplies.usable(x)));
        s=new Snapshot(st.count(VillageCrafting.PLANK),st.count(VillageCrafting.LOG),st.count(VillageCrafting.STICK),st.count(VillageCrafting.COBBLE),
                st.count(x->x.is(Items.BREAD)),st.count(VillageCrafting.WHEAT),st.count(x->x.is(Items.WHEAT_SEEDS)||x.is(Items.BEETROOT_SEEDS)),
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
        // 5. The role's own work, then shared priorities.
        Job own=role==null?null:switch(role){
            case BUILDER->first(()->build(w,s),()->craftForProject(w,s),()->chop(w,s,true));
            case STEWARD->first(()->craftForProject(w,s),()->workshop(w,s),()->build(w,s));
            case LOGGER->chop(w,s,false);
            case MINER->mine(w,s,false);
            case FARMER->first(()->farm(w),()->bread(w,s),()->shear(w,s));
            case GUARD->chop(w,s,true);
            default->null;
        };
        if(own!=null)return own;
        Job shared=first(()->build(w,s),()->craftForProject(w,s),()->workshop(w,s),()->shear(w,s),()->chop(w,s,true),
                ()->mine(w,s,true),()->farm(w),()->bread(w,s),()->pave(w,s));
        if(shared==null&&w.hasSurplus()&&s.chests()>0&&s.freeSlots()>0)return new DepositJob(w);
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
        boolean noChest=v.stock.chests().stream().noneMatch(c->c.getBlockPos().distSqr(hub)<=10*10),full=s.chests()>0&&s.freeSlots()<9&&s.chests()<48;
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
        if(s.chests()>0&&s.table()!=null&&v.noticeBoard()==null&&!busy(w,VillageCrafting.SIGN,Blocks.OAK_SIGN)&&w.ready("board")){
            int slot=Supplies.find(w.bot(),x->x.is(ItemTags.SIGNS));
            if(slot>=0&&w.bag().get(slot).getItem() instanceof net.minecraft.world.item.BlockItem sign){
                BlockPos spot=utilitySpot();if(spot!=null){w.cooldown("board",600);return new PlaceJob(w,spot,sign.getBlock().defaultBlockState(),"табличка");}
            }
            if(s.planks()+s.logs()*4>=12&&CraftJob.affordable(v,VillageCrafting.SIGN,1,w)){w.cooldown("board",1200);return new CraftJob(w,VillageCrafting.SIGN,1);}
        }
        return null;
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
        String tier=(family.equals("pick")||family.equals("sword"))&&s.iron()>=3?"iron":s.cobble()>=3?"stone":"wood";
        var recipe=VillageCrafting.tool(family,tier);
        if(!CraftJob.affordable(v,recipe,1,w)){if(tier.equals("wood"))return null;recipe=VillageCrafting.tool(family,"wood");if(!CraftJob.affordable(v,recipe,1,w))return null;}
        w.cooldown("tool",200);
        return new CraftJob(w,recipe,1);
    }

    // --- construction -----------------------------------------------------------------------------
    private Job build(Worker w,Snapshot s){
        var project=s.project();
        if(project==null||!w.ready("build")||v.activeJobs("build")>=Math.max(1,v.workers.size()/2))return null;
        var job=new BuildJob(w,project);
        if(v.town.pendingCuts(project,1).isEmpty()&&job.affordable(1).isEmpty())return null;
        return job;
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
        if(s.table()==null||!w.ready("craft"))return null;
        if(s.need("door")>owned(x->x.is(ItemTags.WOODEN_DOORS))&&!crafting(w,VillageCrafting.DOOR)&&CraftJob.affordable(v,VillageCrafting.DOOR,1,w))return new CraftJob(w,VillageCrafting.DOOR,1);
        if(s.need("bed")>owned(x->x.is(ItemTags.BEDS))&&s.woolColour()!=null&&s.wool()>=3&&!crafting(w,VillageCrafting.bed(s.woolColour()))&&CraftJob.affordable(v,VillageCrafting.bed(s.woolColour()),1,w))
            return new CraftJob(w,VillageCrafting.bed(s.woolColour()),1);
        if(s.need("slab")>owned(x->x.is(ItemTags.WOODEN_SLABS))&&!crafting(w,VillageCrafting.SLAB)&&CraftJob.affordable(v,VillageCrafting.SLAB,2,w))return new CraftJob(w,VillageCrafting.SLAB,2);
        if(s.need("stairs")>owned(x->x.is(ItemTags.WOODEN_STAIRS))&&!crafting(w,VillageCrafting.STAIRS)&&CraftJob.affordable(v,VillageCrafting.STAIRS,2,w))return new CraftJob(w,VillageCrafting.STAIRS,2);
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
        int wanted=STONE_RESERVE+s.need("cobblestone");
        if(s.cobble()>=wanted&&!ironWanted)return null;
        if(!w.ready("mine")||!w.hasTool(ItemTags.PICKAXES))return null;
        BlockPos from=w.body().blockPosition();
        // Cave walls far below are seen by the survey but not reachable on foot: stay near the worker's level.
        Predicate<BlockPos> usable=p->v.ready(p,w.id)&&v.state.policy.allows("mine",p)&&!v.town.protects(p)&&VillageIndex.exposed(v.level,p)
                &&Math.abs(p.getY()-from.getY())<=8&&VillageCrafting.hasPickFor(w.bot(),v.level.getBlockState(p))&&p.distSqr(from)<64*64;
        Comparator<BlockPos> near=Comparator.comparingDouble(p->{double dy=p.getY()-from.getY();return p.distSqr(from)+15*dy*dy;});
        var ores=v.index.ore.stream().filter(p->MineKnowledge.iron(v,p)||v.level.getBlockState(p).is(Blocks.COAL_ORE)||v.level.getBlockState(p).is(Blocks.DEEPSLATE_COAL_ORE))
                .filter(usable).sorted(near).limit(6).toList();
        if(!ores.isEmpty())return new MineJob(w,ores);
        if(s.cobble()<wanted){
            var stone=v.index.stone.stream().filter(p->v.level.getBlockState(p).is(Blocks.STONE)).filter(usable).sorted(near).limit(12).toList();
            if(stone.size()>=4)return new MineJob(w,stone);
        }
        if(helping&&v.activeJobs("shaft")>0)return null;
        BlockPos entrance=mineEntrance();
        if(entrance==null||v.activeJobs("shaft")>0){w.cooldown("mine",600);return null;}
        return new MineJob(w,entrance);
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
