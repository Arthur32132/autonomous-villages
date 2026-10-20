package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.ai.gossip.GossipType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.WrittenBookContent;

/**
 * What a player holds of a village. With a book and quill, sneaking, a resident writes the village's
 * ledger: a book that rewrites itself every time it is opened (people, store, the current build).
 * A stack of something the village uses, handed over sneaking, goes to the shared store (leaving room for
 * the workers' own deposits; anything else stays with the player); the residents remember the gift,
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
        // The resident turns down what the village has no use for: it stays in the player's hand.
        if(!wanted(v,held)){
            v.level.playSound(null,resident.blockPosition(),SoundEvents.VILLAGER_NO,SoundSource.NEUTRAL,1f,1f);
            player.sendSystemMessage(Component.literal("["+v.state.name+"] Деревне это не нужно: берут стройматериалы, еду, топливо, семена, инструменты.").withStyle(net.minecraft.ChatFormatting.GRAY));
            return InteractionResult.SUCCESS;
        }
        int offered=room(held.getCount(),topUp(v,held),v.stock.freeSlots(),held.getMaxStackSize());
        Item item=held.getItem();
        int given=offered<=0?0:offered-v.stock.put(held.copyWithCount(offered)).getCount();
        if(given<=0){
            player.sendSystemMessage(Component.literal("["+v.state.name+"] Склад почти полон: место оставлено для работников.").withStyle(net.minecraft.ChatFormatting.GRAY));
            return InteractionResult.SUCCESS;
        }
        held.shrink(given);
        thank(v,player,resident,item,given);
        return InteractionResult.SUCCESS;
    }
    /** Books, name tags and the village's own papers are never taken by mistake. */
    static boolean donatable(ItemStack s){
        return !s.is(Items.WRITABLE_BOOK)&&!s.is(Items.WRITTEN_BOOK)&&!s.is(Items.NAME_TAG)&&!s.is(Items.KNOWLEDGE_BOOK)&&!s.has(DataComponents.CUSTOM_NAME);
    }
    /** Empty slots of the store a gift never takes, so that the workers can always bring their goods home. */
    static final int RESERVE=9;
    /** Pieces the village's own designs are built of. */
    static final Set<String> BUILDING=Set.of("planks","logs","door","bed","torch","cobblestone","stairs","slab","fence","trapdoor","stone_stairs",
            "stone_slab","stone_wall","glass_pane","ladder","chest","crafting_table","furnace","fence_gate");
    /** Items the jobs work with (VillageCrafting, Supplies, CompostJob, FarmJob), besides those matched by tag or by building key. */
    static final Set<Item> USED=Set.of(Items.STICK,Items.COBBLESTONE,Items.IRON_INGOT,Items.IRON_NUGGET,Items.COAL,Items.CHARCOAL,Items.WHEAT,Items.BREAD,
            Items.CARROT,Items.POTATO,Items.BEETROOT,Items.TORCH,Items.LANTERN,Items.SAND,Items.RED_SAND,Items.GLASS,Items.SHEARS,Items.SHIELD,Items.BONE_MEAL,
            Items.WHEAT_SEEDS,Items.BEETROOT_SEEDS,Items.PUMPKIN_SEEDS,Items.MELON_SEEDS,Items.EMERALD,Items.APPLE,Items.ANDESITE,Items.DIORITE,Items.GRANITE,
            Items.TUFF,Items.STONE,Items.MOSSY_COBBLESTONE,Items.DEEPSLATE,Items.SANDSTONE,Items.RED_SANDSTONE,Items.SMOOTH_SANDSTONE,Items.CUT_SANDSTONE);
    static final List<TagKey<Item>> USED_TAGS=List.of(ItemTags.PLANKS,ItemTags.LOGS_THAT_BURN,ItemTags.STONE_TOOL_MATERIALS,ItemTags.WOOL,ItemTags.SAPLINGS,
            ItemTags.VILLAGER_PLANTABLE_SEEDS,ItemTags.PICKAXES,ItemTags.AXES,ItemTags.SWORDS,ItemTags.HOES,ItemTags.SHOVELS);
    /** What the village works with: building stuff and its materials, fuel, food and what the tavern cooks, seeds, saplings, flowers, tools, iron, wool, emeralds. */
    static boolean used(Item item){
        if(USED.contains(item)||LandmarkGoods.KITCHEN.containsKey(item)||LandmarkGoods.KITCHEN.containsValue(item))return true;
        var holder=item.builtInRegistryHolder();
        for(var tag:USED_TAGS)if(holder.is(tag))return true;
        return item instanceof BlockItem b&&(BUILDING.contains(Projects.materialKey(b.getBlock().defaultBlockState()))||b.getBlock().defaultBlockState().is(BlockTags.SMALL_FLOWERS));
    }
    static boolean used(ItemStack s){return donatable(s)&&used(s.getItem());}
    /** Something the village uses, or a block the current build is waiting for (a request, a template's hay or terracotta). */
    static boolean wanted(Village v,ItemStack s){
        if(used(s))return true;
        if(!donatable(s))return false;
        String key=VillageRequests.key(s.getItem());
        var project=v.town.active();
        return key!=null&&project!=null&&v.town.missing(project).containsKey(key);
    }
    /** How many of a gift go in: topping up the store's stacks of it, then empty slots, but never the last RESERVE of them. */
    static int room(int offered,int topUp,int freeSlots,int perSlot){
        return (int)Math.max(0,Math.min(offered,(long)topUp+(long)Math.max(0,freeSlots-RESERVE)*perSlot));
    }
    private static int topUp(Village v,ItemStack gift){
        int n=0;
        for(var chest:v.stock.chests())for(int i=0;i<chest.getContainerSize();i++){
            var s=chest.getItem(i);if(!s.isEmpty()&&ItemStack.isSameItemSameComponents(s,gift))n+=Math.max(0,s.getMaxStackSize()-s.getCount());
        }
        return n;
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
        // No save here: the periodic snapshot (every minute, written off the server thread) keeps the tally.
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
        for(var sheet:List.of(summary(v),store(v),build(v),people(v),donors(v)))for(var c:sheet)pages.add(page(c));
        var book=new ItemStack(Items.WRITTEN_BOOK);
        String title=LEDGER+v.state.name;if(title.length()>WrittenBookContent.TITLE_MAX_LENGTH)title=title.substring(0,WrittenBookContent.TITLE_MAX_LENGTH);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT,new WrittenBookContent(Filterable.passThrough(title),Chronicle.BOOK_AUTHOR,0,pages,true));
        return book;
    }
    private static Filterable<Component> page(Component c){return Filterable.passThrough(c);}
    /** Book lines an item name in the reader's language is given: the server cannot measure it. */
    static final int ITEM_LINES=2;
    private static List<Component> summary(Village v){
        var c=v.chronicle;
        return BookLayout.pages(v.state.name+"\n"+c.day(),lines("Жителей: "+v.state.residents.size(),
                "Кроватей: "+v.index.beds.size()+" (свободно "+v.breeding.freeBeds()+")",
                "Зданий: "+v.state.buildings.stream().filter(b->b.completed).count(),
                "Рождений: "+c.total("births"),"Потерь: "+c.total("deaths"),"Деревьев: "+c.total("trees"),"Блоков: "+c.total("placed"),
                "Сейчас: "+v.town.stage),2);
    }
    private static List<BookLayout.Entry> lines(String... lines){return Arrays.stream(lines).map(BookLayout::entry).toList();}
    /** The twelve biggest stacks of the store, by item, with names in the reader's language. */
    private static List<Component> store(Village v){
        var counts=new HashMap<Item,Integer>();
        for(var chest:v.stock.chests())for(int i=0;i<chest.getContainerSize();i++){var s=chest.getItem(i);if(!s.isEmpty())counts.merge(s.getItem(),s.getCount(),Integer::sum);}
        var entries=new ArrayList<BookLayout.Entry>();
        if(counts.isEmpty())entries.add(BookLayout.entry("пусто"));
        counts.entrySet().stream().sorted(Map.Entry.<Item,Integer>comparingByValue().reversed()).limit(12)
                .forEach(e->entries.add(BookLayout.entry(Component.literal(e.getValue()+" ").append(Component.translatable(e.getKey().getDescriptionId())),ITEM_LINES)));
        if(counts.size()>12)entries.add(BookLayout.entry(BookLayout.more(counts.size()-12)+" видов"));
        return BookLayout.pages("Склад ("+v.stock.chests().size()+" сунд.)",entries,3);
    }
    private static List<Component> build(Village v){
        var project=v.town.active();
        if(project==null)return BookLayout.pages("Стройка",lines("Всё построено."),1);
        var entries=new ArrayList<>(lines(kind(project.kind)));
        if(!project.design.isEmpty())entries.add(BookLayout.entry("("+project.design+")"));
        entries.add(BookLayout.entry("готово "+v.town.built(project)+" из "+v.town.size(project)));
        var request=v.state.request;
        if(request!=null&&VillageRequests.enabled(AutonomousVillagesMod.CONFIG))
            entries.add(BookLayout.entry("Заказ игроку: "+VillageRequests.label(request.key)+" ×"+VillageRequests.left(request)));
        entries.addAll(lines("","Не хватает:"));
        var missing=v.town.missing(project);
        if(missing.isEmpty())entries.add(BookLayout.entry("ничего"));
        missing.entrySet().stream().sorted(Map.Entry.<String,Integer>comparingByValue().reversed())
                .forEach(e->entries.add(BookLayout.entry(VillageRequests.label(e.getKey())+" "+e.getValue())));
        return BookLayout.pages("Стройка",entries,3);
    }
    static String kind(String kind){
        return switch(kind){case "house","native_house"->"Дом";case "well"->"Колодец";case "tower"->"Сторожевая вышка";case "storehouse"->"Амбар";
            case "civic"->"Мастерская";case "farm_plot"->"Поле";case "workshop"->"Мастерская";case "lamp"->"Фонарь";case "path"->"Тропа";
            case "forge","market","tavern"->{var l=Landmarks.label(kind);yield Character.toUpperCase(l.charAt(0))+l.substring(1);}default->kind;};
    }
    /** Pages a long list may take before it ends with "…и ещё N". */
    static final int PEOPLE_PAGES=40,DONOR_PAGES=3;
    /** Every resident with role and what they do, as many pages as it takes. */
    private static List<Component> people(Village v){
        var entries=new ArrayList<BookLayout.Entry>();
        for(UUID id:v.state.residents){
            var w=v.workers.get(id);
            entries.add(BookLayout.entry(v.presentation.personName(id)+", "+v.role(id)+(w==null?"":": "+shorten(w.status,28))));
        }
        return entries.isEmpty()?List.of():BookLayout.pages("Жители",entries,PEOPLE_PAGES);
    }
    static String shorten(String s,int max){return s==null?"":s.length()<=max?s:s.substring(0,max-1)+"…";}
    private static List<Component> donors(Village v){
        if(v.state.donations.isEmpty())return List.of();
        var entries=new ArrayList<BookLayout.Entry>();
        v.state.donations.entrySet().stream().sorted(Map.Entry.<UUID,Long>comparingByValue().reversed()).forEach(e->{
            String name=v.state.donorNames.getOrDefault(e.getKey(),"гость");
            entries.add(BookLayout.entry((v.state.honoured.contains(e.getKey())?"★ ":"")+name+": "+e.getValue()));
        });
        return BookLayout.pages("Дарители",entries,DONOR_PAGES);
    }
    private static Village village(VillageRuntime runtime,Villager resident){
        for(Village v:runtime.villages.values())if(v.state.active&&v.state.residents.contains(resident.getUUID()))return v;
        return null;
    }
}
