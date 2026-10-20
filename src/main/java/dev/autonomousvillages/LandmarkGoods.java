package dev.autonomousvillages;

import java.util.*;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;

/**
 * What the forge, the market and the tavern give once a day, from the store and back into it or out
 * to guests. The forge mends worn tools for a lump of coal each; the market lays the store's surplus
 * out in its stall barrels for anyone passing; the tavern cooks raw food for the store and brews the
 * village's mead into its barrels in the evening; the mill grinds spare wheat and bakes it; the pier
 * brings in the morning's catch for the tavern's kitchen. Nothing but the catch is made from nothing.
 */
final class LandmarkGoods {
    private LandmarkGoods(){}
    static final int MENDS=4,COOKS=16,MEADS=2,WHEAT_PER_MEAD=3;

    static void tick(Village v){
        if(!AutonomousVillagesMod.CONFIG.landmarkGoods)return;
        long clock=v.level.getOverworldClockTime(),day=Math.floorDiv(clock,24000),hour=Math.floorMod(clock,24000);
        if(hour>=2000&&hour<11000)once(v,"forge",day,LandmarkGoods::forge);
        if(hour>=1000&&hour<11000)once(v,"market",day,LandmarkGoods::market);
        if(hour>=11000&&hour<14000)once(v,"tavern",day,LandmarkGoods::tavern);
        if(hour>=3000&&hour<11000)once(v,"mill",day,LandmarkGoods::mill);
        if(hour>=0&&hour<6000)once(v,"dock",day,LandmarkGoods::dock);
    }
    private static void once(Village v,String kind,long day,java.util.function.BiConsumer<Village,TownBuilding> work){
        if(v.state.goodsDays.getOrDefault(kind,-1L)>=day)return;
        var building=v.state.buildings.stream().filter(b->b.completed&&b.kind.equals(kind)&&v.level.hasChunkAt(BlockPos.of(b.origin))).findFirst().orElse(null);
        if(building==null)return;
        // The day is kept in the save: a restart does not pay twice.
        v.state.goodsDays.put(kind,day);
        work.accept(v,building);
    }

    // --- forge ------------------------------------------------------------------------------------
    static final Predicate<ItemStack> MENDABLE=s->s.isDamaged()&&(s.is(ItemTags.PICKAXES)||s.is(ItemTags.AXES)||s.is(ItemTags.SHOVELS)||s.is(ItemTags.HOES)
            ||s.is(ItemTags.SWORDS)||s.is(Items.SHEARS));
    /** The smith mends the most worn tools in the store, a lump of coal or charcoal each. */
    static void forge(Village v,TownBuilding forge){
        var worn=new ArrayList<ItemStack>();
        for(var chest:v.stock.chests())for(int i=0;i<chest.getContainerSize();i++){var s=chest.getItem(i);if(!s.isEmpty()&&MENDABLE.test(s))worn.add(s);}
        worn.sort(Comparator.comparingDouble(s->-(double)s.getDamageValue()/Math.max(1,s.getMaxDamage())));
        int mended=0;
        for(var tool:worn){
            if(mended>=MENDS||v.stock.consume(VillageCrafting.FUEL,1)<1)break;
            tool.setDamageValue(0);mended++;
        }
        if(mended==0)return;
        for(var chest:v.stock.chests())chest.setChanged();
        v.stock.invalidate();
        v.chronicle.add("mended",mended);
        show(v,forge,Blocks.ANVIL,SoundEvents.ANVIL_USE);
    }

    // --- market -----------------------------------------------------------------------------------
    /** Goods the market may sell and how many of each the store keeps for itself. */
    record Ware(Item item,int keep){}
    static final List<Ware> WARES=List.of(new Ware(Items.COBBLESTONE,256),new Ware(Items.BREAD,48),new Ware(Items.WHEAT,96),new Ware(Items.WHEAT_SEEDS,64),
            new Ware(Items.APPLE,16),new Ware(Items.OAK_LOG,192),new Ware(Items.SPRUCE_LOG,192),new Ware(Items.BIRCH_LOG,192),new Ware(Items.OAK_SAPLING,32),
            new Ware(Items.CARROT,64),new Ware(Items.POTATO,64),new Ware(Items.WOOL.white(),48),new Ware(Items.COOKED_COD,32),new Ware(Items.COOKED_PORKCHOP,32));
    /** Items above the store's own reserve, a stack at most. */
    static int surplus(int have,int keep){return Math.clamp(have-keep,0,64);}
    /** Each stall barrel that stands empty is filled with one surplus ware, a different one per barrel. */
    static void market(Village v,TownBuilding market){
        var barrels=barrels(v,market);
        if(barrels.isEmpty())return;
        var wares=new ArrayList<>(WARES);
        Collections.rotate(wares,(int)Math.floorMod(v.level.getOverworldClockTime()/24000,wares.size()));
        int laid=0;
        for(var barrel:barrels){
            if(!barrel.isEmpty())continue;
            for(var it=wares.iterator();it.hasNext();){
                var ware=it.next();
                int n=surplus(v.stock.count(s->s.is(ware.item())),ware.keep());
                if(n<=0)continue;
                it.remove();
                int taken=v.stock.consume(s->s.is(ware.item()),n);
                barrel.setItem(0,new ItemStack(ware.item(),taken));barrel.setChanged();laid+=taken;
                break;
            }
        }
        if(laid==0)return;
        v.chronicle.add("marketWares",laid);
        show(v,market,Blocks.BARREL,SoundEvents.BARREL_OPEN);
    }

