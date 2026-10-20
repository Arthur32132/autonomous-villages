package dev.autonomousvillages;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.ToIntBiFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

/**
 * Once a day in daylight a merchant caravan comes to the bell and swaps the store's surplus (sand, cactus,
 * cobblestone, bread...) for what holds up beds and houses and the village cannot get itself: wool while beds
 * wait and no sheep or shears give it, iron for shears, logs where no trees grow. Goods are only exchanged: the
 * payment leaves the store whole or not at all, and goods that do not fit are set down at the bell. The trader
 * is for show and leaves on his own.
 */
final class Caravan {
    private Caravan(){}
    static final int WOOL_MAX=9,IRON_WANTED=2,LOGS_TREELESS=24,LOGS_SHORT=8,WOOD_LOW=8,STAY=6000;
    /** Daylight hours the caravan may come in; a deal short of what the village wants waits for the afternoon. */
    static final long OPENS=1000,CLOSES=11000,AFTERNOON=6000;

    record Word(String one,String few,String many){
        static Word mass(String word){return new Word(word,word,word);}
        String of(long n){return MorningNews.plural(n,one,few,many);}
    }
    /** What the caravan brings and its price in the caravan's coin (a piece of sand is one). */
    record Ware(Item item,int price,Word word){}
    /** What the village pays with: how many the store keeps for itself and what each piece is worth. */
    record Pay(Item item,int keep,int value,Word word){}
    /** Wool, iron, logs: the order the village is served in, and of {@link Wants}. */
    static final List<Ware> WARES=List.of(new Ware(Items.WOOL.white(),4,Word.mass("шерсти")),new Ware(Items.IRON_INGOT,8,new Word("слиток железа","слитка железа","слитков железа")),
            new Ware(Items.OAK_LOG,2,new Word("бревно","бревна","брёвен")));
    /** Paid from first to last: the cheap bulk first, emeralds only when nothing else covers the price. */
    static final List<Pay> PAY;
    static{
        Word egg=new Word("яйцо","яйца","яиц"),seed=new Word("семя","семени","семян"),sapling=new Word("саженец","саженца","саженцев");
        var pay=new ArrayList<>(List.of(new Pay(Items.SAND,32,1,Word.mass("песка")),new Pay(Items.COBBLESTONE,96,1,Word.mass("булыжника")),
                new Pay(Items.DIRT,16,1,Word.mass("земли")),new Pay(Items.ROTTEN_FLESH,0,1,Word.mass("гнилой плоти")),
                new Pay(Items.FEATHER,0,1,new Word("перо","пера","перьев")),new Pay(Items.EGG,0,1,egg),new Pay(Items.BROWN_EGG,0,1,egg),new Pay(Items.BLUE_EGG,0,1,egg),
                new Pay(Items.WHEAT_SEEDS,32,1,seed),new Pay(Items.BEETROOT_SEEDS,32,1,seed),new Pay(Items.WHEAT,32,1,Word.mass("пшеницы"))));
        for(Item s:List.of(Items.OAK_SAPLING,Items.SPRUCE_SAPLING,Items.BIRCH_SAPLING,Items.JUNGLE_SAPLING,Items.ACACIA_SAPLING,Items.DARK_OAK_SAPLING,
                Items.CHERRY_SAPLING,Items.PALE_OAK_SAPLING))pay.add(new Pay(s,16,1,sapling));
        pay.addAll(List.of(new Pay(Items.CACTUS,8,2,new Word("кактус","кактуса","кактусов")),new Pay(Items.FLINT,0,2,new Word("кремень","кремня","кремней")),
                new Pay(Items.PAPER,0,2,new Word("лист бумаги","листа бумаги","листов бумаги")),new Pay(Items.BREAD,24,3,Word.mass("хлеба")),
                new Pay(Items.LEATHER,0,4,Word.mass("кожи")),new Pay(Items.EMERALD,0,16,new Word("изумруд","изумруда","изумрудов"))));
        PAY=List.copyOf(pay);
    }
    /** Plain stacks only (no name, no enchantment): what is taken is exactly what a rollback puts back. One instance per item for the store's count cache. */
    static final Map<Item,Predicate<ItemStack>> PLAIN=new HashMap<>();
    static{for(var p:PAY)PLAIN.put(p.item(),s->s.is(p.item())&&s.getComponentsPatch().isEmpty());}

