package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;

/** Persistent field layouts and rotating forest lots. No crops, water or saplings are conjured. */
final class ManagedLand {
    static final class Field {
        long water,seedChest,produceChest,entry;
        Long source;
        transient long nextSurvey;
        Set<Long> cells=new LinkedHashSet<>();
    }
    static final class Forest {
        long center,restUntil;
        int harvested,replanted;
    }
    List<Field> fields=new ArrayList<>();
    List<Forest> forests=new ArrayList<>();
    int rotation;
    void validate(VillageState v){
        if(fields==null||fields.size()>8||forests==null||forests.size()>16||rotation<0)throw new IllegalArgumentException("Повреждённые хозяйственные участки");
        for(var f:fields)if(f==null||f.cells==null||f.cells.size()>64||f.cells.contains(null)||!v.contains(BlockPos.of(f.water))||!v.contains(BlockPos.of(f.seedChest))||!v.contains(BlockPos.of(f.produceChest))
                ||!v.contains(BlockPos.of(f.entry))||f.source!=null&&!v.contains(BlockPos.of(f.source))||f.cells.stream().anyMatch(p->!v.contains(BlockPos.of(p))))throw new IllegalArgumentException("Повреждённое поле");
        for(var f:forests)if(f==null||!v.contains(BlockPos.of(f.center))||f.restUntil<0||f.harvested<0||f.replanted<0)throw new IllegalArgumentException("Повреждённый лесной участок");
    }
    static void tick(Village v){
        if(v.level.getGameTime()%200!=0)return;
        if(v.state.land.fields.size()<Math.min(8,Math.max(1,v.state.residents.size()/6))&&v.state.buildings.size()<256){
            var base=v.town.findSite(7,7);
            if(base!=null){
                // A seven-square grass field: water in the middle, an unplanted access lane,
                // separate seed and harvest chests at the entry. No solid floor over crops.
                var field=new Field();field.water=base.offset(3,-1,3).asLong();field.entry=base.offset(3,0,-1).asLong();
                field.seedChest=base.offset(1,0,0).asLong();field.produceChest=base.offset(5,0,0).asLong();
                for(int x=1;x<=5;x++)for(int z=1;z<=5;z++)if(x!=3)field.cells.add(base.offset(x,0,z).asLong());
                var plan=new TownBuilding("field",base);plan.add(BlockPos.of(field.seedChest),Blocks.CHEST.defaultBlockState());plan.add(BlockPos.of(field.produceChest),Blocks.CHEST.defaultBlockState());
                plan.add(base.offset(2,0,0),Blocks.CRAFTING_TABLE.defaultBlockState());
                if(field.cells.stream().anyMatch(cell->!VillageIndex.naturalSoil(v.level.getBlockState(BlockPos.of(cell).below()))||!Construction.clear(v.level.getBlockState(BlockPos.of(cell)))||!v.state.policy.allows("farm",BlockPos.of(cell))))return;
                if(!Infrastructure.prepare(v,plan,7,7))return;
                plan.validate(v.state);v.state.land.fields.add(field);v.state.buildings.add(plan);v.town.invalidate();v.runtime.save();v.event("Размечено поле: вода, проход, 20 посадок, раздельная выдача семян и урожая");
            }
        }
        if(v.state.land.forests.size()<16)for(var tree:v.index.trees){
            if(!v.state.contains(tree)||!v.state.policy.allows("wood",tree)||v.state.land.forests.stream().anyMatch(f->BlockPos.of(f.center).distSqr(tree)<24*24))continue;
            var lot=new Forest();lot.center=tree.asLong();v.state.land.forests.add(lot);v.runtime.save();break;
        }
    }
    static boolean seedChest(Village v,long p){return v.state.land.fields.stream().anyMatch(f->f.seedChest==p);}
    static boolean seedItem(ItemStack s){return s.is(Items.WHEAT_SEEDS)||s.is(Items.BEETROOT_SEEDS)||s.is(Items.CARROT)||s.is(Items.POTATO);}
    static boolean acceptsDeposit(Village v,long chest,ItemStack s){
        if(seedChest(v,chest)){
            var c=v.level.getBlockEntity(BlockPos.of(chest));
            return seedItem(s)&&c instanceof net.minecraft.world.level.block.entity.ChestBlockEntity box&&box.countItem(s.getItem())<SettlementEconomy.budget(v).seed();
        }
        return ProductionBuildings.accepts(ProductionBuildings.storagePurpose(v,chest),s);
    }
    static int depositLimit(Village v,long chest,ItemStack s,int wanted){
        if(!acceptsDeposit(v,chest,s))return 0;
        if(seedChest(v,chest)&&v.level.getBlockEntity(BlockPos.of(chest)) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity box)
            return Math.min(wanted,Math.max(0,SettlementEconomy.budget(v).seed()-box.countItem(s.getItem())));
        return wanted;
    }
    static int withdrawLimit(Resident r,long chest,ItemStack s,int wanted){
        if(seedChest(r.v,chest))return r.v.farmer(r.id)&&seedItem(s)?Math.min(8,wanted):0;
        if(s.is(Items.WHEAT)&&r.v.farm.caretaker()!=r)return Math.min(wanted,Math.max(0,r.v.count(t->t.is(Items.WHEAT))-SettlementEconomy.budget(r.v).breedingWheat()));
        return wanted;
    }
    static Forest owner(Village v,BlockPos p){return v.state.land.forests.stream().filter(f->BlockPos.of(f.center).distSqr(p)<24*24).min(Comparator.comparingDouble(f->BlockPos.of(f.center).distSqr(p))).orElse(null);}
    static List<Forest> harvestable(Village v,long now){
        return eligible(v.state.land.forests,now,lot->v.index.trees.stream().anyMatch(p->owner(v,p)==lot&&v.level.hasChunkAt(p)&&v.state.policy.allows("wood",p)
                &&(VillageIndex.naturalLog(v.level,p)||v.state.fellingTrees.contains(p.asLong()))));
    }
    static List<Forest> eligible(List<Forest> lots,long now,java.util.function.Predicate<Forest> hasTrees){return lots.stream().filter(f->now>=f.restUntil&&hasTrees.test(f)).toList();}
    static boolean mayHarvest(Village v,BlockPos p){
        var lot=owner(v,p);
        if(lot==null)return true;
        if(v.state.fellingTrees.contains(p.asLong()))return true;
        if(v.level.getGameTime()<lot.restUntil)return false;
        var eligible=harvestable(v,v.level.getGameTime());
        return eligible.isEmpty()||eligible.get(Math.floorMod(v.state.land.rotation,eligible.size()))==lot;
    }
    static void felled(Village v,BlockPos p){
        var lot=owner(v,p);if(lot!=null){lot.harvested++;lot.restUntil=v.level.getGameTime()+24000;v.state.land.rotation=(v.state.land.rotation+1)%1000000;}
    }
    static void planted(Village v,BlockPos p){var lot=owner(v,p);if(lot!=null){lot.replanted++;lot.restUntil=Math.max(lot.restUntil,v.level.getGameTime()+24000);}}
    static Resident irrigator(Village v){
        return v.workers.values().stream().filter(r->r.bot()!=null&&r.bot().state()==dev.navigationai.api.BotState.ACTIVE&&r.body().isAlive()&&!r.body().isNoAi()&&v.farmer(r.id))
                .min(Comparator.<Resident>comparingInt(r->r.bot().inventory().count(Items.BUCKET)+r.bot().inventory().count(Items.WATER_BUCKET)>0?0:v.hasRole(r.id,ResidentRoles.Role.FARMER)?1:2).thenComparing(r->r.id.toString())).orElse(null);
    }
    static boolean work(Resident r){
        var v=r.v;if(!v.farmer(r.id))return false;
        for(var f:v.state.land.fields){
            var building=v.state.buildings.stream().filter(b->b.kind.equals("field")&&b.blocks.stream().anyMatch(plan->plan.pos==f.seedChest)).findFirst().orElse(null);
            if(building==null||!building.completed||!Infrastructure.prepared(v,building))continue;
            BlockPos water=BlockPos.of(f.water);
            if(!v.level.getBlockState(water).is(Blocks.WATER)){
                if(irrigator(v)!=r)continue;
                if(VillageIndex.naturalSoil(v.level.getBlockState(water))){r.assign("dig_irrigation",water);return r.job!=null;}
                if(!Construction.clear(v.level.getBlockState(water)))continue;
                if(r.bot().inventory().count(Items.WATER_BUCKET)>0){r.assign("irrigate",water);return r.job!=null;}
                if(r.bot().inventory().count(Items.BUCKET)==0){
                    if(VillageWorkshop.craft(r.bot(),v,Items.BUCKET,new String[]{"I I"," I "},s->s.is(Items.IRON_INGOT)))return true;
                    return r.depositOrFetch(r.bot(),v.steward(r.id));
                }
                if(f.source!=null){var p=BlockPos.of(f.source);
                    if(v.level.hasChunkAt(p)&&v.level.getBlockState(p).is(Blocks.WATER)&&v.level.getFluidState(p).isSource()&&v.state.policy.allows("farm",p)){r.assign("fill_bucket",p);return r.job!=null;}f.source=null;
                }
                if(v.level.getGameTime()<f.nextSurvey)continue;f.nextSurvey=v.level.getGameTime()+600;
                for(BlockPos p:BlockPos.betweenClosed(water.offset(-24,-3,-24),water.offset(24,3,24)))
                    if(v.state.contains(p)&&v.level.hasChunkAt(p)&&v.level.getBlockState(p).is(Blocks.WATER)&&v.level.getFluidState(p).isSource()
                            &&v.level.canSeeSky(p.above())&&v.state.policy.allows("farm",p)){f.source=p.asLong();v.runtime.save();r.assign("fill_bucket",p.immutable());return r.job!=null;}
                r.status="орошение ждёт доступный природный источник воды; можно выбрать поле у воды";return false;
            }
            for(long packed:f.cells){var p=BlockPos.of(packed);
                if(v.state.policy.allows("farm",p)&&v.ready(p,r.id)&&Supplies.find(r.bot(),s->s.is(net.minecraft.tags.ItemTags.HOES)&&Supplies.usable(s))>=0&&Construction.clear(v.level.getBlockState(p))&&VillageIndex.naturalSoil(v.level.getBlockState(p.below()))){r.assign("hoe",p);return true;}
            }
        }
        return false;
    }
    static boolean transferWater(Resident r,BlockPos p,boolean fill){
        var v=r.v;if(!v.canWork(p)||!v.level.hasChunkAt(p)||!v.level.getWorldBorder().isWithinBounds(p)||!v.state.contains(p)||!v.state.policy.allows("farm",p)||r.body().getEyePosition().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(p))>16)return false;
        var before=Supplies.copy(r.bot().inventory().snapshot().slots());var next=Supplies.copy(before);
        Item input=fill?Items.BUCKET:Items.WATER_BUCKET,output=fill?Items.WATER_BUCKET:Items.BUCKET;
        var source=next.stream().filter(s->s.is(input)).findFirst().orElse(null);if(source==null)return false;
        var state=v.level.getBlockState(p);
        if(fill?!(state.is(Blocks.WATER)&&v.level.getFluidState(p).isSource()):!Construction.clear(state))return false;
        if(!fill&&(v.town.protects(p)||v.level.getEntities(null,new net.minecraft.world.phys.AABB(p)).stream().anyMatch(e->e.isAlive())))return false;
        source.shrink(1);if(!Supplies.insert(next,new ItemStack(output))||!Supplies.replace(r.bot(),before,next))return false;
        if(!v.level.setBlock(p,fill?Blocks.AIR.defaultBlockState():Blocks.WATER.defaultBlockState(),3)){Supplies.replace(r.bot(),next,before);return false;}return true;
    }
}
