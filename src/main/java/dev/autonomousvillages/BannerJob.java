package dev.autonomousvillages;

import java.util.*;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * Makes one banner of the village arms and puts it up: fetches six wool of the field's colour (or white
 * wool and dyes to colour it) and a stick, crafts the banner at a workbench, adds the loom layers it has
 * dyes for (one dye a layer; flowers are made into dyes first) and hangs or stands it at the site.
 * Without a field ({@code field} null) a spare banner of the arms' colour comes from the store instead.
 */
final class BannerJob extends Job {
    final CoatOfArms arms;
    final CoatOfArms.Site site;
    private final CoatOfArms.Field field;
    private final int layers;
    private final boolean sticks;
    private final Item banner;
    private Fetch fetch,extra;
    private Approach table,approach;
    private boolean fetched,extraFetched,crafted,patterned;
    BannerJob(Worker w,CoatOfArms arms,CoatOfArms.Site site,CoatOfArms.Field field,int layers,boolean sticks){
        super(w,"шьёт флаг деревни");this.arms=arms;this.site=site;this.field=field;this.layers=layers;this.sticks=sticks;this.banner=Items.BANNER.pick(arms.base());
    }
    @Override String kind(){return "banner";}
    @Override BlockPos target(){return site.pos();}
    @Override int timeLimit(){return 4800;}
    @Override boolean keeps(ItemStack s){
        return s.is(banner)||!crafted&&(s.is(ItemTags.WOOL)||s.is(ItemTags.DYES)||s.is(Items.STICK)||!sticks&&s.is(ItemTags.PLANKS)||HeraldryFeature.tint(v,s)!=null);
    }
    Predicate<ItemStack> wool(DyeColor c){Item item=Items.WOOL.pick(c);return s->s.is(item);}
    Predicate<ItemStack> dye(DyeColor c){Item item=Items.DYE.pick(c);return s->s.is(item);}
    /** A dye of the colour, or a flower that makes one. */
    Predicate<ItemStack> tint(DyeColor c){return s->HeraldryFeature.tint(v,s)==c;}
    /** Dyes of a colour this banner still takes: the field's while its wool is white, and the layers'. */
    int dyesWanted(DyeColor c){
        int n=arms.dyesFor(patterned?0:layers).getOrDefault(c,0);
        if(field==CoatOfArms.Field.DYED&&c==arms.base()&&!crafted)n+=Math.max(0,CoatOfArms.WOOL_PER_BANNER-w.count(wool(c)));
        return n;
    }

