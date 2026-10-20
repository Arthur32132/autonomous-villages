package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Bag + chest + overflow keep the same goods through every Supplies transaction, whatever the library answers midway. */
class SuppliesTest {
    @BeforeAll static void bootstrapRegistries(){McBootstrap.init();}

    /** Stacks of 64, of 16, of one, damageable: a method, since touching Items before the bootstrap breaks every later test. */
    private static Item[] kinds(){return new Item[]{Items.COBBLESTONE,Items.OAK_LOG,Items.BREAD,Items.WHEAT,Items.EGG,Items.SNOWBALL,Items.ENDER_PEARL,
            Items.IRON_PICKAXE,Items.SHEARS,Items.WATER_BUCKET};}

    /** Goods by item and components, however they are split into stacks. */
    @SafeVarargs private static Map<String,Integer> tally(List<ItemStack>... groups){
        Map<String,Integer> t=new TreeMap<>();
        for(var g:groups)for(var s:g)if(!s.isEmpty())t.merge(BuiltInRegistries.ITEM.getKey(s.getItem())+" "+s.getComponentsPatch(),s.getCount(),Integer::sum);
        return t;
    }
    private static void assertStackSizes(List<ItemStack> slots,String where){
        for(var s:slots)assertTrue(s.isEmpty()||s.getCount()<=s.getMaxStackSize(),where+": "+s+" over "+s.getMaxStackSize());
    }
    private static ItemStack named(Item item,int count){var s=new ItemStack(item,count);s.set(DataComponents.CUSTOM_NAME,Component.literal("Клеймо"));return s;}
    private static FakeBot bot(ItemStack... stacks){var b=new FakeBot("Тест");for(int i=0;i<stacks.length;i++)b.put(i,stacks[i]);return b;}
    /** Three stacks in, bread eaten and a pickaxe made: the shape of a craft. */
    private static List<ItemStack> craftNext(List<ItemStack> before){
        var next=Supplies.copy(before);next.get(1).shrink(3);Supplies.insert(next,new ItemStack(Items.IRON_PICKAXE));return next;
    }
    private static FakeBot threeStacks(){return bot(new ItemStack(Items.COBBLESTONE,40),new ItemStack(Items.BREAD,5),new ItemStack(Items.EGG,16));}

    @Test void aCleanSwapLeavesExactlyNextAndDropsNothing(){
        var b=threeStacks();var before=Supplies.copy(b.bag);var next=craftNext(before);var spilled=new ArrayList<ItemStack>();
        assertTrue(Supplies.replace(b,before,next,spilled::add));
        assertTrue(spilled.isEmpty());assertEquals(tally(next),tally(b.bag));assertStackSizes(b.bag,"bag");
    }

    @Test void aStaleSnapshotABusyBotOrARemovedBotChangesNothing(){
        var b=threeStacks();var before=Supplies.copy(b.bag);var next=craftNext(before);var spilled=new ArrayList<ItemStack>();
        var stale=Supplies.copy(before);stale.get(0).shrink(1);
        assertFalse(Supplies.replace(b,stale,craftNext(stale),spilled::add));
        b.refuse=n->n==2;
        assertFalse(Supplies.replace(b,before,next,spilled::add));
        b.remove();
        assertFalse(assertDoesNotThrow(()->Supplies.replace(b,before,next,spilled::add)));
        assertTrue(spilled.isEmpty());assertTrue(Supplies.matches(before,b.bag));
    }

    @Test void aTakeRefusedMidwayPutsBackWhatCameOutInsteadOfDuplicating(){
        // Call 1 is the opening stopAll, 2..4 the takes: the second take answers EMPTY and its slot stays full.
        var b=threeStacks();var before=Supplies.copy(b.bag);var spilled=new ArrayList<ItemStack>();b.refuse=n->n==3;
        assertFalse(Supplies.replace(b,before,craftNext(before),spilled::add));
        assertTrue(spilled.isEmpty());assertEquals(tally(before),tally(b.bag));assertStackSizes(b.bag,"bag");
    }

    @Test void aBotThatStaysBusySpillsWhatItCannotTakeBack(){
        var b=threeStacks();var before=Supplies.copy(b.bag);var spilled=new ArrayList<ItemStack>();b.refuse=n->n>=3;
        assertFalse(Supplies.replace(b,before,craftNext(before),spilled::add));
        // Only the cobblestone had come out before the bot got busy; it cannot go back, so it is set down.
        assertEquals(tally(List.of(new ItemStack(Items.COBBLESTONE,40))),tally(spilled));
        assertEquals(tally(before),tally(b.bag,spilled));
    }

    @Test void aCallbackReshufflingTheBagIsCaughtAndUndone(){
        var b=threeStacks();var before=Supplies.copy(b.bag);var spilled=new ArrayList<ItemStack>();
        b.callback=n->{if(n==3)Collections.swap(b.bag,1,2);};
        assertFalse(Supplies.replace(b,before,craftNext(before),spilled::add));
        assertTrue(spilled.isEmpty());assertEquals(tally(before),tally(b.bag));assertStackSizes(b.bag,"bag");
    }