    // --- tavern -----------------------------------------------------------------------------------
    static final Map<Item,Item> KITCHEN=new LinkedHashMap<>();
    static{
        KITCHEN.put(Items.BEEF,Items.COOKED_BEEF);KITCHEN.put(Items.PORKCHOP,Items.COOKED_PORKCHOP);KITCHEN.put(Items.CHICKEN,Items.COOKED_CHICKEN);
        KITCHEN.put(Items.MUTTON,Items.COOKED_MUTTON);KITCHEN.put(Items.RABBIT,Items.COOKED_RABBIT);KITCHEN.put(Items.COD,Items.COOKED_COD);
        KITCHEN.put(Items.SALMON,Items.COOKED_SALMON);KITCHEN.put(Items.POTATO,Items.BAKED_POTATO);
    }
    /** Seed potatoes stay raw. */
    static int cookable(Item raw,int have){return raw==Items.POTATO?Math.max(0,have-32):have;}
    /**
     * In the evening the tavern cooks raw meat, fish and spare potatoes from the store (a lump of fuel
     * for every eight, as in a furnace) and brews mead from spare wheat into its barrels.
     */
    static void tavern(Village v,TownBuilding tavern){
        int cooked=0;
        for(var e:KITCHEN.entrySet()){
            Item raw=e.getKey();
            // Only as much as the store has room for: a full store leaves the meat raw.
            int n=Math.min(Math.min(COOKS-cooked,cookable(raw,v.stock.count(s->s.is(raw)))),room(v,new ItemStack(e.getValue())));
            if(n<=0)continue;
            int fuel=(n+7)/8;
            if(v.stock.count(VillageCrafting.FUEL)<fuel)break;
            v.stock.consume(VillageCrafting.FUEL,fuel);
            int took=v.stock.consume(s->s.is(raw),n);
            store(v,tavern,Blocks.BREWING_STAND,new ItemStack(e.getValue(),took));
            cooked+=took;
            if(cooked>=COOKS)break;
        }
        int brewed=0;
        var barrels=barrels(v,tavern);
        // Bread first: mead only from the wheat a well-fed village has left over.
        for(int i=0;i<MEADS&&!barrels.isEmpty()&&v.stock.count(VillageCrafting.WHEAT)>=32+WHEAT_PER_MEAD;i++){
            var mead=mead(v.state.name);
            var barrel=barrels.stream().filter(b->fits(b,mead)).findFirst().orElse(null);
            if(barrel==null)break;
            v.stock.consume(VillageCrafting.WHEAT,WHEAT_PER_MEAD);
            add(barrel,mead);brewed++;
        }
        if(cooked>0)v.chronicle.add("cooked",cooked);
        if(brewed>0)v.chronicle.add("mead",brewed);
        if(cooked+brewed>0)show(v,tavern,Blocks.BREWING_STAND,brewed>0?SoundEvents.BREWING_STAND_BREW:SoundEvents.SMOKER_SMOKE);
    }
    /** The village's mead: a golden drink that heals a little and fills the belly. */
    static ItemStack mead(String village){
        var mug=new ItemStack(Items.POTION);
        mug.set(DataComponents.POTION_CONTENTS,new PotionContents(Optional.empty(),Optional.of(0xE0A030),
                List.of(new MobEffectInstance(MobEffects.REGENERATION,200,0),new MobEffectInstance(MobEffects.SATURATION,1,1)),Optional.empty()));
        mug.set(DataComponents.CUSTOM_NAME,Component.literal("Медовуха «"+village+"»").withStyle(net.minecraft.ChatFormatting.GOLD));
        mug.set(DataComponents.LORE,new ItemLore(List.of(Component.literal("Сварено в таверне деревни "+village).withStyle(net.minecraft.ChatFormatting.GRAY))));
        return mug;
    }
    private static boolean fits(Container c,ItemStack s){for(int i=0;i<c.getContainerSize();i++)if(c.getItem(i).isEmpty())return true;return false;}
    private static void add(Container c,ItemStack s){for(int i=0;i<c.getContainerSize();i++)if(c.getItem(i).isEmpty()){c.setItem(i,s);c.setChanged();return;}}

