package dev.autonomousvillages;

import java.util.*;
import dev.navigationai.api.*;
import net.minecraft.core.*;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.*;

/** A paid grass pen. Animals walk, eat, breed and regrow wool through vanilla world mechanics. */
final class SheepFarm {
    static final int TARGET=4;
    static final class State {
        Long base;
        Set<UUID> flock=new LinkedHashSet<>();
        UUID leading;
        long retryAfter;
        void validate(VillageState v){
            if(flock==null||flock.size()>16||flock.contains(null)||base!=null&&!v.contains(BlockPos.of(base)))throw new IllegalArgumentException("Повреждённое овцеводство");
        }
    }
    final Village v;
    private int leadStage;
    SheepFarm(Village v){this.v=v;}
    State state(){return v.state.sheepFarm;}
    TownBuilding pen(){return v.state.buildings.stream().filter(b->b.kind.equals("sheep_pen")).findFirst().orElse(null);}
    BlockPos gate(){return BlockPos.of(state().base).offset(3,0,0);}
    BlockPos inside(){return BlockPos.of(state().base).offset(3,0,3);}
    boolean inside(BlockPos p){
        if(state().base==null)return false;var b=BlockPos.of(state().base);
        return p.getX()>b.getX()&&p.getX()<b.getX()+6&&p.getZ()>b.getZ()&&p.getZ()<b.getZ()+6&&Math.abs(p.getY()-b.getY())<=1;
    }
    boolean ready(){var p=pen();return p!=null&&Infrastructure.prepared(v,p)&&p.built(v)==p.blocks.size();}
    boolean owns(Sheep s){return state().flock.contains(s.getUUID());}
    /** Called only for confirmed death/destruction, never for an absent/unloaded entity. */
    boolean lost(UUID id){
        boolean changed=state().flock.remove(id);
        if(id.equals(state().leading)){
            state().leading=null;changed=true;
            for(var r:v.workers.values())if("herd".equals(r.job))r.cancel();
        }
        if(changed)state().retryAfter=0;
        return changed;
    }
    List<Sheep> flock(){return state().flock.stream().map(v.level::getEntity).filter(e->e instanceof Sheep s&&s.isAlive()).map(e->(Sheep)e).toList();}
    Resident caretaker(){
        return v.workers.values().stream().filter(r->r.bot()!=null&&r.bot().state()==BotState.ACTIVE&&r.body().isAlive()&&!r.body().isNoAi()&&(v.farmer(r.id)||v.steward(r.id)))
                .min(Comparator.<Resident>comparingInt(r->v.hasRole(r.id,ResidentRoles.Role.FARMER)?0:1).thenComparing(r->r.id.toString())).orElse(null);
    }
    boolean woolRequested(){return v.state.orders.stream().anyMatch(o->o.purpose.equals("bed_wool")&&o.open());}
    boolean wantsWool(){
        Orders.refreshStock(v);
        int demand=v.state.orders.stream().filter(o->o.purpose.equals("bed_wool")&&o.open()).mapToInt(Orders.Order::outstanding).sum();
        int usable=0;
        for(Item item:net.minecraft.core.registries.BuiltInRegistries.ITEM)if(new ItemStack(item).is(net.minecraft.tags.ItemTags.WOOL))
            usable+=v.count(s->s.is(item))/3*3;
        // Outstanding already excludes wool in the maker's bag. Only the remainder
        // elsewhere can cover it; prepared beds are not physical wool in this pool.
        int allocated=v.state.orders.stream().filter(o->o.purpose.equals("bed_wool"))
                .filter(o->v.workers.values().stream().anyMatch(r->r.id.toString().equals(o.recipient)&&r.bot()!=null&&r.bot().state()==BotState.ACTIVE))
                .mapToInt(o->Math.max(0,o.delivered-o.craftedCoverage)).sum();
        return demand>Math.max(0,usable-allocated);
    }
    static TownBuilding blueprint(BlockPos base){
        var b=new TownBuilding("sheep_pen",base);
        for(int x=0;x<=6;x++)for(int z=0;z<=6;z++)if(x==0||x==6||z==0||z==6){
            if(x==3&&z==0)b.add(base.offset(x,0,z),Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING,Direction.SOUTH));
            else b.add(base.offset(x,0,z),Blocks.OAK_FENCE.defaultBlockState());
        }
        return b;
    }
    void tick(){
        if(v.level.getGameTime()%100!=0)return;
        var farm=state();var p=pen();
        if(p==null&&woolRequested()&&v.state.buildings.size()<256){
            BlockPos base=v.town.findSite(7,7);
            if(base!=null){p=blueprint(base);if(!Infrastructure.prepare(v,p,7,7))return;p.validate(v.state);v.state.buildings.add(p);farm.base=base.asLong();v.town.invalidate();v.event("Размечен травяной загон с калиткой для племенных овец");v.runtime.save();}
        }else if(p!=null)farm.base=p.origin;
        if(farm.base==null)return;
        boolean changed=false;
        for(var id:List.copyOf(farm.flock)){var e=v.level.getEntity(id);if(e!=null&&!e.isAlive())changed|=lost(id);}
        // Only offspring inside our pen are adopted, never named/leashed player animals.
        for(Sheep s:v.level.getEntitiesOfClass(Sheep.class,new AABB(inside()).inflate(4),s->inside(s.blockPosition())&&s.isBaby()&&!s.hasCustomName()&&!s.isLeashed()))
            if(farm.flock.size()<16)changed|=farm.flock.add(s.getUUID());
        if(farm.leading==null)gate(false);
        if(changed)v.runtime.save();
    }
    boolean needsAttention(Resident r){
        if(caretaker()!=r||!ready())return false;
        return state().flock.size()<2||flock().stream().anyMatch(s->!inside(s.blockPosition()))||canBreed()||wantsWool();
    }
    boolean canBreed(){
        return state().flock.size()<TARGET&&flock().stream().filter(s->inside(s.blockPosition())&&s.canFallInLove()).count()>=2
                &&v.count(Supplies::food)>=Math.max(6,v.state.residents.size()*3);
    }
    int wheatReserve(Resident r){return caretaker()==r&&state().base!=null&&(state().flock.size()<2||canBreed())?3:0;}
    boolean planWork(Resident r){
        if(!needsAttention(r)||v.level.getGameTime()<state().retryAfter)return false;
        var b=r.bot();
        if(state().flock.size()<2||flock().stream().anyMatch(s->!inside(s.blockPosition()))){
            if(b.inventory().count(Items.WHEAT)==0){if(r.depositOrFetch(b,v.steward(r.id)))return true;r.status="для привода овец нужна настоящая пшеница";return false;}
            Sheep sheep=state().leading==null?null:v.level.getEntity(state().leading) instanceof Sheep s?s:null;
            if(sheep==null)sheep=flock().stream().filter(s->!inside(s.blockPosition())).findFirst().orElse(null);
            if(sheep==null)sheep=v.level.getEntitiesOfClass(Sheep.class,r.body().getBoundingBox().inflate(96),s->s.isAlive()&&!s.isBaby()&&!s.hasCustomName()&&!s.isLeashed()&&!owns(s)&&v.runtime.villages.values().stream().noneMatch(other->other!=v&&other.farm.owns(s)))
                    .stream().min(Comparator.<Sheep>comparingInt(s->s.getColor()==DyeColor.WHITE?0:1).thenComparingDouble(r.body()::distanceToSqr)).orElse(null);
            if(sheep==null){state().retryAfter=r.now()+400;r.status="ищет пару овец для собственного загона";return false;}
            state().leading=sheep.getUUID();leadStage=0;r.assign("herd",sheep.blockPosition());return true;
        }
        if(canBreed()){
            if(b.inventory().count(Items.WHEAT)>=2){r.assign("breed_sheep",inside());return true;}
            if(r.depositOrFetch(b,v.steward(r.id)))return true;
        }
        if(wantsWool()){
            if(Supplies.find(b,s->s.is(Items.SHEARS)&&Supplies.usable(s))<0)return r.prepareCrafting(b,v.steward(r.id))||r.depositOrFetch(b,v.steward(r.id));
            Sheep s=flock().stream().filter(sheep->inside(sheep.blockPosition())&&sheep.readyForShearing()).findFirst().orElse(null);
            if(s!=null){r.assign("shear",s.blockPosition());return true;}
            r.status="ждёт отрастания шерсти у собственных овец";
        }
        return false;
    }
    void herd(Resident r){
        var s=state().leading==null?null:v.level.getEntity(state().leading) instanceof Sheep sheep?sheep:null;
        if(s==null||!s.isAlive()||s.hasCustomName()||s.isLeashed()||!ready()||r.bot().inventory().count(Items.WHEAT)==0||r.now()-r.since>2400){
            state().leading=null;state().retryAfter=r.now()+200;r.cancel();gate(false);return;
        }
        if(inside(s.blockPosition())&&!s.getBoundingBox().intersects(new AABB(gate()).inflate(.1))){
            gate(false);
            // A block coordinate just past the threshold can still leave an animal's
            // body in the gate. Keep the wheat inside until every body clears it.
            if(v.level.getBlockState(gate()).getValue(FenceGateBlock.OPEN)){walk(r,inside().south());return;}
            state().flock.add(s.getUUID());s.getNavigation().stop();state().leading=null;r.cancel();
            v.event("Овца сама дошла через калитку в племенной загон");v.runtime.save();return;
        }
        int wheat=Supplies.find(r.bot(),stack->stack.is(Items.WHEAT));
        // select() stops all library tasks, even when selecting the same slot.
        if(!r.body().getMainHandItem().is(Items.WHEAT)){r.bot().inventory().select(wheat);r.navigation=null;}
        if(inside(r.body().blockPosition()))gate(true);
        if(r.body().distanceToSqr(s)>64){if(!inside(r.body().blockPosition()))gate(false);walk(r,s.blockPosition());r.status="подходит к овце с пшеницей";return;}
        gate(true);
        // Tempted sheep stop short of the holder. Stand deep enough that this
        // stopping distance still leaves their whole body beyond the gate.
        BlockPos waypoint=leadStage==0?gate().north(2):inside().south();
        if(leadStage==0&&s.blockPosition().distSqr(waypoint)<9)leadStage=1;
        if(r.now()%5==0){
            var path=s.getNavigation().createPath(r.body().blockPosition(),1);
            if(path!=null&&path.canReach())s.getNavigation().moveTo(path,1.1);
        }
        if(r.body().distanceToSqr(s)<36)walk(r,waypoint);
        r.status="ведёт овцу с пшеницей через калитку";
    }
    private void walk(Resident r,BlockPos p){
        if(r.body().blockPosition().distSqr(p)<1)return;
        if(r.navigation==null||r.navigation.completion().isDone())r.navigation=r.bot().navigator().goTo(new NavigationGoal.PositionGoal(Vec3.atBottomCenterOf(p),.4),Resident.TRAVEL);
    }
    boolean feedPair(Resident r){
        if(!ready()||!canBreed()||r.body().position().distanceToSqr(Vec3.atCenterOf(inside()))>16)return false;
        var pair=flock().stream().filter(s->inside(s.blockPosition())&&s.canFallInLove()&&s.distanceToSqr(r.body())<16&&r.body().hasLineOfSight(s)).limit(2).toList();
        if(pair.size()!=2||r.bot().inventory().count(Items.WHEAT)<2||!Supplies.consume(r.bot(),Items.WHEAT,2))return false;
        for(var s:pair)s.setInLove(null);
        r.body().swing(net.minecraft.world.InteractionHand.MAIN_HAND);v.event("Две настоящие пшеницы отданы племенной паре");return true;
    }
    void gate(boolean open){
        if(state().base==null)return;var p=gate();var actual=v.level.getBlockState(p);
        if(!(actual.getBlock() instanceof FenceGateBlock)||actual.getValue(FenceGateBlock.OPEN)==open)return;
        if(!open&&!v.level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,new AABB(p).inflate(.1)).isEmpty())return;
        v.level.setBlock(p,actual.setValue(FenceGateBlock.OPEN,open),3);
    }
}
