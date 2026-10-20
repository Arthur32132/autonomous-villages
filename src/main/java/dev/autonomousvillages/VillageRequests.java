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
    /** Requests are filled by gifts: with donations off nobody could bring anything, so there are none. */
    static boolean enabled(ModConfig c){return c.requests&&c.donations;}
    /**
     * Who brought how much toward an open request (in memory: a restart forgets it). A request withdrawn
     * before it is filled thanks each of them; the one who fills it gets the reward.
     */
    private static final Map<VillageState.Request,Map<UUID,Contribution>> GIVERS=new WeakHashMap<>();
    record Contribution(String name,int count){}

    /** Every ten seconds: drop a request the build no longer needs, post a new one when there is none. */
    static void tick(Village v){
        // Switched off (requests or donations): an open request is withdrawn, so the board and the ledger stop showing it.
        if(!enabled(AutonomousVillagesMod.CONFIG)){if(v.state.request!=null)withdraw(v,v.state.request);v.state.request=null;return;}
        var request=v.state.request;
        var project=v.town.active();
        var missing=project==null?Map.<String,Integer>of():v.town.missing(project);
        if(request!=null){
            if(!missing.containsKey(request.key)){withdraw(v,request);v.state.request=null;v.state.nextRequest=v.level.getGameTime()+2400;}
            return;
        }
        // Requests are for players: none is posted (or kept in the chronicle) while nobody is about.
        if(project==null||v.level.getGameTime()<v.state.nextRequest||!playerNear(v))return;
        String best=null;int short_=0;
        for(var e:missing.entrySet()){int lacking=e.getValue()-stored(v,e.getKey());if(lacking>short_){short_=lacking;best=e.getKey();}}
        if(best==null)return;
        var r=new VillageState.Request();r.key=best;r.amount=Math.min(MAX_AMOUNT,short_);r.posted=v.level.getGameTime();
        v.state.request=r;
        v.chronicle.announce("Заказ на доске у колокола: "+label(best)+" ×"+r.amount+". Принеси жителю (присев) — деревня отблагодарит.");
    }
    static boolean playerNear(Village v){
        var area=VillageDiscovery.area(v.state,64);
        for(var player:v.runtime.server.getPlayerList().getPlayers())if(player.level()==v.level&&!player.isSpectator()&&area.contains(player.position()))return true;
        return false;
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
        if(!enabled(AutonomousVillagesMod.CONFIG)||r==null||!r.key.equals(key(item)))return;
        r.delivered+=count;
        record(GIVERS.computeIfAbsent(r,k->new LinkedHashMap<>()),player.getUUID(),player.getGameProfile().name(),count);
        if(left(r)>0){
            player.sendSystemMessage(Component.literal("["+v.state.name+"] По заказу осталось: "+label(r.key)+" ×"+left(r)).withStyle(net.minecraft.ChatFormatting.YELLOW));
            return;
        }
        v.state.request=null;v.state.nextRequest=v.level.getGameTime()+2400;
        var helpers=GIVERS.remove(r);
        var reward=reward(v);
        String what=reward.isEmpty()?"благодарность жителей":reward.is(Items.EMERALD)?reward.getCount()+" изумр.":reward.getCount()+" шт. еды";
        if(!reward.isEmpty()&&!player.getInventory().add(reward))player.drop(reward,false);
        for(UUID id:v.state.residents)if(v.level.getEntity(id) instanceof Villager villager)villager.getGossips().add(player.getUUID(),GossipType.MAJOR_POSITIVE,5);
        v.chronicle.add("requestsFilled",1);
        v.chronicle.announce(player.getGameProfile().name()+" выполнил заказ ("+label(r.key)+" ×"+r.amount+")! Награда: "+what);
        // Others who brought part of it are thanked too.
        if(helpers!=null){helpers.remove(player.getUUID());thank(v,r,helpers,"Заказ выполнен");}
    }
    static void record(Map<UUID,Contribution> givers,UUID id,String name,int count){
        givers.merge(id,new Contribution(name,count),(a,b)->new Contribution(b.name(),a.count()+b.count()));
    }
    /** The chronicle's thanks for parts of a request: "Заказ снят: … (доски). Спасибо за помощь: Mars ×12!". */
    static String credit(String head,String what,Map<UUID,Contribution> givers){
        var names=new ArrayList<String>();
        for(var c:givers.values())names.add(c.name()+" ×"+c.count());
        return head+" ("+what+"). Спасибо за помощь: "+String.join(", ",names)+"!";
    }
    /** A request taken down before it is filled: whoever brought part of it is thanked (chronicle, chat) and remembered kindly. */
    private static void withdraw(Village v,VillageState.Request r){
        var givers=GIVERS.remove(r);
        if(givers!=null)thank(v,r,givers,"Заказ снят: деревня обошлась без него");
    }
    private static void thank(Village v,VillageState.Request r,Map<UUID,Contribution> givers,String head){
        if(givers.isEmpty())return;
        v.chronicle.event(credit(head,label(r.key),givers));
        for(var e:givers.entrySet()){
            int points=Math.clamp(5L*e.getValue().count()/Math.max(1,r.amount),1,5);
            for(UUID id:v.state.residents)if(v.level.getEntity(id) instanceof Villager villager)villager.getGossips().add(e.getKey(),GossipType.MAJOR_POSITIVE,points);
            var player=v.runtime.server.getPlayerList().getPlayer(e.getKey());
            if(player!=null)player.sendSystemMessage(Component.literal("["+v.state.name+"] "+head+". Спасибо за "+label(r.key)+" ×"+e.getValue().count()+" — жители это запомнят.").withStyle(net.minecraft.ChatFormatting.YELLOW));
        }
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