    record Wants(int wool,int iron,int logs){
        int of(int ware){return switch(ware){case 0->wool;case 1->iron;default->logs;};}
        boolean none(){return wool<=0&&iron<=0&&logs<=0;}
    }
    /** Goods bought and payment given, per item. */
    record Deal(Map<Item,Integer> goods,Map<Item,Integer> payment){
        boolean empty(){return goods.isEmpty();}
        int price(){int n=0;for(var w:WARES)n+=goods.getOrDefault(w.item(),0)*w.price();return n;}
        int paid(){int n=0;for(var p:PAY)n+=payment.getOrDefault(p.item(),0)*p.value();return n;}
        boolean covers(Wants wants){
            for(int i=0;i<WARES.size();i++)if(goods.getOrDefault(WARES.get(i).item(),0)<wants.of(i))return false;
            return true;
        }
    }

    /**
     * What the village cannot get itself. Wool for the beds still to make (3 each, 9 at most) while beds wait and
     * there are fewer than two sheep about or no shears; 2 iron for shears while beds wait without them; logs while
     * wood (in planks, a log is 4) is short of {@code woodNeed}: up to 24 where no tree grows, else up to 8 once the
     * store is nearly bare of wood.
     */
    static Wants wants(int needBed,int bedItems,int wool,boolean shears,int sheepNear,int iron,boolean treeless,int wood,int woodNeed){
        boolean beds=needBed>bedItems;
        int w=beds&&(sheepNear<2||!shears)?Math.clamp(3L*(needBed-bedItems)-wool,0,WOOL_MAX):0;
        int i=beds&&!shears&&iron<IRON_WANTED?IRON_WANTED-Math.max(0,iron):0;
        int lack=Math.max(0,woodNeed-wood);
        int logs=Math.min(treeless?LOGS_TREELESS:wood<WOOD_LOW?LOGS_SHORT:0,(lack+3)/4);
        return new Wants(w,i,logs);
    }

    /**
     * The day's deal: the goods the store's surplus above its reserves pays for, wool before iron before logs,
     * paid from the first payment item on until the price is met (never less, at most one piece's worth more).
     * Nothing wanted or nothing to spare is an empty deal.
     */
    static Deal deal(Map<Item,Integer> store,Wants wants){
        var spare=new LinkedHashMap<Pay,Integer>();long budget=0;
        for(var p:PAY){int n=Math.max(0,store.getOrDefault(p.item(),0)-p.keep());if(n>0){spare.put(p,n);budget+=(long)n*p.value();}}
        var goods=new LinkedHashMap<Item,Integer>();long price=0;
        for(int i=0;i<WARES.size();i++){
            var w=WARES.get(i);
            int n=(int)Math.min(Math.max(0,wants.of(i)),(budget-price)/w.price());
            if(n>0){goods.put(w.item(),n);price+=(long)n*w.price();}
        }
        var payment=new LinkedHashMap<Item,Integer>();long due=price;
        for(var e:spare.entrySet()){
            if(due<=0)break;
            var p=e.getKey();int n=(int)Math.min(e.getValue(),(due+p.value()-1)/p.value());
            payment.put(p.item(),n);due-=(long)n*p.value();
        }
        return new Deal(goods,payment);
    }
    /** Once a game day, in daylight. */
    static boolean due(long day,long hour,long lastDay){return lastDay<day&&hour>=OPENS&&hour<CLOSES;}
    /** A deal that serves every want is struck at once; a smaller one waits for the afternoon, while the store may still fill up. */
    static boolean settles(Deal deal,Wants wants,long hour){return !deal.empty()&&(deal.covers(wants)||hour>=AFTERNOON);}

    /**
     * Takes the payment out of the store item by item. When any item comes up short, everything already taken goes
     * back through {@code giveBack} (whole stacks) and there is no trade. True when all of it was taken.
     */
    static boolean pay(Map<Item,Integer> payment,ToIntBiFunction<Item,Integer> take,Consumer<ItemStack> giveBack){
        var taken=new LinkedHashMap<Item,Integer>();
        for(var e:payment.entrySet()){
            int n=take.applyAsInt(e.getKey(),e.getValue());
            if(n>0)taken.merge(e.getKey(),n,Integer::sum);
            if(n<e.getValue()){taken.forEach((item,k)->stacks(item,k).forEach(giveBack));return false;}
        }
        return true;
    }
    /** {@code n} pieces as stacks no bigger than the item's own stack size. */
    static List<ItemStack> stacks(Item item,int n){
        var out=new ArrayList<ItemStack>();int max=Math.max(1,new ItemStack(item).getMaxStackSize());
        for(int left=n;left>0;left-=max)out.add(new ItemStack(item,Math.min(left,max)));
        return out;
    }
    /** "Пришёл караван: 6 шерсти и 16 брёвен за 96 песка". */
    static String line(Deal deal){
        var goods=new ArrayList<String>();
        for(var w:WARES){int n=deal.goods().getOrDefault(w.item(),0);if(n>0)goods.add(w.word().of(n));}
        var paid=new LinkedHashMap<Word,Integer>();
        for(var p:PAY){int n=deal.payment().getOrDefault(p.item(),0);if(n>0)paid.merge(p.word(),n,Integer::sum);}
        return "Пришёл караван: "+join(goods)+" за "+join(paid.entrySet().stream().map(e->e.getKey().of(e.getValue())).toList());
    }
    static String join(List<String> parts){
        if(parts.size()<2)return String.join("",parts);
        return String.join(", ",parts.subList(0,parts.size()-1))+" и "+parts.getLast();
    }

