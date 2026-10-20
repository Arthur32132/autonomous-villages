package dev.autonomousvillages;

import dev.navigationai.api.BotHandle;
import dev.navigationai.api.BotInventory;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.tags.ItemTags;

/** Synchronous server-thread transactions. Work on copies before committing any change. */
final class Supplies {
    static List<ItemStack> copy(Container c) {
        List<ItemStack> result = new ArrayList<>();
        for (int i = 0; i < c.getContainerSize(); i++) result.add(c.getItem(i).copy());
        return result;
    }
    static List<ItemStack> copy(List<ItemStack> slots) { return new ArrayList<>(slots.stream().map(ItemStack::copy).toList()); }
    static int count(List<ItemStack> slots, Predicate<ItemStack> test) { return slots.stream().filter(test).mapToInt(ItemStack::getCount).sum(); }
    static int find(BotHandle bot, Predicate<ItemStack> test) {
        var slots = bot.inventory().snapshot().slots();
        for (int i = 0; i < slots.size(); i++) if (!slots.get(i).isEmpty() && test.test(slots.get(i))) return i;
        return -1;
    }
    static boolean usable(ItemStack stack) { return !stack.isEmpty() && !stack.isBroken() && (!stack.isDamageableItem() || stack.getMaxDamage() - stack.getDamageValue() > 4); }
    static boolean tool(ItemStack stack) { return stack.is(ItemTags.PICKAXES) || stack.is(ItemTags.AXES) || stack.is(ItemTags.SWORDS) || stack.is(ItemTags.HOES) || stack.is(Items.SHEARS) || stack.is(Items.SHIELD); }
    static boolean food(ItemStack stack) { return stack.is(Items.BREAD) || stack.is(Items.CARROT) || stack.is(Items.POTATO) || stack.is(Items.BEETROOT); }
    static boolean insert(List<ItemStack> slots, ItemStack incoming) {
        return insertPartial(slots,incoming)==incoming.getCount();
    }
    static int insertPartial(List<ItemStack> slots, ItemStack incoming) {
        int n = incoming.getCount();
        for (ItemStack slot : slots) if (!slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, incoming)) {
            int take = Math.min(n, Math.max(0, slot.getMaxStackSize() - slot.getCount())); slot.grow(take); n -= take;
        }
        for (int i = 0; i < slots.size() && n > 0; i++) if (slots.get(i).isEmpty()) {
            int take = Math.min(n, incoming.getMaxStackSize()); slots.set(i, incoming.copyWithCount(take)); n -= take;
        }
        return incoming.getCount()-n;
    }
    static boolean matches(List<ItemStack> a, List<ItemStack> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!ItemStack.matches(a.get(i), b.get(i))) return false;
        return true;
    }
    static boolean replace(BotHandle bot, List<ItemStack> before, List<ItemStack> next) { return replace(bot, before, next, spill(bot)); }
    /** True: the bot now holds {@code next}; false: it holds {@code before}. Goods that no longer fit go to {@code overflow}, never vanish. */
    static boolean replace(BotHandle bot, List<ItemStack> before, List<ItemStack> next, Consumer<ItemStack> overflow) {
        if (!stopped(bot) || !matches(before, bot.inventory().snapshot().slots())) return false;
        BotInventory inv = bot.inventory(); List<ItemStack> held = new ArrayList<>(); boolean intact = true;
        // The library returns EMPTY when its stopAll fails and a completion callback may reshuffle slots: check every take.
        for (int i = 0; i < before.size() && intact; i++) if (!before.get(i).isEmpty()) {
            ItemStack got = take(inv, i); if (!got.isEmpty()) held.add(got); intact = ItemStack.matches(got, before.get(i));
        }
        if (!intact) { putBack(inv, held, overflow); return false; }
        List<ItemStack> left = new ArrayList<>();
        for (ItemStack stack : next) if (!stack.isEmpty()) { ItemStack rest = left.isEmpty() ? put(inv, stack) : stack.copy(); if (!rest.isEmpty()) left.add(rest); }
        if (left.isEmpty()) return true;
        // Part of next did not go in: take it out again and restore before; if it cannot all come out, keep next instead.
        for (int i = 0; i < before.size(); i++) { ItemStack got = take(inv, i); if (!got.isEmpty()) left.add(got); }
        if (inv.snapshot().slots().stream().allMatch(ItemStack::isEmpty) && sameGoods(left, next)) { putBack(inv, before, overflow); return false; }
        putBack(inv, left, overflow); return true;
    }
    /** The same goods however they are split into stacks. */
    static boolean sameGoods(List<ItemStack> a, List<ItemStack> b) {
        List<ItemStack> pool = copy(a);
        for (ItemStack s : b) {
            int n = s.getCount();
            for (ItemStack p : pool) if (n > 0 && !p.isEmpty() && ItemStack.isSameItemSameComponents(p, s)) { int k = Math.min(n, p.getCount()); p.shrink(k); n -= k; }
            if (n > 0) return false;
        }
        return pool.stream().allMatch(ItemStack::isEmpty);
    }
    private static boolean stopped(BotHandle bot) { try { return bot.stopAll(); } catch (RuntimeException inactive) { return false; } }
    // Library insert/extract throw only before they touch the inventory (inactive bot): treat that as "nothing moved".
    private static ItemStack take(BotInventory inv, int slot) { try { return inv.extract(slot, Integer.MAX_VALUE); } catch (RuntimeException inactive) { return ItemStack.EMPTY; } }
    private static ItemStack put(BotInventory inv, ItemStack stack) { try { return inv.insert(stack.copy()); } catch (RuntimeException inactive) { return stack.copy(); } }
    private static void putBack(BotInventory inv, List<ItemStack> stacks, Consumer<ItemStack> overflow) {
        for (ItemStack stack : stacks) if (!stack.isEmpty()) { ItemStack rest = put(inv, stack); if (!rest.isEmpty()) overflow.accept(rest); }
    }
    /** Last resort: what the bot cannot hold any more is set down at its feet, with one warning per transaction. */
    private static Consumer<ItemStack> spill(BotHandle bot) {
        boolean[] warned = {false};
        return rest -> {
            try {
                var body = bot.entity();
                if (!warned[0]) { warned[0] = true; AutonomousVillagesMod.LOG.warn("Bot {} could not take back {} after an inventory transaction, dropped at {}", bot.name(), rest, body.blockPosition()); }
                var item = new ItemEntity(body.level(), body.getX(), body.getY() + .5, body.getZ(), rest.copy());
                item.setDefaultPickUpDelay(); body.level().addFreshEntity(item);
            } catch (RuntimeException broken) { AutonomousVillagesMod.LOG.error("Cannot drop {} for bot {}", rest, bot.name(), broken); }
        };
    }
    static int transfer(BotHandle bot, Container chest, boolean deposit, Predicate<ItemStack> test, int limit) { return transfer(bot, chest, deposit, test, limit, spill(bot)); }
    static int transfer(BotHandle bot, Container chest, boolean deposit, Predicate<ItemStack> test, int limit, Consumer<ItemStack> overflow) {
        List<ItemStack> b = copy(bot.inventory().snapshot().slots()), c = copy(chest);
        List<ItemStack> bn = copy(b), cn = copy(c), from = deposit ? bn : cn, to = deposit ? cn : bn;
        int moved = 0;
        for (int i = 0; i < from.size() && moved < limit; i++) {
            ItemStack stack = from.get(i);
            if (stack.isEmpty() || !test.test(stack)) continue;
            int amount = Math.min(stack.getCount(), limit - moved);
            // A partial insertion must never consume the source: try a disposable target first.
            List<ItemStack> attempt = copy(to);
            amount=insertPartial(attempt,stack.copyWithCount(amount));
            if(amount==0)continue;
            for (int j = 0; j < to.size(); j++) to.set(j, attempt.get(j));
            stack.shrink(amount); moved += amount;
        }
        if (moved == 0 || !matches(c, copy(chest)) || !replace(bot, b, bn, overflow)) return 0;
        for (int i = 0; i < cn.size(); i++) chest.setItem(i, cn.get(i));
        chest.setChanged(); return moved;
    }
    static boolean consume(BotHandle bot, Item item, int count) {
        var before = copy(bot.inventory().snapshot().slots()); var next = copy(before); int left = count;
        for (ItemStack stack : next) if (stack.is(item)) { int n = Math.min(left, stack.getCount()); stack.shrink(n); left -= n; }
        return left == 0 && replace(bot, before, next);
    }
}
