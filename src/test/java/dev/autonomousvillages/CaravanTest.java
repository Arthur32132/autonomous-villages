package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CaravanTest {
    @BeforeAll static void bootstrapRegistries(){McBootstrap.init();}

    static final Item WOOL=Items.WOOL.white();
    static Caravan.Wants want(int wool,int iron,int logs){return new Caravan.Wants(wool,iron,logs);}
    static Map<Item,Integer> store(Object... pairs){
        var out=new HashMap<Item,Integer>();
        for(int i=0;i<pairs.length;i+=2)out.put((Item)pairs[i],(Integer)pairs[i+1]);
        return out;
    }

    @Test void woolOnlyWhileBedsWaitWithoutSheepOrShears(){
        // Two beds to make, no wool, no sheep, no shears: six wool and the iron for shears.
        assertEquals(want(6,2,0),Caravan.wants(2,0,0,false,0,0,false,100,64));
        // Wool the store already has counts, and a caravan brings nine at most.
        assertEquals(6-4,Caravan.wants(2,0,4,false,0,0,false,100,64).wool());
        assertEquals(9,Caravan.wants(5,0,0,false,0,0,false,100,64).wool());
        assertEquals(0,Caravan.wants(2,0,6,false,0,0,false,100,64).wool());
        // Bed items in the store cover the beds: nothing for them.
        assertEquals(want(0,0,0),Caravan.wants(2,2,0,false,0,0,false,100,64));
        assertEquals(want(0,0,0),Caravan.wants(0,0,0,false,0,0,false,100,64));
        // Shears and a flock of two or more: the shepherd brings the wool.
        assertEquals(want(0,0,0),Caravan.wants(2,0,0,true,2,0,false,100,64));
        // Shears but fewer than two sheep, or sheep but no shears: wool still comes.
        assertEquals(want(6,0,0),Caravan.wants(2,0,0,true,1,0,false,100,64));
        assertEquals(want(6,2,0),Caravan.wants(2,0,0,false,5,0,false,100,64));
    }

    @Test void ironOnlyForShearsWhileBedsWait(){
        assertEquals(2,Caravan.wants(1,0,3,false,0,0,false,100,64).iron());
        assertEquals(1,Caravan.wants(1,0,3,false,0,1,false,100,64).iron());
        assertEquals(0,Caravan.wants(1,0,3,false,0,2,false,100,64).iron());
        assertEquals(0,Caravan.wants(1,0,0,true,0,0,false,100,64).iron());
        assertEquals(0,Caravan.wants(1,1,0,false,0,0,false,100,64).iron());
    }

    @Test void logsWhereNoTreeGrowsOrTheStoreIsBare(){
        // A desert: up to 24 logs while wood is short of the need (a log is four planks).
        assertEquals(24,Caravan.wants(0,0,0,true,0,0,true,0,200).logs());
        assertEquals(16,Caravan.wants(0,0,0,true,0,0,true,0,64).logs());
        assertEquals(1,Caravan.wants(0,0,0,true,0,0,true,62,64).logs());
        assertEquals(0,Caravan.wants(0,0,0,true,0,0,true,64,64).logs());
        // With trees about: only once the store is nearly bare of wood, and 8 at most.
        assertEquals(8,Caravan.wants(0,0,0,true,0,0,false,0,64).logs());
        assertEquals(8,Caravan.wants(0,0,0,true,0,0,false,7,64).logs());
        assertEquals(0,Caravan.wants(0,0,0,true,0,0,false,8,64).logs());
        assertEquals(1,Caravan.wants(0,0,0,true,0,0,false,0,4).logs());
        assertEquals(0,Caravan.wants(0,0,0,true,0,0,false,0,0).logs());
    }

    @Test void theVillagePaysOnlyFromAboveItsReserves(){
        var reserves=Map.of(Items.SAND,32,Items.CACTUS,8,Items.DIRT,16,Items.COBBLESTONE,96,Items.BREAD,24,Items.WHEAT,32,Items.OAK_SAPLING,16,
                Items.WHEAT_SEEDS,32,Items.BEETROOT_SEEDS,32,Items.PALE_OAK_SAPLING,16);
        for(var e:reserves.entrySet()){
            assertTrue(Caravan.deal(store(e.getKey(),e.getValue()),want(9,2,24)).empty(),e.getKey()+" at its reserve");
            var deal=Caravan.deal(store(e.getKey(),e.getValue()+40),want(9,2,24));
            assertFalse(deal.empty(),e.getKey()+" above its reserve");
            assertEquals(Set.of(e.getKey()),deal.payment().keySet());
            assertTrue(deal.payment().get(e.getKey())<=40,e.getKey()+" paid past its reserve");
        }
        // What the village has no use for goes in full.
        for(Item spare:List.of(Items.ROTTEN_FLESH,Items.EGG,Items.BROWN_EGG,Items.LEATHER,Items.FEATHER,Items.FLINT,Items.PAPER,Items.EMERALD)){
            var deal=Caravan.deal(store(spare,64),want(1,0,0));
            assertEquals(Map.of(WOOL,1),deal.goods(),spare.toString());
        }
        // Goods the caravan does not take are not money.
        assertTrue(Caravan.deal(store(Items.DIAMOND,64,Items.OAK_LOG,500,Items.SAND,32),want(9,2,24)).empty());
    }

    @Test void woolFirstThenIronThenLogs(){
        var deal=Caravan.deal(store(Items.SAND,32+14),want(3,2,10));
        // 14 sand: three wool (12), no iron (8 does not fit in the 2 left), a log.
        assertEquals(Map.of(WOOL,3,Items.OAK_LOG,1),deal.goods());
        assertEquals(Map.of(Items.SAND,14),deal.payment());
        assertFalse(deal.covers(want(3,2,10)));
        assertEquals(Map.of(WOOL,3,Items.IRON_INGOT,2,Items.OAK_LOG,1),Caravan.deal(store(Items.SAND,32+30),want(3,2,10)).goods());
        var rich=Caravan.deal(store(Items.SAND,500,Items.EMERALD,5),want(9,2,24));
        assertEquals(Map.of(WOOL,9,Items.IRON_INGOT,2,Items.OAK_LOG,24),rich.goods());
        assertTrue(rich.covers(want(9,2,24)));
        // Sand covers it all: the emeralds stay.
        assertEquals(Map.of(Items.SAND,9*4+2*8+24*2),rich.payment());
    }

    @Test void aShortSurplusMakesAPartialDealAndNothingAnEmptyOne(){
        var partial=Caravan.deal(store(Items.SAND,32+20,Items.CACTUS,8+3),want(9,0,0));
        assertEquals(Map.of(WOOL,6),partial.goods());
        assertEquals(24,partial.price());
        assertEquals(Map.of(Items.SAND,20,Items.CACTUS,2),partial.payment());
        assertEquals(24,partial.paid());
        assertFalse(partial.covers(want(9,0,0)));
        // Nothing wanted: nothing bought, nothing paid.
        var none=Caravan.deal(store(Items.SAND,1000,Items.EMERALD,64),want(0,0,0));
        assertTrue(none.empty());assertTrue(none.payment().isEmpty());
        // Less surplus than the cheapest piece: nothing.
        var poor=Caravan.deal(store(Items.SAND,32+2),want(9,2,0));
        assertTrue(poor.empty());assertTrue(poor.payment().isEmpty());
        assertTrue(Caravan.deal(Map.of(),want(9,2,24)).empty());
    }

    @Test void thePaymentAlwaysMeetsThePriceAndNeverTouchesTheReserves(){
        var r=new Random(2026);
        var keep=new HashMap<Item,Integer>();for(var p:Caravan.PAY)keep.put(p.item(),p.keep());
        int dealt=0;
        for(int run=0;run<2000;run++){
            var store=new HashMap<Item,Integer>();
            for(var p:Caravan.PAY)if(r.nextInt(3)==0)store.put(p.item(),r.nextInt(p.keep()+60));
            var wants=want(r.nextInt(10),r.nextInt(3),r.nextInt(25));
            var deal=Caravan.deal(store,wants);
            String label="run "+run+" "+store+" "+wants;
            assertTrue(deal.paid()>=deal.price(),label);
            int dearest=deal.payment().keySet().stream().mapToInt(i->Caravan.PAY.stream().filter(p->p.item()==i).findFirst().orElseThrow().value()).max().orElse(1);
            assertTrue(deal.paid()-deal.price()<dearest,label+" overpaid");
            for(var e:deal.payment().entrySet())assertTrue(e.getValue()>0&&store.get(e.getKey())-e.getValue()>=keep.get(e.getKey()),label);
            for(int i=0;i<Caravan.WARES.size();i++)assertTrue(deal.goods().getOrDefault(Caravan.WARES.get(i).item(),0)<=wants.of(i),label);
            if(deal.empty())assertTrue(deal.payment().isEmpty(),label);else dealt++;
        }
        assertTrue(dealt>500,"too few deals to say anything: "+dealt);
    }

    @Test void onceADayInDaylight(){
        assertFalse(Caravan.due(0,999,-1));
        assertTrue(Caravan.due(0,1000,-1));
        assertTrue(Caravan.due(0,10999,-1));
        assertFalse(Caravan.due(0,11000,-1));
        assertFalse(Caravan.due(0,20000,-1));
        assertFalse(Caravan.due(3,5000,3));
        assertTrue(Caravan.due(4,5000,3));
        // A short deal waits for the afternoon; a whole one is struck at once; an empty one never.
        var partial=Caravan.deal(store(Items.SAND,40),want(9,0,0));
        assertFalse(Caravan.settles(partial,want(9,0,0),1000));
        assertTrue(Caravan.settles(partial,want(9,0,0),Caravan.AFTERNOON));
        var whole=Caravan.deal(store(Items.SAND,200),want(9,0,0));
        assertTrue(Caravan.settles(whole,want(9,0,0),1000));
        assertFalse(Caravan.settles(Caravan.deal(store(),want(9,0,0)),want(9,0,0),10000));
    }

    @Test void aPaymentThatFallsShortGoesBackWhole(){
        var chests=new HashMap<>(store(Items.SAND,151,Items.CACTUS,3));
        var before=new HashMap<>(chests);
        var returned=new ArrayList<ItemStack>();
        var payment=new LinkedHashMap<Item,Integer>();payment.put(Items.SAND,151);payment.put(Items.CACTUS,5);payment.put(Items.EMERALD,1);
        boolean paid=Caravan.pay(payment,(item,n)->{int have=chests.getOrDefault(item,0),k=Math.min(have,n);chests.put(item,have-k);return k;},
                back->{returned.add(back);chests.merge(back.getItem(),back.getCount(),Integer::sum);});
        assertFalse(paid);
        assertEquals(before,chests);
        // In whole stacks, the sand in three, and nothing is taken after the shortfall.
        assertEquals(List.of(64,64,23,3),returned.stream().map(ItemStack::getCount).toList());
        assertEquals(0,chests.getOrDefault(Items.EMERALD,0));
        var whole=new LinkedHashMap<Item,Integer>();whole.put(Items.SAND,100);whole.put(Items.CACTUS,3);
        assertTrue(Caravan.pay(whole,(item,n)->{int have=chests.getOrDefault(item,0),k=Math.min(have,n);chests.put(item,have-k);return k;},back->fail("nothing goes back")));
        assertEquals(51,chests.get(Items.SAND));assertEquals(0,chests.get(Items.CACTUS));
        assertEquals(List.of(16,16,2),Caravan.stacks(Items.EGG,34).stream().map(ItemStack::getCount).toList());
        assertTrue(Caravan.stacks(Items.SAND,0).isEmpty());
    }

    @Test void oneRussianLineNamesTheGoodsAndThePayment(){
        var goods=new LinkedHashMap<Item,Integer>();goods.put(WOOL,6);goods.put(Items.OAK_LOG,16);
        var deal=new Caravan.Deal(goods,Map.of(Items.SAND,56));
        assertEquals(deal.price(),deal.paid());
        assertEquals("Пришёл караван: 6 блоков шерсти и 16 брёвен за 56 блоков песка",Caravan.line(deal));
        assertEquals("блок шерсти",Caravan.WARES.get(0).word().one());
        var mixed=new LinkedHashMap<Item,Integer>();mixed.put(Items.OAK_SAPLING,3);mixed.put(Items.BIRCH_SAPLING,2);mixed.put(Items.CACTUS,2);mixed.put(Items.EMERALD,1);
        // Goods in the order served, saplings of every kind as one.
        assertEquals("Пришёл караван: 3 блока шерсти, 2 слитка железа и 1 бревно за 5 саженцев, 2 кактуса и 1 изумруд",
                Caravan.line(new Caravan.Deal(Map.of(Items.OAK_LOG,1,Items.IRON_INGOT,2,WOOL,3),mixed)));
        // An emerald for an ingot: a whole piece, never less than the price.
        var dear=Caravan.deal(store(Items.EMERALD,3),want(0,1,0));
        assertEquals("Пришёл караван: 1 слиток железа за 1 изумруд",Caravan.line(dear));
        assertEquals(8,dear.price());assertEquals(16,dear.paid());
    }

    @Test void theCaravanDaySurvivesTheSave(){
        assertEquals(-1,new VillageState().caravanDay);
        var s=new VillageState("Караван",UUID.randomUUID(),new BlockPos(0,64,0),24);
        s.caravanDay=12;
        var save=new VillageRuntime.Save();save.villages=List.of(s);
        var r=VillageRuntime.GSON.fromJson(VillageRuntime.GSON.toJson(save),VillageRuntime.Save.class).villages.getFirst();
        r.compactMetadata();r.validate();
        assertEquals(12,r.caravanDay);
        // An old save without it starts with no caravan seen; a broken value is no earlier than that.
        var old=VillageRuntime.GSON.fromJson("{\"name\":\"Old\",\"owner\":\""+UUID.randomUUID()+"\",\"x\":0,\"y\":64,\"z\":0,\"radius\":24}",VillageState.class);
        old.compactMetadata();assertEquals(-1,old.caravanDay);
        old.caravanDay=-40;old.compactMetadata();assertEquals(-1,old.caravanDay);
        assertTrue(new ModConfig().caravans);
    }
}