    @Test void anInsertRefusedHalfwayRestoresBeforeInsteadOfThrowing(){
        // Calls 1..4 open and take, 5 is the first insert: it is refused once, then everything works again.
        var b=threeStacks();var before=Supplies.copy(b.bag);var spilled=new ArrayList<ItemStack>();b.refuse=n->n==6;
        assertFalse(assertDoesNotThrow(()->Supplies.replace(b,before,craftNext(before),spilled::add)));
        assertTrue(spilled.isEmpty());assertEquals(tally(before),tally(b.bag));assertStackSizes(b.bag,"bag");
    }

    @Test void whenNextCannotComeOutAgainTheBotKeepsNextAndSpillsTheRest(){
        // The first stack of next goes in, then the bot stays busy: before cannot be restored, so the transaction stands.
        var b=threeStacks();var before=Supplies.copy(b.bag);var next=craftNext(before);var spilled=new ArrayList<ItemStack>();b.refuse=n->n>=6;
        assertTrue(assertDoesNotThrow(()->Supplies.replace(b,before,next,spilled::add)));
        assertFalse(spilled.isEmpty());assertEquals(tally(next),tally(b.bag,spilled));
    }

    @Test void aBotRemovedMidTransactionNeverThrowsOrLosesGoods(){
        for(int at=2;at<12;at++){
            var b=threeStacks();var before=Supplies.copy(b.bag);var next=craftNext(before);var spilled=new ArrayList<ItemStack>();final int k=at;
            b.callback=n->{if(n==k)b.remove();};
            boolean done=assertDoesNotThrow(()->Supplies.replace(b,before,next,spilled::add));
            assertEquals(tally(done?next:before),tally(b.bag,spilled),"removed at call "+at);
        }
    }

    @Test void depositMovesWhatTheChestTakesAndNoMore(){
        var b=bot(new ItemStack(Items.COBBLESTONE,64),new ItemStack(Items.COBBLESTONE,30),new ItemStack(Items.BREAD,3));
        var chest=new SimpleContainer(3);chest.setItem(0,new ItemStack(Items.COBBLESTONE,60));chest.setItem(1,new ItemStack(Items.DIRT,64));
        var all=tally(b.bag,Supplies.copy(chest));var spilled=new ArrayList<ItemStack>();
        assertEquals(68,Supplies.transfer(b,chest,true,s->s.is(Items.COBBLESTONE),1000,spilled::add));
        assertEquals(26,b.inventory().count(Items.COBBLESTONE));assertEquals(3,b.inventory().count(Items.BREAD));
        assertTrue(spilled.isEmpty());assertEquals(all,tally(b.bag,Supplies.copy(chest)));assertStackSizes(Supplies.copy(chest),"chest");
        // A full chest takes nothing and changes nothing.
        var full=Supplies.copy(chest);
        assertEquals(0,Supplies.transfer(b,chest,true,s->s.is(Items.COBBLESTONE),1000,spilled::add));
        assertTrue(Supplies.matches(full,Supplies.copy(chest)));assertEquals(26,b.inventory().count(Items.COBBLESTONE));
    }

    @Test void withdrawRespectsLimitStackSizesAndComponents(){
        var b=bot(new ItemStack(Items.EGG,10));
        var chest=new SimpleContainer(4);chest.setItem(0,new ItemStack(Items.EGG,16));chest.setItem(1,new ItemStack(Items.EGG,16));chest.setItem(2,named(Items.EGG,5));
        var all=tally(b.bag,Supplies.copy(chest));
        assertEquals(20,Supplies.transfer(b,chest,false,s->s.is(Items.EGG),20));
        assertEquals(30,b.inventory().count(Items.EGG));assertEquals(all,tally(b.bag,Supplies.copy(chest)));
        assertStackSizes(b.bag,"bag");assertEquals(16,b.bag.get(0).getCount());
        // The named eggs never merge into a plain stack.
        assertEquals(5,Supplies.transfer(b,chest,false,s->s.has(DataComponents.CUSTOM_NAME),64));
        assertTrue(b.bag.stream().filter(s->s.has(DataComponents.CUSTOM_NAME)).allMatch(s->s.getCount()==5));
        assertEquals(all,tally(b.bag,Supplies.copy(chest)));
    }

    @Test void consumeTakesExactlyOrNothing(){
        var b=bot(new ItemStack(Items.BREAD,2),new ItemStack(Items.WHEAT,9),new ItemStack(Items.BREAD,3));
        var before=Supplies.copy(b.bag);
        assertFalse(Supplies.consume(b,Items.BREAD,6));assertTrue(Supplies.matches(before,b.bag));
        assertTrue(Supplies.consume(b,Items.BREAD,4));
        assertEquals(1,b.inventory().count(Items.BREAD));assertEquals(9,b.inventory().count(Items.WHEAT));
    }