    // --- mill and pier ----------------------------------------------------------------------------
    static final int MILL_BREAD=8,WHEAT_KEPT=16;
    /** Loaves the mill bakes from the wheat above what the fields keep for themselves. */
    static int loaves(int wheat){return Math.clamp((wheat-WHEAT_KEPT)/3,0,MILL_BREAD);}
    static void mill(Village v,TownBuilding mill){
        int n=Math.min(loaves(v.stock.count(VillageCrafting.WHEAT)),room(v,new ItemStack(Items.BREAD)));
        if(n<=0)return;
        int took=v.stock.consume(VillageCrafting.WHEAT,n*3);n=took/3;
        // Wheat short of a loaf goes back.
        if(took%3>0)store(v,mill,Blocks.COMPOSTER,new ItemStack(Items.WHEAT,took%3));
        if(n<=0)return;
        store(v,mill,Blocks.COMPOSTER,new ItemStack(Items.BREAD,n));
        v.chronicle.add("milled",n);
        show(v,mill,Blocks.COMPOSTER,SoundEvents.VILLAGER_WORK_FARMER);
    }
    /** The morning's catch off the pier, while the water is still there: three cod and a salmon, what the store has room for. */
    static void dock(Village v,TownBuilding dock){
        boolean water=dock.blocks.stream().anyMatch(p->v.level.hasChunkAt(p.position())&&v.level.getFluidState(p.position().below()).is(net.minecraft.tags.FluidTags.WATER));
        if(!water)return;
        int cod=Math.min(3,room(v,new ItemStack(Items.COD))),salmon=Math.min(1,room(v,new ItemStack(Items.SALMON)));
        if(cod+salmon<=0)return;
        if(cod>0)store(v,dock,Blocks.BARREL,new ItemStack(Items.COD,cod));
        if(salmon>0)store(v,dock,Blocks.BARREL,new ItemStack(Items.SALMON,salmon));
        v.chronicle.add("fished",cod+salmon);
        show(v,dock,Blocks.BARREL,SoundEvents.FISHING_BOBBER_SPLASH);
    }

    // --- shared -----------------------------------------------------------------------------------
    static List<BarrelBlockEntity> barrels(Village v,TownBuilding b){
        var out=new ArrayList<BarrelBlockEntity>();
        for(var p:b.blocks)if(p.block().is(Blocks.BARREL)&&v.level.getBlockEntity(p.position()) instanceof BarrelBlockEntity barrel)out.add(barrel);
        return out;
    }
    /** Room the store's chests have for more of these goods, filling partial stacks first as VillageStock.put does. */
    static int room(List<ItemStack> slots,ItemStack goods){
        int n=0;
        for(var slot:slots)n+=slot.isEmpty()?goods.getMaxStackSize():ItemStack.isSameItemSameComponents(slot,goods)?Math.max(0,slot.getMaxStackSize()-slot.getCount()):0;
        return n;
    }
    private static int room(Village v,ItemStack goods){
        var slots=new ArrayList<ItemStack>();
        for(var chest:v.stock.chests())for(int i=0;i<chest.getContainerSize();i++)slots.add(chest.getItem(i));
        return room(slots,goods);
    }
    /** Goods go into the store; what does not fit is handed to {@code spill}, never thrown away. Returns what was stored. */
    static int keep(ItemStack goods,java.util.function.UnaryOperator<ItemStack> store,java.util.function.Consumer<ItemStack> spill){
        var rest=store.apply(goods);
        if(!rest.isEmpty())spill.accept(rest.copy());
        return goods.getCount()-rest.getCount();
    }
    /** Into the store, or set down at the building's workstation when the chests filled up meanwhile. */
    private static void store(Village v,TownBuilding b,Block station,ItemStack goods){
        keep(goods,v.stock::put,rest->{
            BlockPos at=station(v,b,station);
            var item=new net.minecraft.world.entity.item.ItemEntity(v.level,at.getX()+.5,at.getY()+1.1,at.getZ()+.5,rest);
            item.setDefaultPickUpDelay();v.level.addFreshEntity(item);
        });
    }
    private static BlockPos station(Village v,TownBuilding b,Block station){
        return b.blocks.stream().map(TownBuilding.Plan::position).filter(p->v.level.hasChunkAt(p)&&v.level.getBlockState(p).is(station)).findFirst().orElse(BlockPos.of(b.origin));
    }
    /** Sparks and a sound at the building's workstation, for whoever is near. */
    private static void show(Village v,TownBuilding b,Block station,SoundEvent sound){
        BlockPos at=station(v,b,station);
        v.level.playSound(null,at,sound,SoundSource.BLOCKS,1f,1f);
        v.level.sendParticles(ParticleTypes.HAPPY_VILLAGER,at.getX()+.5,at.getY()+1.2,at.getZ()+.5,6,.4,.3,.4,0);
    }
}