    @Override boolean tick(){
        // A banner left in the store by an earlier attempt goes up as it is.
        if(field==null&&!crafted){
            if(fetch==null)fetch=new Fetch(List.of(new Need(s->s.is(banner),1,"флаг")));
            var state=fetch.tick();
            if(state==Approach.State.FAILED)return fail("нет на складе: флаг");
            if(state==Approach.State.MOVING)return true;
            crafted=patterned=true;
        }
        if(!crafted){
            if(!fetched){
                if(fetch==null){
                    var needs=new ArrayList<Need>();
                    if(field==CoatOfArms.Field.WOOL)needs.add(new Need(wool(arms.base()),CoatOfArms.WOOL_PER_BANNER,"шерсть"));
                    else{needs.add(new Need(wool(DyeColor.WHITE),CoatOfArms.WOOL_PER_BANNER,"белая шерсть"));needs.add(new Need(tint(arms.base()),CoatOfArms.WOOL_PER_BANNER,"краситель"));}
                    needs.add(sticks?new Need(VillageCrafting.STICK,1,"палка"):new Need(VillageCrafting.PLANK,2,"доски"));
                    fetch=new Fetch(needs);
                }
                var state=fetch.tick();
                if(state==Approach.State.FAILED)return fail("нет на складе: "+fetch.missing);
                if(state==Approach.State.MOVING)return true;
                fetched=true;
            }
            if(!extraFetched){
                // Dyes for the layers are a bonus: what the store no longer has is left out of the design.
                if(extra==null){
                    var needs=new ArrayList<Need>();
                    for(var e:arms.dyesFor(layers).entrySet())needs.add(new Need(tint(e.getKey()),e.getValue()+(field==CoatOfArms.Field.DYED&&e.getKey()==arms.base()?CoatOfArms.WOOL_PER_BANNER:0),"краситель"));
                    extra=new Fetch(needs);
                }
                var state=extra.tick();
                if(state==Approach.State.MOVING)return true;
                extraFetched=true;
            }
            // The wool goes back to the store if a house has come to wait for beds meanwhile.
            if(v.planner.snapshot().need("bed")>0)return fail("шерсть нужна на кровати");
            if(v.index.tables.stream().noneMatch(p->VillageCrafting.nearTable(bot(),v,p))){
                BlockPos nearest=v.index.tables.stream().filter(p->v.level.getBlockState(p).is(Blocks.CRAFTING_TABLE)&&reachable(p))
                        .min(Comparator.comparingDouble(p->p.distSqr(body().blockPosition()))).orElse(null);
                if(nearest==null)return fail("нет верстака");
                if(table==null||!table.target.equals(nearest))table=new Approach(nearest,3.5,true);
                var state=table.tick();
                if(state==Approach.State.FAILED){unreachable(table.target);return fail("верстак недоступен");}
                if(state==Approach.State.MOVING)return true;
            }
            return craftStep();
        }
        if(!patterned){patterned=true;applyLayers();return true;}
        status="поднимает флаг деревни";
        if(approach==null)approach=new Approach(site.pos(),4.4,false);
        var reach=approach.tick();
        if(reach==Approach.State.MOVING)return true;
        if(reach==Approach.State.FAILED){HeraldryFeature.blocked(v,site.key(),now()+12000);return fail("не подойти к месту флага");}
        return raise();
    }
    /** One craft a tick so the hands are seen working: dyes from flowers, dyed wool, a stick, the banner. */
    private boolean craftStep(){
        status="шьёт флаг деревни";
        for(DyeColor c:DyeColor.values()){
            if(w.count(dye(c))>=dyesWanted(c))continue;
            int slot=Supplies.find(bot(),s->!s.is(ItemTags.DYES)&&HeraldryFeature.tint(v,s)==c);
            if(slot<0)continue;
            Item flower=w.bag().get(slot).getItem(),made=Items.DYE.pick(c);
            if(VillageCrafting.craft(bot(),v,new String[]{"M"},s->s.is(flower),s->s.is(made)))return true;
            return fail("не вышло: краситель");
        }
        if(field==CoatOfArms.Field.DYED&&w.count(wool(arms.base()))<CoatOfArms.WOOL_PER_BANNER)
            return dyeWool(arms.base())||fail("не вышло: крашеная шерсть");
        if(w.count(VillageCrafting.STICK)<1)return VillageCrafting.craft(bot(),v,VillageCrafting.STICKS)||fail("не вышло: палки");
        if(!VillageCrafting.craft(bot(),v,new String[]{"MMM","MMM"," S "},wool(arms.base()),s->s.is(banner)))return fail("не вышло: флаг");
        crafted=true;v.stock.invalidate();
        return true;
    }
    /** White wool and a dye make wool of the dye's colour, by the vanilla recipe, from the bag. */
    private boolean dyeWool(DyeColor colour){
        if(v.level.getGameRules().get(GameRules.LIMITED_CRAFTING))return false;
        var before=Supplies.copy(bot().inventory().snapshot().slots());var next=Supplies.copy(before);
        ItemStack d=next.stream().filter(s->Inventory.isUsableForCrafting(s)&&dye(colour).test(s)).findFirst().orElse(null);
        ItemStack white=next.stream().filter(s->Inventory.isUsableForCrafting(s)&&wool(DyeColor.WHITE).test(s)).findFirst().orElse(null);
        if(d==null||white==null)return false;
        var input=CraftingInput.of(2,1,List.of(d.copyWithCount(1),white.copyWithCount(1)));
        var recipe=v.level.recipeAccess().getRecipeFor(RecipeType.CRAFTING,input,v.level).orElse(null);
        if(recipe==null)return false;
        ItemStack out=recipe.value().assemble(input);
        if(!wool(colour).test(out)||!out.isItemEnabled(v.level.enabledFeatures()))return false;
        d.shrink(1);white.shrink(1);
        for(ItemStack rest:recipe.value().getRemainingItems(input))if(!rest.isEmpty()&&!Supplies.insert(next,rest))return false;
        if(!Supplies.insert(next,out)||!Supplies.replace(bot(),before,next))return false;
        bot().entity().swing(net.minecraft.world.InteractionHand.MAIN_HAND);v.chronicle.crafted(out);
        return true;
    }
    /** The loom's work on the new banner: each layer takes one dye from the bag; a layer short of its dye ends the design. */
    private void applyLayers(){
        if(layers==0)return;
        var before=Supplies.copy(bot().inventory().snapshot().slots());var next=Supplies.copy(before);
        ItemStack plain=next.stream().filter(s->s.is(banner)&&s.getOrDefault(DataComponents.BANNER_PATTERNS,BannerPatternLayers.EMPTY).layers().isEmpty()).findFirst().orElse(null);
        if(plain==null)return;
        var patterns=v.level.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN);
        var builder=new BannerPatternLayers.Builder();int n=0;
        for(var layer:arms.layers().subList(0,Math.min(layers,arms.layers().size()))){
            ItemStack d=next.stream().filter(s->dye(layer.colour()).test(s)).findFirst().orElse(null);
            var holder=patterns.get(layer.charge().key());
            if(d==null||holder.isEmpty())break;
            d.shrink(1);builder.add(holder.get(),layer.colour());n++;
        }
        if(n==0)return;
        ItemStack made=plain.copyWithCount(1);made.set(DataComponents.BANNER_PATTERNS,builder.build());plain.shrink(1);
        if(!Supplies.insert(next,made)||!Supplies.replace(bot(),before,next))return;
        bot().entity().swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        v.level.playSound(null,body().blockPosition(),SoundEvents.UI_LOOM_TAKE_RESULT,SoundSource.NEUTRAL,.8f,1f);
    }
    /** Puts the banner up; the block gets the very banner that left the bag, layers and all. */
    private boolean raise(){
        int slot=Supplies.find(bot(),s->s.is(banner));
        if(slot<0)return fail("нет флага");
        ItemStack carried=w.bag().get(slot).copyWithCount(1);
        BlockPos p=site.pos(),hub=v.meetingPoint();
        BlockState state;
        if(site.facing()!=null)state=Blocks.WALL_BANNER.pick(arms.base()).defaultBlockState().setValue(WallBannerBlock.FACING,site.facing());
        else{
            // The face turns toward the bell, as a sign does.
            float yaw=(float)Math.toDegrees(Math.atan2(-(p.getX()-hub.getX()),p.getZ()-hub.getZ()));
            state=Blocks.BANNER.pick(arms.base()).defaultBlockState().setValue(BannerBlock.ROTATION,RotationSegment.convertToSegment(yaw+180f));
        }
        if(v.town.protects(p)||!Construction.place(v,bot(),new Construction.Step(p,state,banner))){
            HeraldryFeature.blocked(v,site.key(),now()+6000);return fail("не удалось поднять флаг");
        }
        if(v.level.getBlockEntity(p) instanceof BannerBlockEntity entity){
            entity.applyComponentsFromItemStack(carried);entity.setChanged();v.level.sendBlockUpdated(p,state,state,3);
        }
        HeraldryFeature.raised(v,site,carried.getOrDefault(DataComponents.BANNER_PATTERNS,BannerPatternLayers.EMPTY).layers().size());
        return false;
    }
    @Override void stop(){if(fetch!=null)fetch.cancel();if(extra!=null)extra.cancel();if(table!=null)table.cancel();if(approach!=null)approach.cancel();}
}
