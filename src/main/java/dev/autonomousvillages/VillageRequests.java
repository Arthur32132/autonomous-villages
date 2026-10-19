package dev.autonomousvillages;

import java.util.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.gossip.GossipType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A request to players: the material the current build lacks most and the store does not hold,
 * posted on the notice board by the bell and announced. Whoever hands it to a resident (sneaking, as
 * any gift) gets a reward from the store when the request is filled: emeralds if the store has them,
 * otherwise the best of its food, and the residents' lasting goodwill.
 */
final class VillageRequests {
    private VillageRequests(){}
    static final int MAX_AMOUNT=32,REWARD_EMERALDS=3;

    /** Every ten seconds: drop a request the build no longer needs, post a new one when there is none. */
    static void tick(Village v){
        if(!AutonomousVillagesMod.CONFIG.requests)return;
        var request=v.state.request;
        var project=v.town.active();
        var missing=project==null?Map.<String,Integer>of():v.town.missing(project);
        if(request!=null){
            if(!missing.containsKey(request.key)){v.state.request=null;v.state.nextRequest=v.level.getGameTime()+2400;v.chronicle.event("Заказ снят: "+label(request.key)+" уже не нужен");}
            return;
        }
        if(project==null||v.level.getGameTime()<v.state.nextRequest)return;
        String best=null;int short_=0;
        for(var e:missing.entrySet()){int lacking=e.getValue()-stored(v,e.getKey());if(lacking>short_){short_=lacking;best=e.getKey();}}
        if(best==null)return;
        var r=new VillageState.Request();r.key=best;r.amount=Math.min(MAX_AMOUNT,short_);r.posted=v.level.getGameTime();
        v.state.request=r;
        v.chronicle.announce("Заказ на доске у колокола: "+label(best)+" ×"+r.amount+". Принеси жителю (присев) — деревня отблагодарит.");
    }
    static String label(String key){return VillagePresentation.NEEDS.getOrDefault(key,key.replace("minecraft:",""));}
    /** The request key an item answers, or null for an item that is not a building block. */
    static String key(Item item){return item instanceof BlockItem b?Projects.materialKey(b.getBlock().defaultBlockState()):null;}
    static int stored(Village v,String key){return v.stock.count(s->key.equals(key(s.getItem())));}
    /** What is left to bring. */
    static int left(VillageState.Request r){return Math.max(0,r.amount-r.delivered);}

    /** Counts a gift toward the request; when it is filled the giver is rewarded. */
    static void delivered(Village v,ServerPlayer player,Villager resident,Item item,int count){
        var r=v.state.request;
        if(!AutonomousVillagesMod.CONFIG.requests||r==null||!r.key.equals(key(item)))return;
        r.delivered+=count;
        if(left(r)>0){
            player.sendSystemMessage(Component.literal("["+v.state.name+"] По заказу осталось: "+label(r.key)+" ×"+left(r)).withStyle(net.minecraft.ChatFormatting.YELLOW));
            return;
        }
        v.state.request=null;v.state.nextRequest=v.level.getGameTime()+2400;
        var reward=reward(v);
        String what=reward.isEmpty()?"благодарность жителей":reward.is(Items.EMERALD)?reward.getCount()+" изумр.":reward.getCount()+" шт. еды";
        if(!reward.isEmpty()&&!player.getInventory().add(reward))player.drop(reward,false);
        for(UUID id:v.state.residents)if(v.level.getEntity(id) instanceof Villager villager)villager.getGossips().add(player.getUUID(),GossipType.MAJOR_POSITIVE,5);
        v.chronicle.add("requestsFilled",1);
        v.chronicle.announce(player.getGameProfile().name()+" выполнил заказ ("+label(r.key)+" ×"+r.amount+")! Награда: "+what);
    }
    /** Emeralds from the store; without them the best food it has, a few pieces. */
    static ItemStack reward(Village v){
        int emeralds=v.stock.consume(s->s.is(Items.EMERALD),REWARD_EMERALDS);
        if(emeralds>0)return new ItemStack(Items.EMERALD,emeralds);
        for(Item food:List.of(Items.COOKED_BEEF,Items.COOKED_PORKCHOP,Items.COOKED_MUTTON,Items.BAKED_POTATO,Items.BREAD)){
            int got=v.stock.consume(s->s.is(food),4);
            if(got>0)return new ItemStack(food,got);
        }
        return ItemStack.EMPTY;
    }
    /** The three lines the notice board shows under the village's name while a request is open. */
    static List<String> board(VillageState.Request r){
        return List.of("ЗАКАЗ игроку:",label(r.key)+" ×"+left(r),"награда ждёт");
    }
}
