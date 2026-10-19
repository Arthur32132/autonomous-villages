package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SignItem;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * A sign beside the front door of every home saying who lives there. The village keeps the text
 * current: a newborn appears on it, a lost resident is taken off.
 */
final class NameplateJob extends Job {
    /** Where the sign hangs and which way it faces; the building it names. */
    record Spot(BlockPos pos,Direction facing,TownBuilding home){}
    private final Spot spot;
    private Fetch fetch;
    private Approach approach;
    NameplateJob(Worker w,Spot spot){super(w,"вешает табличку на дом");this.spot=spot;}
    @Override String kind(){return "nameplate";}
    @Override BlockPos target(){return spot.pos();}
    @Override boolean keeps(ItemStack s){return s.is(ItemTags.SIGNS);}
    @Override boolean tick(){
        if(!w.has(s->s.is(ItemTags.SIGNS))){
            if(fetch==null)fetch=new Fetch(List.of(new Need(s->s.is(ItemTags.SIGNS),1,"табличка")));
            var fetched=fetch.tick();
            if(fetched==Approach.State.FAILED)return fail("нет таблички");
            if(fetched==Approach.State.MOVING)return true;
        }
        if(approach==null)approach=new Approach(spot.pos(),4.0,false);
        var reach=approach.tick();
        if(reach==Approach.State.MOVING)return true;
        if(reach==Approach.State.FAILED){v.rejected.put(spot.pos().asLong(),now()+2400);return fail("не подойти к двери");}
        int slot=Supplies.find(bot(),s->s.is(ItemTags.SIGNS));
        if(slot<0||!(w.bag().get(slot).getItem() instanceof SignItem sign))return fail("нет таблички");
        String id=BuiltInRegistries.ITEM.getKey(sign).toString().replace("_sign","_wall_sign");
        if(!(BuiltInRegistries.BLOCK.getValue(Identifier.parse(id)) instanceof WallSignBlock wall))return fail("нет таблички");
        var state=wall.defaultBlockState().setValue(WallSignBlock.FACING,spot.facing());
        if(!Construction.place(v,bot(),new Construction.Step(spot.pos(),state,sign)))return fail("не удалось повесить");
        v.state.nameplates.put(spot.pos().asLong(),spot.home().origin);
        write(v,spot.pos(),spot.home());
        v.chronicle.add("nameplates",1);
        return false;
    }
    @Override void stop(){if(fetch!=null)fetch.cancel();if(approach!=null)approach.cancel();}

    /** Homes of the village: buildings with a bed and a door. */
    static boolean home(TownBuilding b){
        return b.completed&&(b.kind.equals("house")||b.kind.equals("native_house"))&&b.blocks.stream().anyMatch(p->p.block().getBlock() instanceof BedBlock);
    }
    /** Names of the residents whose bed is in this building. */
    static List<String> residents(Village v,TownBuilding b){
        var beds=new HashSet<Long>();
        for(var p:b.blocks)if(p.block().getBlock() instanceof BedBlock){beds.add(p.pos);beds.add(p.position().relative(p.block().getValue(BedBlock.FACING)).asLong());}
        var out=new ArrayList<String>();
        for(UUID id:v.state.residents){
            if(!(v.level.getEntity(id) instanceof Villager body))continue;
            var home=body.getBrain().getMemory(MemoryModuleType.HOME).filter(g->g.dimension().equals(v.level.dimension())).map(GlobalPos::pos).orElse(null);
            if(home!=null&&beds.contains(home.asLong()))out.add(v.presentation.personName(id));
        }
        Collections.sort(out);
        return out;
    }
    /** "Здесь живут" and up to three names; the last line counts the rest. */
    static String[] lines(List<String> names){
        var lines=new String[]{"","","",""};
        if(names.isEmpty()){lines[0]="Дом";lines[1]="ждёт жильцов";return lines;}
        lines[0]=names.size()==1?"Здесь живёт":"Здесь живут";
        for(int i=0;i<Math.min(3,names.size());i++)lines[i+1]=names.get(i);
        if(names.size()>3)lines[3]=names.get(2)+" и ещё "+(names.size()-3);
        return lines;
    }
    static void write(Village v,BlockPos pos,TownBuilding b){
        if(!(v.level.getBlockEntity(pos) instanceof SignBlockEntity sign))return;
        var lines=lines(residents(v,b));
        var text=sign.getFrontText();
        for(int i=0;i<4;i++)text=text.setMessage(i,Component.literal(lines[i]));
        if(text.equals(sign.getFrontText()))return;
        sign.setText(text,true);sign.setChanged();
        var state=v.level.getBlockState(pos);v.level.sendBlockUpdated(pos,state,state,3);
    }
    /** Rewrites every plate, and forgets plates that are gone. */
    static void refresh(Village v){
        if(v.state.nameplates.isEmpty())return;
        var homes=new HashMap<Long,TownBuilding>();for(var b:v.state.buildings)homes.put(b.origin,b);
        v.state.nameplates.entrySet().removeIf(e->{
            BlockPos pos=BlockPos.of(e.getKey());
            if(!v.level.hasChunkAt(pos))return false;
            var b=homes.get(e.getValue());
            if(b==null||!(v.level.getBlockEntity(pos) instanceof SignBlockEntity))return true;
            write(v,pos,b);return false;
        });
    }
    /** The next home without a plate, and where its plate goes: on the wall beside the door, outside, at head height. */
    static Spot next(Village v){
        var named=new HashSet<>(v.state.nameplates.values());
        for(var b:v.state.buildings){
            if(!home(b)||named.contains(b.origin))continue;
            for(var p:b.blocks){
                var door=p.block();
                if(!(door.getBlock() instanceof DoorBlock)||door.getValue(DoorBlock.HALF)!=DoubleBlockHalf.LOWER)continue;
                BlockPos at=p.position();
                if(!v.level.hasChunkAt(at)||!(v.level.getBlockState(at).getBlock() instanceof DoorBlock))continue;
                Direction facing=door.getValue(DoorBlock.FACING);
                for(Direction out:new Direction[]{facing,facing.getOpposite()}){
                    if(!v.level.canSeeSky(at.relative(out))&&!v.level.canSeeSky(at.relative(out).above()))continue;
                    for(Direction side:new Direction[]{out.getClockWise(),out.getCounterClockWise()}){
                        BlockPos wall=at.above().relative(side),sign=wall.relative(out);
                        if(!v.level.getBlockState(wall).isFaceSturdy(v.level,wall,out)||!v.level.getBlockState(sign).isAir()
                                ||!v.state.contains(sign)||Lighting.unreachable(v,sign))continue;
                        return new Spot(sign,out,b);
                    }
                }
            }
        }
        return null;
    }
}