    // --- in the world -----------------------------------------------------------------------------
    /** From LifeEvents every ten seconds: the day's caravan, once it is due and the village wants something it can pay for. */
    static void tick(Village v){
        if(!AutonomousVillagesMod.CONFIG.lifeEvents||!AutonomousVillagesMod.CONFIG.caravans||!v.state.active)return;
        long clock=v.level.getOverworldClockTime(),day=Math.floorDiv(clock,24000),hour=Math.floorMod(clock,24000);
        if(!due(day,hour,v.state.caravanDay))return;
        BlockPos hub=v.meetingPoint();
        if(!v.level.hasChunkAt(hub)||v.stock.chests().isEmpty())return;
        var s=v.planner.snapshot();
        boolean beds=s.need("bed")>s.bedItems();
        var wants=wants(s.need("bed"),s.bedItems(),s.wool(),s.shears(),beds?sheepNear(v,hub):0,s.iron()+s.rawIron(),v.index.trees.isEmpty(),s.wood(),
                VillagePlanner.WOOD_RESERVE+s.need("planks")+4*s.need("logs"));
        if(wants.none())return;
        var store=new HashMap<Item,Integer>();
        for(var p:PAY)store.put(p.item(),v.stock.count(PLAIN.get(p.item())));
        var deal=deal(store,wants);
        if(!settles(deal,wants,hour))return;
        // Kept in the save: a restart brings no second caravan, and a payment that fell short is not tried again today.
        v.state.caravanDay=day;
        if(!pay(deal.payment(),(item,n)->v.stock.consume(PLAIN.get(item),n),back->store(v,hub,back)))return;
        for(var e:deal.goods().entrySet())for(var goods:stacks(e.getKey(),e.getValue()))store(v,hub,goods);
        trader(v,hub);
        v.chronicle.add("caravans",1);
        v.chronicle.announce(line(deal));
    }
    private static int sheepNear(Village v,BlockPos hub){
        return v.level.getEntitiesOfClass(Sheep.class,new AABB(hub).inflate(48),x->x.isAlive()&&v.canWork(x.blockPosition())).size();
    }
    /** Into the store; what does not fit is set down at the bell, never lost. */
    private static void store(Village v,BlockPos hub,ItemStack goods){
        LandmarkGoods.keep(goods,v.stock::put,rest->{
            var item=new ItemEntity(v.level,hub.getX()+.5,hub.getY()+1.1,hub.getZ()+.5,rest);
            item.setDefaultPickUpDelay();v.level.addFreshEntity(item);
        });
    }
    /** The caravan's merchant by the bell: one already about stays a while longer, else one arrives; he leaves on his own. */
    private static void trader(Village v,BlockPos hub){
        var about=v.level.getEntitiesOfClass(WanderingTrader.class,new AABB(hub).inflate(16),WanderingTrader::isAlive);
        if(!about.isEmpty()){
            var t=about.getFirst();
            if(t.getDespawnDelay()>0)t.setDespawnDelay(Math.max(t.getDespawnDelay(),STAY));
        }else{
            var t=EntityTypes.WANDERING_TRADER.spawn(v.level,spot(v,hub),EntitySpawnReason.EVENT);
            if(t!=null)t.setDespawnDelay(STAY);
        }
        v.level.playSound(null,hub,SoundEvents.WANDERING_TRADER_YES,SoundSource.NEUTRAL,1f,1f);
        v.level.sendParticles(ParticleTypes.HAPPY_VILLAGER,hub.getX()+.5,hub.getY()+1.5,hub.getZ()+.5,10,1.5,.6,1.5,0);
    }
    /** Ground two blocks beside the bell, not a roof; above the bell when none is near its height. */
    private static BlockPos spot(Village v,BlockPos hub){
        for(var d:new Direction[]{Direction.EAST,Direction.SOUTH,Direction.WEST,Direction.NORTH}){
            BlockPos c=hub.relative(d,2);
            if(!v.level.hasChunkAt(c))continue;
            int y=v.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,c.getX(),c.getZ());
            if(Math.abs(y-hub.getY())<=3)return new BlockPos(c.getX(),y,c.getZ());
        }
        return hub.above();
    }
}
