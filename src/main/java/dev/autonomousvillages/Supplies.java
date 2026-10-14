package dev.autonomousvillages;

import dev.navigationai.api.BotHandle;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.world.Container;
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
        int n = incoming.getCount();
        for (ItemStack slot : slots) if (!slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, incoming)) {
            int take = Math.min(n, Math.max(0, slot.getMaxStackSize() - slot.getCount())); slot.grow(take); n -= take;
        }
        for (int i = 0; i < slots.size() && n > 0; i++) if (slots.get(i).isEmpty()) {
            int take = Math.min(n, incoming.getMaxStackSize()); slots.set(i, incoming.copyWithCount(take)); n -= take;
        }
        return n == 0;
    }
    static boolean matches(List<ItemStack> a, List<ItemStack> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!ItemStack.matches(a.get(i), b.get(i))) return false;
        return true;
    }
    static boolean replace(BotHandle bot, List<ItemStack> before, List<ItemStack> next) {
        if (!bot.stopAll() || !matches(before, bot.inventory().snapshot().slots())) return false;
        for (int i = 0; i < before.size(); i++) bot.inventory().extract(i, Integer.MAX_VALUE);
        for (ItemStack stack : next) if (!stack.isEmpty()) {
            if (!bot.inventory().insert(stack).isEmpty()) throw new IllegalStateException("Inventory transaction overflow");
        }
        return true;
    }
    static int transfer(BotHandle bot, Container chest, boolean deposit, Predicate<ItemStack> test, int limit) {
        List<ItemStack> b = copy(bot.inventory().snapshot().slots()), c = copy(chest);
        List<ItemStack> bn = copy(b), cn = copy(c), from = deposit ? bn : cn, to = deposit ? cn : bn;
        int moved = 0;
        for (int i = 0; i < from.size() && moved < limit; i++) {
            ItemStack stack = from.get(i);
            if (stack.isEmpty() || !test.test(stack)) continue;
            int amount = Math.min(stack.getCount(), limit - moved);
            // A partial insertion must never consume the source: try a disposable target first.
            List<ItemStack> attempt = copy(to);
            if (!insert(attempt, stack.copyWithCount(amount))) continue;
            for (int j = 0; j < to.size(); j++) to.set(j, attempt.get(j));
            stack.shrink(amount); moved += amount;
        }
        if (moved == 0 || !matches(c, copy(chest)) || !replace(bot, b, bn)) return 0;
        for (int i = 0; i < cn.size(); i++) chest.setItem(i, cn.get(i));
        chest.setChanged(); return moved;
    }
    static boolean consume(BotHandle bot, Item item, int count) {
        var before = copy(bot.inventory().snapshot().slots()); var next = copy(before); int left = count;
        for (ItemStack stack : next) if (stack.is(item)) { int n = Math.min(left, stack.getCount()); stack.shrink(n); left -= n; }
        return left == 0 && replace(bot, before, next);
    }
}
