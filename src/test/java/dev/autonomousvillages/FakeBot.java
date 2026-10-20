package dev.autonomousvillages;

import dev.navigationai.api.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/** A bot with a plain 36-slot bag that keeps the library's rules: every mutation stops all tasks first and gives up when that fails,
 * a removed handle throws, completion callbacks run while stopping. Nothing here creates or destroys items on its own. */
final class FakeBot implements BotHandle {
    static final int SIZE=36;
    final List<ItemStack> bag=new ArrayList<>(Collections.nCopies(SIZE,ItemStack.EMPTY));
    /** stopAll calls so far; {@link #refuse} and {@link #callback} see the number of the current call (from 1). */
    int stops;
    /** stopAll answers false on these calls: a completion callback started a newer intent. */
    IntPredicate refuse=n->false;
    /** Completion callbacks, run inside stopAll; they may reshuffle the bag, bring goods in or remove the bot. */
    IntConsumer callback=n->{};
    private final UUID id=UUID.randomUUID();
    private final String name;
    private boolean removed;
    private final BotInventory inventory=new Bag();

    FakeBot(String name){this.name=name;}
    FakeBot put(int slot,ItemStack stack){bag.set(slot,stack.copy());return this;}

    private void ensureActive(){if(removed)throw new IllegalStateException("Bot is not active");}

    @Override public UUID id(){return id;}
    @Override public String name(){return name;}
    @Override public ServerPlayer player(){return null;}
    @Override public BotKind kind(){return BotKind.VILLAGER;}
    @Override public BotState state(){return removed?BotState.REMOVED:BotState.ACTIVE;}
    @Override public BotActions actions(){throw new UnsupportedOperationException("FakeBot has no actions");}
    @Override public Navigator navigator(){throw new UnsupportedOperationException("FakeBot does not walk");}
    @Override public Miner miner(){throw new UnsupportedOperationException("FakeBot does not mine");}
    @Override public ResourceCollector collector(){throw new UnsupportedOperationException("FakeBot does not gather");}
    @Override public CombatController combat(){throw new UnsupportedOperationException("FakeBot does not fight");}
    @Override public BotInventory inventory(){return inventory;}
    @Override public boolean stopAll(){ensureActive();int n=++stops;callback.accept(n);return !refuse.test(n);}
    @Override public void remove(){removed=true;}

    private final class Bag implements BotInventory {
        @Override public InventorySnapshot snapshot(){return new InventorySnapshot(bag,0);}
        @Override public boolean select(int slot){ensureActive();if(slot<0||slot>=SIZE)throw new IllegalArgumentException("slot must be 0..35");return stopAll();}
        @Override public boolean equip(int slot,EquipmentSlot equipment){ensureActive();Objects.requireNonNull(equipment);return false;}
        /** Like Inventory.add: tops up equal stacks first, then fills empty slots; returns an independent remainder. */
        @Override public ItemStack insert(ItemStack stack){
            ensureActive();ItemStack rest=Objects.requireNonNull(stack).copy();
            if(!stopAll())return rest;
            ensureActive();
            for(int i=0;i<SIZE&&!rest.isEmpty();i++){ItemStack s=bag.get(i);
                if(!s.isEmpty()&&ItemStack.isSameItemSameComponents(s,rest)){int n=Math.min(rest.getCount(),Math.max(0,s.getMaxStackSize()-s.getCount()));s.grow(n);rest.shrink(n);}}
            for(int i=0;i<SIZE&&!rest.isEmpty();i++)if(bag.get(i).isEmpty()){int n=Math.min(rest.getCount(),rest.getMaxStackSize());bag.set(i,rest.copyWithCount(n));rest.shrink(n);}
            return rest.isEmpty()?ItemStack.EMPTY:rest;
        }
        @Override public ItemStack extract(int slot,int count){
            ensureActive();if(slot<0||slot>=SIZE)throw new IllegalArgumentException("slot must be 0..35");
            if(count<1)throw new IllegalArgumentException("count must be positive");
            if(!stopAll())return ItemStack.EMPTY;
            ensureActive();
            ItemStack s=bag.get(slot);if(s.isEmpty())return ItemStack.EMPTY;
            ItemStack out=s.split(count);if(s.isEmpty())bag.set(slot,ItemStack.EMPTY);
            return out;
        }
    }
}
