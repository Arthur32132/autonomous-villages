package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A resident brings a visiting player a loaf from the village's surplus and hands it over. Once per
 * player per day; nothing is given while the village itself is short of bread.
 */
final class GiftJob extends Job {
    private final UUID player;
    private Fetch fetch;
    private Walk walk;
    GiftJob(Worker w,ServerPlayer player){super(w,"несёт гостинец: "+player.getName().getString());this.player=player.getUUID();}
    @Override String kind(){return "gift";}
    @Override int timeLimit(){return 1600;}
    @Override boolean keeps(ItemStack s){return s.is(Items.BREAD);}
    /** A player standing in the village who has not had a gift today. */
    static ServerPlayer guest(Village v){
        long now=v.level.getGameTime();
        BlockPos hub=v.meetingPoint();
        return v.level.players().stream().filter(p->p.isAlive()&&!p.isSpectator()&&v.state.contains(p.blockPosition())&&p.blockPosition().distSqr(hub)<48*48
                &&v.gifted.getOrDefault(p.getUUID(),0L)<=now).min(Comparator.comparingDouble(p->p.blockPosition().distSqr(hub))).orElse(null);
    }
    @Override boolean tick(){
        if(!(v.level.getPlayerByUUID(player) instanceof ServerPlayer guest)||!guest.isAlive()||!v.state.contains(guest.blockPosition()))return fail("гость ушёл");
        if(w.count(s->s.is(Items.BREAD))<1){
            if(fetch==null)fetch=new Fetch(List.of(new Need(s->s.is(Items.BREAD),1,"хлеб")));
            var state=fetch.tick();
            if(state==Approach.State.FAILED)return fail("нет хлеба для гостя");
            if(state==Approach.State.MOVING)return true;
        }
        if(body().distanceToSqr(guest)>2.5*2.5){
            if(walk==null||walk.point.distanceToSqr(guest.position())>4)walk=new Walk(guest.position(),2);
            if(walk.tick()==Approach.State.FAILED)return fail("гость далеко");
            return true;
        }
        if(!Supplies.consume(bot(),Items.BREAD,1))return false;
        var gift=new ItemStack(Items.BREAD);
        if(!guest.getInventory().add(gift))guest.drop(gift,false);
        body().swing(net.minecraft.world.InteractionHand.MAIN_HAND);body().getLookControl().setLookAt(guest);
        v.level.sendParticles(ParticleTypes.HAPPY_VILLAGER,body().getX(),body().getY()+2.1,body().getZ(),5,.3,.2,.3,0);
        v.level.playSound(null,body().blockPosition(),SoundEvents.VILLAGER_YES,SoundSource.NEUTRAL,1f,1f);
        guest.sendSystemMessage(Component.literal(v.presentation.personName(w.id)+" из "+v.state.name+": «Угощайся, свежий хлеб!»").withStyle(net.minecraft.ChatFormatting.GREEN));
        v.chronicle.add("gifts",1);v.gifted.put(player,now()+24000);
        return false;
    }
    @Override void stop(){if(fetch!=null)fetch.cancel();if(walk!=null)walk.cancel();}
}