    @Test void listInsertKeepsStackLimitsAndComponentsApart(){
        var slots=new ArrayList<>(Collections.nCopies(3,ItemStack.EMPTY));
        assertEquals(20,Supplies.insertPartial(slots,new ItemStack(Items.EGG,20)));
        assertEquals(16,slots.get(0).getCount());assertEquals(4,slots.get(1).getCount());
        assertEquals(7,Supplies.insertPartial(slots,named(Items.EGG,7)));assertEquals(7,slots.get(2).getCount());assertEquals(4,slots.get(1).getCount());
        assertFalse(Supplies.insert(slots,new ItemStack(Items.SHEARS)));
        assertEquals(12,Supplies.insertPartial(slots,new ItemStack(Items.EGG,30)));assertStackSizes(slots,"slots");
    }

    @Test void sameGoodsIgnoresSplitsButNotComponentsOrCounts(){
        assertTrue(Supplies.sameGoods(List.of(new ItemStack(Items.EGG,16),new ItemStack(Items.EGG,4)),List.of(ItemStack.EMPTY,new ItemStack(Items.EGG,20))));
        assertFalse(Supplies.sameGoods(List.of(new ItemStack(Items.EGG,20)),List.of(named(Items.EGG,20))));
        assertFalse(Supplies.sameGoods(List.of(new ItemStack(Items.EGG,20)),List.of(new ItemStack(Items.EGG,19))));
        assertFalse(Supplies.sameGoods(List.of(new ItemStack(Items.EGG,19)),List.of(new ItemStack(Items.EGG,20))));
    }

    private static ItemStack randomStack(Random rnd){
        var kinds=kinds();Item item=kinds[rnd.nextInt(kinds.length)];var s=new ItemStack(item);s.setCount(1+rnd.nextInt(s.getMaxStackSize()));
        if(s.isDamageableItem()&&rnd.nextBoolean())s.setDamageValue(1+rnd.nextInt(s.getMaxDamage()-1));
        if(rnd.nextInt(8)==0)s.set(DataComponents.CUSTOM_NAME,Component.literal("Клеймо"));
        return s;
    }
    private static void fill(List<ItemStack> slots,Random rnd,int stacks){for(int i=0;i<stacks;i++)slots.set(rnd.nextInt(slots.size()),randomStack(rnd));}

    @Test void randomBagsChestsAndFaultsNeverCreateOrDestroyGoods(){
        var rnd=new Random(20261020L);int committed=0,refused=0,spilledRounds=0;
        for(int round=0;round<400;round++){
            var b=new FakeBot("Проба"+round);fill(b.bag,rnd,rnd.nextInt(FakeBot.SIZE+8));
            var chest=new SimpleContainer(27);var chestSlots=new ArrayList<>(Collections.nCopies(27,ItemStack.EMPTY));fill(chestSlots,rnd,rnd.nextInt(34));
            for(int i=0;i<27;i++)chest.setItem(i,chestSlots.get(i));
            var brought=new ArrayList<ItemStack>();var spilled=new ArrayList<ItemStack>();
            int fault=rnd.nextInt(6),k=1+rnd.nextInt(80),i1=rnd.nextInt(FakeBot.SIZE),i2=rnd.nextInt(FakeBot.SIZE);var gift=randomStack(rnd);
            switch(fault){
                case 1->b.refuse=n->n==k;
                case 2->b.refuse=n->n>=k;
                case 3->b.callback=n->{if(n==k)Collections.swap(b.bag,i1,i2);};
                case 4->b.callback=n->{if(n==k&&b.bag.get(i1).isEmpty()){b.bag.set(i1,gift.copy());brought.add(gift.copy());}};
                case 5->b.callback=n->{if(n==k)b.remove();};
                default->{}
            }
            var all=tally(b.bag,Supplies.copy(chest));var chestBefore=Supplies.copy(chest);
            boolean deposit=rnd.nextBoolean();Item kind=kinds()[rnd.nextInt(kinds().length)];
            Predicate<ItemStack> test=rnd.nextInt(3)==0?s->true:s->s.is(kind);int limit=1+rnd.nextInt(300);
            int moved=assertDoesNotThrow(()->Supplies.transfer(b,chest,deposit,test,limit,spilled::add),"round "+round);
            var chestAfter=Supplies.copy(chest);
            var expected=new TreeMap<>(all);tally(brought).forEach((key,n)->expected.merge(key,n,Integer::sum));
            assertEquals(expected,tally(b.bag,chestAfter,spilled),"round "+round+" fault "+fault+" at "+k);
            assertStackSizes(b.bag,"bag, round "+round);assertStackSizes(chestAfter,"chest, round "+round);assertStackSizes(spilled,"spill, round "+round);
            int chestDelta=chestAfter.stream().mapToInt(ItemStack::getCount).sum()-chestBefore.stream().mapToInt(ItemStack::getCount).sum();
            if(moved==0)assertTrue(Supplies.matches(chestBefore,chestAfter),"round "+round);else assertEquals(deposit?moved:-moved,chestDelta,"round "+round);
            if(moved>0)committed++;else refused++;if(!spilled.isEmpty())spilledRounds++;
        }
        // The seed must reach every branch: commits, refusals and spills.
        assertTrue(committed>50&&refused>50&&spilledRounds>0,committed+" committed, "+refused+" refused, "+spilledRounds+" spilled");
    }
}
