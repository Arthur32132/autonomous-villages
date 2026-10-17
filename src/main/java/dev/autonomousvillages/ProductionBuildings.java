package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.*;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;

/** Distinct work points and inventory destinations, planned for measured demand, one at a time. */
final class ProductionBuildings {
    static final Set<String> KINDS=Set.of("lumber_yard","smithy","granary","watch_post");
    static String label(String kind){return switch(kind){case "lumber_yard"->"лесной двор";case "smithy"->"кузница";case "granary"->"амбар";case "watch_post"->"сторожевой пост";default->kind;};}
    static TownBuilding blueprint(String kind,BlockPos base){
        if(!KINDS.contains(kind))throw new IllegalArgumentException("Неизвестная мастерская");
        var b=new TownBuilding(kind,base);
        // Open front, safe roof, rear windbreak; different equipment, not decorative signs.
        for(int x=0;x<5;x++)for(int z=0;z<5;z++){
            b.add(base.offset(x,0,z),Blocks.OAK_PLANKS.defaultBlockState());
            b.add(base.offset(x,3,z),Blocks.OAK_SLAB.defaultBlockState());
        }
        for(int x:new int[]{0,4})for(int z:new int[]{0,4})for(int y=1;y<=2;y++)b.add(base.offset(x,y,z),Blocks.OAK_LOG.defaultBlockState());
        b.add(base.offset(2,1,4),Blocks.CHEST.defaultBlockState());
        if(!kind.equals("watch_post"))b.add(base.offset(1,1,3),Blocks.CRAFTING_TABLE.defaultBlockState());
        if(kind.equals("smithy"))for(int x:new int[]{1,3})b.add(base.offset(x,1,4),Blocks.FURNACE.defaultBlockState().setValue(FurnaceBlock.FACING,Direction.NORTH));
        if(kind.equals("granary"))b.add(base.offset(3,1,4),Blocks.CHEST.defaultBlockState());
        if(kind.equals("lumber_yard"))b.add(base.offset(3,1,3),Blocks.CHEST.defaultBlockState());
        if(kind.equals("watch_post")){
            for(int x:new int[]{1,3})b.add(base.offset(x,1,4),Blocks.COBBLESTONE_WALL.defaultBlockState());
        }
        b.add(base.offset(2,1,1),Blocks.TORCH.defaultBlockState());return b;
    }
    static void tick(Village v){
        if(v.level.getGameTime()%400!=0||!SettlementEconomy.surplus(v)||v.state.buildings.size()>=250
                ||v.state.buildings.stream().anyMatch(b->!b.completed&&(KINDS.contains(b.kind)||b.kind.equals("house"))))return;
        // Evaluate all needs so an existing high-demand building cannot starve the next type.
        var priorities=new ArrayList<String>();if(IronSupply.next(v)!=null)priorities.add("smithy");
        if(v.state.land.fields.size()>=2||v.count(s->s.is(Items.WHEAT))>=24)priorities.add("granary");
        if(v.state.land.forests.size()>=2)priorities.add("lumber_yard");
        if(v.state.residents.size()>=6)priorities.add("watch_post");
        String kind=priorities.stream().filter(k->v.state.buildings.stream().noneMatch(b->b.kind.equals(k))).findFirst().orElse(null);
        if(kind==null)return;
        var site=v.town.findSite(5,5);if(site==null)return;var b=blueprint(kind,site);
        if(!Infrastructure.prepare(v,b,5,5))return;b.validate(v.state);v.state.buildings.add(b);v.town.invalidate();v.runtime.save();v.event("Нагрузка хозяйства: строят "+label(kind));
    }
    static String storagePurpose(Village v,long p){
        for(var b:v.state.buildings)if(KINDS.contains(b.kind)&&b.blocks.stream().anyMatch(plan->plan.pos==p&&plan.block().is(Blocks.CHEST)))return b.kind;
        return "shared";
    }
    static boolean accepts(String purpose,ItemStack s){return switch(purpose){
        case "lumber_yard"->s.is(ItemTags.LOGS)||s.is(ItemTags.PLANKS)||s.is(ItemTags.SAPLINGS)||s.is(Items.STICK)||s.is(ItemTags.AXES);
        case "smithy"->s.is(Items.RAW_IRON)||s.is(Items.IRON_INGOT)||s.is(Items.COAL)||s.is(Items.CHARCOAL)||s.is(Items.COBBLESTONE)||Supplies.tool(s)||s.has(net.minecraft.core.component.DataComponents.EQUIPPABLE);
        case "granary"->Supplies.food(s)||s.is(Items.WHEAT)||s.is(Items.WHEAT_SEEDS)||s.is(Items.BEETROOT_SEEDS);
        case "watch_post"->s.is(ItemTags.SWORDS)||s.is(Items.SHIELD)||s.has(net.minecraft.core.component.DataComponents.EQUIPPABLE)||Supplies.food(s);
        default->true;
    };}
    static BlockPos post(Village v){
        return v.state.buildings.stream().filter(b->b.kind.equals("watch_post")&&b.completed&&Infrastructure.prepared(v,b))
                .map(b->BlockPos.of(b.origin).offset(2,1,2)).findFirst().orElse(v.meetingPoint());
    }
    static void record(Village v,BlockPos point,ItemStack output){
        if(output.isEmpty())return;
        for(var b:v.state.buildings)if(KINDS.contains(b.kind)&&b.completed&&BlockPos.of(b.origin).offset(2,1,2).distSqr(point)<=36&&accepts(b.kind,output)){
            v.state.workshopOutput.merge(b.kind,(long)output.getCount(),(a,n)->a>Long.MAX_VALUE-n?Long.MAX_VALUE:a+n);return;
        }
    }
    static void alarm(Village v){
        if(v.state.buildings.stream().anyMatch(b->b.kind.equals("watch_post")&&b.completed))v.state.workshopOutput.merge("watch_post",1L,(a,n)->Math.min(Long.MAX_VALUE-1,a)+n);
    }
    static boolean work(Resident r){
        var v=r.v;var role=v.primaryRole(r.id);
        String kind=role==null?"":switch(role){case LOGGER->"lumber_yard";case FARMER->"granary";case MINER,STEWARD->"smithy";case BUILDER->v.smallCrew()?"smithy":"";default->"";};
        var b=v.state.buildings.stream().filter(p->p.kind.equals(kind)&&p.completed&&Infrastructure.prepared(v,p)).findFirst().orElse(null);
        if(b==null)return false;var point=BlockPos.of(b.origin).offset(2,1,2);
        if(kind.equals("smithy")){
            for(long packed:v.state.furnaces){var p=BlockPos.of(packed);
                if(p.distSqr(point)<36&&v.level.getBlockEntity(p) instanceof net.minecraft.world.level.block.entity.FurnaceBlockEntity f&&VillageWorkshop.wantsFurnace(r.bot(),v,f)){
                    r.assign("furnace",p);return true;
                }
            }
        }
        if(kind.equals("lumber_yard")&&r.bot().inventory().snapshot().slots().stream().anyMatch(s->s.is(ItemTags.LOGS))&&v.town.woodDemand()>0){
            if(r.body().blockPosition().distSqr(point)>9){r.assign("workshop",point);return true;}
            return VillageWorkshop.craft(r.bot(),v,new String[]{"M"},s->s.is(ItemTags.LOGS_THAT_BURN),s->s.is(ItemTags.PLANKS));
        }
        if(kind.equals("granary")&&r.bot().inventory().count(Items.WHEAT)>=3){
            if(r.body().blockPosition().distSqr(point)>9){r.assign("workshop",point);return true;}
            return VillageWorkshop.craft(r.bot(),v,Items.BREAD,new String[]{"MMM"},s->s.is(Items.WHEAT));
        }
        return false;
    }
}
