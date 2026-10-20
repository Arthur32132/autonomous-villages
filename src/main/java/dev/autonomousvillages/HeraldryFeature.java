package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Герб деревни: the village takes a coat of arms from its name ({@link CoatOfArms}), tells it in the
 * chronicle once, and once it has a finished watchtower or barn puts up banners of it from spare wool:
 * on top of the tower, on the barn front, beside the bell and over the gates of the palisade. One
 * banner job at a time, at most {@link CoatOfArms#MAX_BANNERS}; never while a house waits for beds.
 * Switch {@code heraldry} in {@link ModConfig}; state in {@link VillageState#features}.
 */
final class HeraldryFeature implements Features.Feature {
    static final String KEY="heraldry";
    @Override public String key(){return KEY;}
    @Override public boolean enabled(){return AutonomousVillagesMod.CONFIG.heraldry;}

    /** Saved: whether the arms were told, the banners up (site key to position) and when the next may go up. */
    static final class State {
        public boolean announced;
        public Map<String,Long> raised=new LinkedHashMap<>();
        public long nextAt;
    }
    static State state(Village v){var s=Features.load(v,KEY,State.class,State::new);if(s.raised==null)s.raised=new LinkedHashMap<>();return s;}

    @Override public void tick(Village v,long time){
        if(time%200!=71||v.state.name==null)return;
        var st=state(v);
        if(st.announced)return;
        st.announced=true;Features.store(v,KEY,st);
        v.chronicle.announce("Деревня выбрала свой герб: "+CoatOfArms.of(v.state.name).describe());
    }

    @Override public Job job(Village v,Worker w,ResidentRoles.Role role){
        // Builders, miners and farmers keep to the growth of the village; the others make the flags.
        if(role==ResidentRoles.Role.BUILDER||role==ResidentRoles.Role.MINER||role==ResidentRoles.Role.FARMER||!w.ready("banner"))return null;
        long clock=Math.floorMod(v.level.getOverworldClockTime(),24000);
        if(clock<1000||clock>=9000||v.activeJobs("banner")>0)return null;
        w.cooldown("banner",600);
        var st=state(v);long now=v.level.getGameTime();
        if(st.raised.size()>=CoatOfArms.MAX_BANNERS||now<st.nextAt&&st.nextAt-now<=24000)return null;
        var s=v.planner.snapshot();
        boolean bedsWaiting=s.need("bed")>0;
        if(bedsWaiting||s.sticks()<1&&s.planks()<2||v.index.tables.isEmpty())return null;
        var arms=CoatOfArms.of(v.state.name);
        var wool=new EnumMap<DyeColor,Integer>(DyeColor.class);var dyes=new EnumMap<DyeColor,Integer>(DyeColor.class);
        for(var c:v.stock.chests())for(int i=0;i<c.getContainerSize();i++){
            var item=c.getItem(i);if(item.isEmpty())continue;
            DyeColor colour=woolColour(item);
            if(colour!=null)wool.merge(colour,item.getCount(),Integer::sum);
            else if((colour=tint(v,item))!=null)dyes.merge(colour,item.getCount(),Integer::sum);
        }
        Item banner=Items.BANNER.pick(arms.base());
        boolean spare=v.stock.count(x->x.is(banner))>0;
        var field=CoatOfArms.field(arms.base(),wool,dyes,bedsWaiting);
        if(field==null&&!spare)return null;
        var site=nextSite(v,st,now);
        if(site==null)return null;
        w.cooldown("banner",2400);
        if(spare)return new BannerJob(w,arms,site,null,0,true);
        if(field==CoatOfArms.Field.DYED)dyes.merge(arms.base(),-CoatOfArms.WOOL_PER_BANNER,Integer::sum);
        return new BannerJob(w,arms,site,field,arms.affordable(dyes),s.sticks()>0);
    }

    // --- materials -------------------------------------------------------------------------------
    static DyeColor woolColour(ItemStack s){
        if(!s.is(ItemTags.WOOL))return null;
        for(DyeColor c:DyeColor.values())if(s.is(Items.WOOL.pick(c)))return c;
        return null;
    }
    /** Per recipe set (a reload makes a new one): which dye a flower item makes. */
    private static final Map<Object,Map<Item,Optional<DyeColor>>> FLOWER_DYES=new WeakHashMap<>();
    /** The colour of a dye, or of the dye a flower makes by its vanilla recipe; null for anything else. */
    static DyeColor tint(Village v,ItemStack s){
        if(s.isEmpty())return null;
        if(s.is(ItemTags.DYES)){for(DyeColor c:DyeColor.values())if(s.is(Items.DYE.pick(c)))return c;return null;}
        if(!FlowerJob.FLOWER.test(s))return null;
        return FLOWER_DYES.computeIfAbsent(v.level.recipeAccess(),k->new HashMap<>()).computeIfAbsent(s.getItem(),item->{
            var input=CraftingInput.of(1,1,List.of(new ItemStack(item)));
            var out=v.level.recipeAccess().getRecipeFor(RecipeType.CRAFTING,input,v.level).map(r->r.value().assemble(input)).orElse(ItemStack.EMPTY);
            for(DyeColor c:DyeColor.values())if(out.is(Items.DYE.pick(c)))return Optional.of(c);
            return Optional.empty();
        }).orElse(null);
    }

    // --- sites -----------------------------------------------------------------------------------
    /** Sites whose banner could not be put up wait a while (site key to the tick they may be tried again). */
    private static final Map<Village,Map<String,Long>> retry=new WeakHashMap<>();
    static void blocked(Village v,String key,long until){retry.computeIfAbsent(v,k->new HashMap<>()).merge(key,until,Math::max);}

    /** The next place for a banner: the tower top, the barn front, beside the bell, then the gates nearest the bell. */
    static CoatOfArms.Site nextSite(Village v,State st,long now){
        var tower=finished(v,"tower");var barn=finished(v,"storehouse");
        if(tower==null&&barn==null)return null;
        var sites=new ArrayList<CoatOfArms.Site>();
        if(tower!=null){
            var required=new HashSet<BlockPos>();for(var p:tower.blocks)if(!p.optional)required.add(p.position());
            BlockPos top=CoatOfArms.towerTop(required,cells(tower));
            if(top!=null)sites.add(new CoatOfArms.Site("tower",top,null));
        }
        if(barn!=null){
            BlockPos door=barn.blocks.stream().filter(p->p.block().getBlock() instanceof DoorBlock).map(TownBuilding.Plan::position).findFirst().orElse(null);
            if(door!=null)sites.add(CoatOfArms.front("storehouse",cells(barn),door));
        }
        BlockPos hub=v.meetingPoint();
        if(!st.raised.containsKey("bell")){
            BlockPos bell=CoatOfArms.besideBell(hub,column->ground(v,hub,column));
            if(bell!=null)sites.add(new CoatOfArms.Site("bell",bell,null));
        }
        var wall=finished(v,"wall");
        if(wall!=null){
            var all=cells(wall);var fences=new HashSet<BlockPos>();
            for(var p:wall.blocks)if(p.block().getBlock() instanceof FenceBlock)fences.add(p.position());
            wall.blocks.stream().filter(p->p.block().getBlock() instanceof FenceGateBlock).sorted(Comparator.comparingDouble(p->p.position().distSqr(hub))).limit(3)
                    .forEach(p->sites.add(CoatOfArms.gate("gate:"+p.pos,fences,all,p.position(),p.block().getValue(FenceGateBlock.FACING))));
        }
        var waiting=retry.getOrDefault(v,Map.of());
        sites.removeIf(site->site==null||waiting.getOrDefault(site.key(),0L)>now||!placeable(v,site));
        return CoatOfArms.next(sites,st.raised.keySet());
    }
    private static TownBuilding finished(Village v,String kind){return v.state.buildings.stream().filter(b->b.completed&&b.kind.equals(kind)).findFirst().orElse(null);}
    private static Set<BlockPos> cells(TownBuilding b){var out=new HashSet<BlockPos>();for(var p:b.blocks)out.add(p.position());return out;}
    /** Free ground in a column beside the bell: not a building, path, doorway or field, about the bell's height. */
    private static BlockPos ground(Village v,BlockPos bell,BlockPos column){
        if(!v.level.hasChunkAt(column)||!v.state.contains(column))return null;
        BlockPos p=new BlockPos(column.getX(),v.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,column.getX(),column.getZ()),column.getZ());
        if(Math.abs(p.getY()-bell.getY())>3||v.town.protects(p.below()))return null;
        var under=v.level.getBlockState(p.below());
        if(under.is(Blocks.DIRT_PATH)||under.is(Blocks.FARMLAND)||!under.isFaceSturdy(v.level,p.below(),Direction.UP))return null;
        if(v.state.buildings.stream().anyMatch(b->b.path.contains(p.below().asLong()))||v.town.doors().stream().anyMatch(d->d.distSqr(p)<=2))return null;
        return placeable(v,new CoatOfArms.Site("bell",p,null))?p:null;
    }
    /** The cell is free, inside the village and its policy, outside every plan, and what holds the banner is there. */
    static boolean placeable(Village v,CoatOfArms.Site site){
        BlockPos p=site.pos();
        BlockPos holder=site.facing()==null?p.below():p.relative(site.facing().getOpposite());
        if(!v.level.hasChunkAt(p)||!v.level.hasChunkAt(holder)||!v.state.contains(p)||!v.canWork(p)||v.town.protects(p)||!v.state.policy.allows("build",p))return false;
        var at=v.level.getBlockState(p);
        return Construction.clear(at)&&at.getFluidState().isEmpty()&&v.level.getBlockState(holder).isSolid();
    }

    /** A banner is up: remembered, told in the chronicle, cheered. */
    static void raised(Village v,CoatOfArms.Site site,int layers){
        var st=state(v);long now=v.level.getGameTime();
        st.raised.put(site.key(),site.pos().asLong());st.nextAt=now+2400;Features.store(v,KEY,st);
        v.chronicle.add("banners",1);
        if(layers>0)v.chronicle.add("bannerLayers",layers);
        String key=site.key();
        if(key.equals("tower"))v.chronicle.announce("Над вышкой поднят флаг деревни");
        else if(key.equals("storehouse"))v.chronicle.announce("На амбаре вывесили флаг деревни");
        else if(key.equals("bell"))v.chronicle.event("У колокола поднят флаг деревни");
        else v.chronicle.event("Над воротами частокола поднят флаг деревни");
        if(AutonomousVillagesMod.CONFIG.celebrations){
            BlockPos p=site.pos();
            v.level.sendParticles(ParticleTypes.HAPPY_VILLAGER,p.getX()+.5,p.getY()+1,p.getZ()+.5,14,.4,.7,.4,0);
        }
    }
}
