package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.ai.gossip.GossipType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.WrittenBookContent;

/**
 * What a player holds of a village. With a book and quill, sneaking, a resident writes the village's
 * ledger: a book that rewrites itself every time it is opened (people, store, the current build).
 * Any other stack handed over sneaking goes to the shared store; the residents remember the gift,
 * which makes vanilla trades cheaper, and a generous donor is given the village's certificate of honour.
 * Vanilla items with names and lore only, so a client needs no mod.
 */
final class PlayerGoods {
    private PlayerGoods(){}
    static final String LEDGER="Ведомость ";
    /** Items given before the village names a donor an honorary resident. */
    static final long HONOUR=128;

    // --- handing things to a resident ------------------------------------------------------------
    static InteractionResult useOnResident(VillageRuntime runtime,ServerPlayer player,InteractionHand hand,Villager resident){
        if(!player.isShiftKeyDown()||resident.isBaby())return InteractionResult.PASS;
        Village v=village(runtime,resident);
        if(v==null||v.level!=player.level())return InteractionResult.PASS;
        ItemStack held=player.getItemInHand(hand);
        if(held.isEmpty())return InteractionResult.PASS;
        if(held.is(Items.WRITABLE_BOOK)){
            if(!AutonomousVillagesMod.CONFIG.ledger)return InteractionResult.PASS;
            held.shrink(1);
            var ledger=ledger(v);
            if(!player.getInventory().add(ledger))player.drop(ledger,false);
            resident.getLookControl().setLookAt(player);
            v.level.playSound(null,resident.blockPosition(),SoundEvents.VILLAGER_WORK_CARTOGRAPHER,SoundSource.NEUTRAL,1f,1f);
            player.sendSystemMessage(Component.literal("["+v.state.name+"] ").withStyle(net.minecraft.ChatFormatting.GOLD)
                    .append(Component.literal(v.presentation.personName(resident.getUUID())+" заполнил ведомость деревни. Открой её — она всегда свежая.").withStyle(net.minecraft.ChatFormatting.YELLOW)));
            return InteractionResult.SUCCESS;
        }
        if(!AutonomousVillagesMod.CONFIG.donations||!donatable(held))return InteractionResult.PASS;
        if(v.stock.chests().isEmpty()){
            player.sendSystemMessage(Component.literal("["+v.state.name+"] У деревни ещё нет общего сундука.").withStyle(net.minecraft.ChatFormatting.GRAY));
            return InteractionResult.SUCCESS;
        }
        int offered=held.getCount();Item item=held.getItem();
        ItemStack rest=v.stock.put(held.copy());
        int given=offered-rest.getCount();
        if(given<=0){
            player.sendSystemMessage(Component.literal("["+v.state.name+"] Склад полон.").withStyle(net.minecraft.ChatFormatting.GRAY));
            return InteractionResult.SUCCESS;
        }
        player.setItemInHand(hand,rest);
        thank(v,player,resident,item,given);
        return InteractionResult.SUCCESS;
    }
    /** Books, name tags and the village's own papers are never taken by mistake. */
    static boolean donatable(ItemStack s){
        return !s.is(Items.WRITABLE_BOOK)&&!s.is(Items.WRITTEN_BOOK)&&!s.is(Items.NAME_TAG)&&!s.is(Items.KNOWLEDGE_BOOK)&&!s.has(DataComponents.CUSTOM_NAME);
    }
    /** Minor goodwill per gift, a little more for a bigger one: four items buy one point, 20 at most. */
    static int goodwill(int items){return Math.clamp((items+3)/4,1,20);}
    private static void thank(Village v,ServerPlayer player,Villager resident,Item item,int given){
        UUID id=player.getUUID();
        long before=v.state.donations.getOrDefault(id,0L),total=before+given;
        v.state.donations.put(id,total);v.state.donorNames.put(id,player.getGameProfile().name());
        for(UUID r:v.state.residents)if(v.level.getEntity(r) instanceof Villager villager)villager.getGossips().add(id,GossipType.MINOR_POSITIVE,goodwill(given));
        resident.getLookControl().setLookAt(player);
        v.level.playSound(null,resident.blockPosition(),SoundEvents.VILLAGER_YES,SoundSource.NEUTRAL,1f,1f);
        v.level.sendParticles(ParticleTypes.HAPPY_VILLAGER,resident.getX(),resident.getY()+2.1,resident.getZ(),6,.3,.2,.3,0);
        player.sendSystemMessage(Component.literal("["+v.state.name+"] ").withStyle(net.minecraft.ChatFormatting.GOLD)
                .append(Component.literal(v.presentation.personName(resident.getUUID())+" относит на склад: ").withStyle(net.minecraft.ChatFormatting.YELLOW))
                .append(Component.translatable(item.getDescriptionId()).withStyle(net.minecraft.ChatFormatting.YELLOW))
                .append(Component.literal(" ×"+given+". Спасибо!").withStyle(net.minecraft.ChatFormatting.YELLOW)));
        v.chronicle.add("donated",given);
        // Only sizeable gifts go into the chronicle: a handful at a time must not push out its history.
        if(given>=16)v.chronicle.event(player.getGameProfile().name()+" принёс на склад "+given+" "+BuiltInRegistries.ITEM.getKey(item).getPath());
        if(before<HONOUR&&total>=HONOUR&&v.state.honoured.add(id)){
            for(UUID r:v.state.residents)if(v.level.getEntity(r) instanceof Villager villager)villager.getGossips().add(id,GossipType.MAJOR_POSITIVE,20);
            var honour=certificate(v,player.getGameProfile().name());
            if(!player.getInventory().add(honour))player.drop(honour,false);
            v.chronicle.announce(player.getGameProfile().name()+" — почётный житель деревни "+v.state.name+"!");
            v.level.playSound(null,resident.blockPosition(),SoundEvents.PLAYER_LEVELUP,SoundSource.PLAYERS,1f,1f);
        }
        VillageRequests.delivered(v,player,resident,item,given);
        v.runtime.save();
    }
    /** The certificate of honour: paper with the village's name, a glint and the date. */
    static ItemStack certificate(Village v,String player){
        var paper=new ItemStack(Items.PAPER);
        paper.set(DataComponents.CUSTOM_NAME,Component.literal("Грамота почётного жителя").withStyle(net.minecraft.ChatFormatting.GOLD));
        paper.set(DataComponents.LORE,new ItemLore(List.of(
                Component.literal("Деревня "+v.state.name+" благодарит").withStyle(net.minecraft.ChatFormatting.GRAY),
                Component.literal(player+" за помощь складу").withStyle(net.minecraft.ChatFormatting.GRAY),
                Component.literal(v.chronicle.day()).withStyle(net.minecraft.ChatFormatting.DARK_GRAY))));
        paper.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE,true);
        paper.set(DataComponents.RARITY,Rarity.RARE);
        return paper;
    }

    // --- the ledger -------------------------------------------------------------------------------
    static boolean isLedger(ItemStack s){
        if(!s.is(Items.WRITTEN_BOOK))return false;
        var content=s.get(DataComponents.WRITTEN_BOOK_CONTENT);
        return content!=null&&Chronicle.BOOK_AUTHOR.equals(content.author())&&content.title().raw().startsWith(LEDGER);
    }
    static String ledgerVillage(String title){return title.startsWith(LEDGER)?title.substring(LEDGER.length()):null;}
    /** Opening a ledger first rewrites it from the village as it is now. */
    static void refresh(VillageRuntime runtime,ServerPlayer player,InteractionHand hand){
        if(!AutonomousVillagesMod.CONFIG.ledger)return;
        ItemStack held=player.getItemInHand(hand);
        if(!isLedger(held))return;
        String name=ledgerVillage(held.get(DataComponents.WRITTEN_BOOK_CONTENT).title().raw());
        Village v=runtime.villages.get(name.toLowerCase(Locale.ROOT));
        if(v==null)return;
        held.set(DataComponents.WRITTEN_BOOK_CONTENT,ledger(v).get(DataComponents.WRITTEN_BOOK_CONTENT));
        // The new pages must reach the client before the book opens on its copy.
        player.containerMenu.broadcastChanges();
    }
    static ItemStack ledger(Village v){
        var pages=new ArrayList<Filterable<Component>>();
        pages.add(page(summary(v)));
        pages.add(page(store(v)));
        pages.add(page(build(v)));
        for(var people:people(v))pages.add(page(people));
        var donors=donors(v);if(donors!=null)pages.add(page(donors));
        var book=new ItemStack(Items.WRITTEN_BOOK);
        String title=LEDGER+v.state.name;if(title.length()>WrittenBookContent.TITLE_MAX_LENGTH)title=title.substring(0,WrittenBookContent.TITLE_MAX_LENGTH);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT,new WrittenBookContent(Filterable.passThrough(title),Chronicle.BOOK_AUTHOR,0,pages,true));
        return book;
    }
    private static Filterable<Component> page(Component c){return Filterable.passThrough(c);}
    private static Component summary(Village v){
        var c=v.chronicle;
        return Component.literal(v.state.name+"\n"+c.day()+"\n\n"
                +"Жителей: "+v.state.residents.size()+"\n"
                +"Кроватей: "+v.index.beds.size()+" (свободно "+v.breeding.freeBeds()+")\n"
                +"Зданий: "+v.state.buildings.stream().filter(b->b.completed).count()+"\n"
                +"Рождений: "+c.total("births")+"\nПотерь: "+c.total("deaths")+"\n"
                +"Деревьев: "+c.total("trees")+"\nБлоков: "+c.total("placed")+"\n"
                +"Сейчас: "+v.town.stage);
    }
    /** The twelve biggest stacks of the store, by item, with names in the reader's language. */
    private static Component store(Village v){
        var counts=new HashMap<Item,Integer>();
        for(var chest:v.stock.chests())for(int i=0;i<chest.getContainerSize();i++){var s=chest.getItem(i);if(!s.isEmpty())counts.merge(s.getItem(),s.getCount(),Integer::sum);}
        MutableComponent out=Component.literal("Склад ("+v.stock.chests().size()+" сунд.)\n\n");
        if(counts.isEmpty())return out.append("пусто");
        counts.entrySet().stream().sorted(Map.Entry.<Item,Integer>comparingByValue().reversed()).limit(12)
                .forEach(e->out.append(Component.literal(e.getValue()+" ")).append(Component.translatable(e.getKey().getDescriptionId())).append("\n"));
        return out;
    }
    private static Component build(Village v){
        var project=v.town.active();
        if(project==null)return Component.literal("Стройка\n\nВсё построено.");
        var out=new StringBuilder("Стройка\n\n").append(kind(project.kind)).append(project.design.isEmpty()?"":"\n("+project.design+")")
                .append("\nготово ").append(v.town.built(project)).append(" из ").append(v.town.size(project)).append("\n\nНе хватает:\n");
        var request=v.state.request;
        if(request!=null)out.append("\nЗаказ игроку: ").append(VillageRequests.label(request.key)).append(" ×").append(VillageRequests.left(request)).append("\n");
        var missing=v.town.missing(project);
        if(missing.isEmpty())out.append("ничего");
        missing.entrySet().stream().sorted(Map.Entry.<String,Integer>comparingByValue().reversed()).limit(8)
                .forEach(e->out.append(VillagePresentation.NEEDS.getOrDefault(e.getKey(),e.getKey())).append(" ").append(e.getValue()).append("\n"));
        return Component.literal(out.toString());
    }
    static String kind(String kind){
        return switch(kind){case "house","native_house"->"Дом";case "well"->"Колодец";case "tower"->"Сторожевая вышка";case "storehouse"->"Амбар";
            case "civic"->"Мастерская";case "farm_plot"->"Поле";case "workshop"->"Мастерская";case "lamp"->"Фонарь";case "path"->"Тропа";
            case "forge","market","tavern"->{var l=Landmarks.label(kind);yield Character.toUpperCase(l.charAt(0))+l.substring(1);}default->kind;};
    }
    /** Residents with role and what they do, ten to a page, four pages at most. */
    private static List<Component> people(Village v){
        var lines=new ArrayList<String>();
        for(UUID id:v.state.residents){
            var w=v.workers.get(id);
            lines.add(v.presentation.personName(id)+", "+v.role(id)+(w==null?"":": "+shorten(w.status,28)));
        }
        var out=new ArrayList<Component>();
        for(int i=0;i<lines.size()&&out.size()<4;i+=10)
            out.add(Component.literal("Жители\n\n"+String.join("\n",lines.subList(i,Math.min(lines.size(),i+10)))));
        return out;
    }
    static String shorten(String s,int max){return s==null?"":s.length()<=max?s:s.substring(0,max-1)+"…";}
    private static Component donors(Village v){
        if(v.state.donations.isEmpty())return null;
        var out=new StringBuilder("Дарители\n\n");
        v.state.donations.entrySet().stream().sorted(Map.Entry.<UUID,Long>comparingByValue().reversed()).limit(10).forEach(e->{
            String name=v.state.donorNames.getOrDefault(e.getKey(),"гость");
            out.append(v.state.honoured.contains(e.getKey())?"★ ":"").append(name).append(": ").append(e.getValue()).append("\n");
        });
        return Component.literal(out.toString());
    }
    private static Village village(VillageRuntime runtime,Villager resident){
        for(Village v:runtime.villages.values())if(v.state.active&&v.state.residents.contains(resident.getUUID()))return v;
        return null;
    }
}
