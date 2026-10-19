package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Items;

/**
 * Village growth: when a bed is free and the store holds enough food, two adults have a child.
 * The child is a vanilla villager baby made by the vanilla offspring code; the food is really eaten.
 */
final class Breeding {
    static final int MAX_RESIDENTS=64;
    static final long INTERVAL=4800;
    private final Village v;
    Breeding(Village v){this.v=v;}
    int freeBeds(){return freeBedList().size();}
    List<BlockPos> freeBedList(){
        var b=v.state.bounds;if(b==null)return List.of();
        BlockPos center=new BlockPos((b.minX+b.maxX)/2,v.state.y,(b.minZ+b.maxZ)/2);
        int r=Math.max(b.maxX-b.minX,b.maxZ-b.minZ)/2+1;
        return v.level.getPoiManager().getInSquare(h->h.is(PoiTypes.HOME),center,r,PoiManager.Occupancy.HAS_SPACE)
                .map(p->p.getPos()).filter(v.state::contains).toList();
    }
    /** Bread-equivalents in the store: a bread feeds as much as four vegetables. */
    int food(){return v.stock.count(s->s.is(Items.BREAD))+v.stock.count(s->s.is(Items.CARROT)||s.is(Items.POTATO)||s.is(Items.BEETROOT))/4;}
    String blocker(){
        if(v.state.residents.size()>=MAX_RESIDENTS)return "предел населения";
        if(freeBeds()<1)return "нет свободной кровати";
        if(food()<3)return "мало еды на складе (нужно 3 хлеба)";
        long wait=v.state.lastBirth+INTERVAL-v.level.getGameTime();
        if(wait>0)return "семьи отдыхают ещё "+wait/20+" с";
        return "";
    }
    void tick(){
        long now=v.level.getGameTime();
        if(now%200!=37||v.level.isDarkOutside()||!blocker().isEmpty())return;
        var adults=new ArrayList<Villager>();
        for(UUID id:v.state.residents)if(v.level.getEntity(id) instanceof Villager body&&body.isAlive()&&!body.isBaby()&&!body.isSleeping()&&!body.isTrading()
                &&v.state.contains(body.blockPosition()))adults.add(body);
        if(adults.size()<2)return;
        // The child is born at home: next to a free bed, to the two adults nearest to it.
        var beds=freeBedList();if(beds.isEmpty())return;
        BlockPos bed=beds.get(Math.floorMod((int)(now/200),beds.size()));
        var nearest=adults.stream().sorted(java.util.Comparator.comparingDouble(a->a.blockPosition().distSqr(bed))).limit(2).toList();
        Villager first=nearest.get(0),second=nearest.get(1);
        BlockPos crib=v.safeFeet(bed.above())?bed.above():java.util.stream.Stream.of(net.minecraft.core.Direction.values()).map(bed::relative)
                .filter(v::safeFeet).findFirst().orElse(null);
        if(crib==null)return;
        int bread=Math.min(3,v.stock.count(s->s.is(Items.BREAD)));
        int eaten=v.stock.consume(s->s.is(Items.BREAD),bread);
        if(eaten<3)v.stock.consume(s->s.is(Items.CARROT)||s.is(Items.POTATO)||s.is(Items.BEETROOT),(3-eaten)*4);
        Villager baby=first.getBreedOffspring(v.level,second);
        if(baby==null)return;
        baby.setAge(-24000);
        var spot=v.feetPosition(crib);
        baby.snapTo(spot.x,spot.y,spot.z,0,0);
        v.level.addFreshEntityWithPassengers(baby);
        Festivities.birth(v,baby);
        first.setAge(6000);second.setAge(6000);
        v.level.broadcastEntityEvent(first,(byte)12);v.level.broadcastEntityEvent(second,(byte)12);v.level.broadcastEntityEvent(baby,(byte)12);
        v.state.residents.add(baby.getUUID());v.state.lastBirth=now;
        v.chronicle.born(v.presentation.personName(baby.getUUID()));v.runtime.save();
    }
}
