package dev.autonomousvillages;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.*;

/** Budgets protect production inputs before optional construction or native breeding food. */
final class SettlementEconomy {
    record Budget(int food,int seed,int breedingWheat,int timber){ }
    static Budget budget(int residents,int wounded,int children,int travellers,int flock){
        return new Budget(Math.max(24,residents*6+wounded*6+children*6+travellers*6+12),
                Math.max(16,residents*4),flock>0&&flock<4?4:0,Math.max(48,residents*12));
    }
    static Budget budget(Village v){
        int wounded=(int)v.workers.values().stream().filter(r->r.bot()!=null&&r.body().isAlive()&&r.body().getHealth()<r.body().getMaxHealth()).count();
        int children=(int)v.state.residents.stream().map(v.level::getEntity).filter(e->e instanceof net.minecraft.world.entity.npc.villager.Villager b&&b.isBaby()).count();
        return budget(v.state.residents.size(),wounded,children,v.state.expedition==null?0:v.state.expedition.team.size(),v.farm.state().flock.size());
    }
    static int foodUnits(int bread,int vegetables){return bread*4+vegetables;}
    static int food(Village v){
        int stock=foodUnits(v.count(s->s.is(Items.BREAD)),v.count(s->Supplies.food(s)&&!s.is(Items.BREAD)));
        for(var r:v.workers.values())if(r.bot()!=null&&r.body().isAlive())stock+=foodUnits(r.body().getInventory().countItem(Items.BREAD),Supplies.count(Supplies.copy(r.body().getInventory()),s->Supplies.food(s)&&!s.is(Items.BREAD)));
        for(var r:v.workers.values())if(r.bot()!=null&&v.farmer(r.id))stock-=Math.min(8,r.bot().inventory().count(Items.CARROT))+Math.min(8,r.bot().inventory().count(Items.POTATO));
        return Math.max(0,stock)/4;
    }
    static int wood(Village v){return v.count(s->s.is(ItemTags.PLANKS))+4*v.count(s->s.is(ItemTags.LOGS));}
    static boolean surplus(Village v){return food(v)>=budget(v).food&&wood(v)>=budget(v).timber;}
    static boolean canGrow(Village v){
        long adults=v.state.residents.stream().map(v.level::getEntity).filter(e->e instanceof net.minecraft.world.entity.npc.villager.Villager b&&b.isAlive()&&!b.isBaby()).count();
        return adults>=2&&v.index.beds.stream().filter(v.town::knownShelter).count()>=v.state.residents.size()+2&&food(v)>=budget(v).food+12
                &&v.state.land.fields.stream().anyMatch(f->v.level.getBlockState(net.minecraft.core.BlockPos.of(f.water)).is(net.minecraft.world.level.block.Blocks.WATER)
                    &&f.cells.stream().filter(p->v.level.getBlockState(net.minecraft.core.BlockPos.of(p).below()).is(net.minecraft.world.level.block.Blocks.FARMLAND)).count()>=16);
    }
    static int seedReserve(Village v,ItemStack s){
        if(s.is(Items.WHEAT_SEEDS)||s.is(Items.BEETROOT_SEEDS)||s.is(Items.CARROT)||s.is(Items.POTATO))return budget(v).seed;
        if(s.is(Items.WHEAT))return budget(v).breedingWheat;
        return 0;
    }
}
