package dev.autonomousvillages;

import dev.navigationai.api.BotHandle;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

/**
 * Builds the village campfire: a ready campfire from the store, or three sticks, a coal and three logs
 * fetched from it and crafted at a workbench by the vanilla recipe, then set on the chosen site by the square.
 */
final class CampfireJob extends Job {
    static final Predicate<ItemStack> CAMPFIRE_ITEM=s->s.is(Items.CAMPFIRE);
    /** The vanilla shape: S a stick, C coal or charcoal, L a log. */
    static final String[] PATTERN={" S ","SCS","LLL"};
    final BlockPos site;
    private Fetch fetch;
    private Approach table,approach;
    private boolean fetched;
    CampfireJob(Worker w,BlockPos site){super(w,"складывает костёр на площади");this.site=site.immutable();}
    @Override String kind(){return "campfire";}
    @Override BlockPos target(){return site;}
    @Override int timeLimit(){return 4800;}
    @Override boolean keeps(ItemStack s){return CAMPFIRE_ITEM.test(s)||VillageCrafting.STICK.test(s)||VillageCrafting.PLANK.test(s)||VillageCrafting.LOG.test(s)||VillageCrafting.FUEL.test(s);}
    @Override boolean tick(){
        if(!w.has(CAMPFIRE_ITEM)){
            if(!fetched){
                if(fetch==null)fetch=new Fetch(needs());
                var state=fetch.tick();
                if(state==Approach.State.FAILED)return fail("нет на складе: "+fetch.missing);
                if(state==Approach.State.MOVING)return true;
                fetched=true;
                if(w.has(CAMPFIRE_ITEM))return true;
            }
            if(v.index.tables.stream().noneMatch(p->VillageCrafting.nearTable(bot(),v,p))){
                BlockPos nearest=v.index.tables.stream().filter(p->v.level.hasChunkAt(p)&&v.level.getBlockState(p).is(Blocks.CRAFTING_TABLE)&&reachable(p))
                        .min(Comparator.comparingDouble(p->p.distSqr(body().blockPosition()))).orElse(null);
                if(nearest==null)return fail("нет верстака");
                if(table==null||!table.target.equals(nearest))table=new Approach(nearest,3.5,true);
                var state=table.tick();
                if(state==Approach.State.FAILED){unreachable(table.target);return fail("верстак недоступен");}
                if(state==Approach.State.MOVING)return true;
            }
            status="мастерит костёр";
            // One craft a tick, so the hands are seen at work: planks, sticks, then the campfire.
            if(w.count(VillageCrafting.STICK)<3){
                if(w.count(VillageCrafting.PLANK)<2)return w.count(VillageCrafting.LOG)>3&&VillageCrafting.craft(bot(),v,VillageCrafting.PLANKS)||fail("не вышло: доски");
                return VillageCrafting.craft(bot(),v,VillageCrafting.STICKS)||fail("не вышло: палки");
            }
            if(!craft(bot(),v))return fail("не вышло: костёр");
            v.stock.invalidate();
            return true;
        }
        if(approach==null)approach=new Approach(site,4.0,false);
        var reach=approach.tick();
        if(reach==Approach.State.MOVING){status="несёт костёр на площадь";return true;}
        if(reach==Approach.State.FAILED){v.rejected.put(site.asLong(),now()+2400);return fail("не подойти к месту костра");}
        if(!CampfireFeature.fits(CampfireFeature.ground(v),site)){v.rejected.put(site.asLong(),now()+2400);return fail("место для костра занято");}
        BlockPos hub=v.meetingPoint();
        int dx=hub.getX()-site.getX(),dz=hub.getZ()-site.getZ();
        var facing=Math.abs(dx)>Math.abs(dz)?(dx>0?Direction.EAST:Direction.WEST):(dz>0?Direction.SOUTH:Direction.NORTH);
        var state=Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.FACING,facing);
        if(!Construction.place(v,bot(),new Construction.Step(site,state,Items.CAMPFIRE)))return fail("не удалось поставить костёр");
        CampfireFeature.built(v,site);
        return false;
    }
    /** A campfire from the store if there is one, else its materials as {@link CampfireFeature#kit} counts them. */
    private List<Need> needs(){
        if(v.stock.count(CAMPFIRE_ITEM)>0)return List.of(new Need(CAMPFIRE_ITEM,1,"костёр"));
        var k=CampfireFeature.kit(v.stock.count(VillageCrafting.STICK)+w.count(VillageCrafting.STICK),v.stock.count(VillageCrafting.PLANK)+w.count(VillageCrafting.PLANK));
        var out=new ArrayList<Need>();
        out.add(new Need(VillageCrafting.FUEL,k.fuel(),"уголь"));
        out.add(new Need(VillageCrafting.LOG,k.logs(),"брёвна"));
        if(k.sticks()>0)out.add(new Need(VillageCrafting.STICK,k.sticks(),"палки"));
        if(k.planks()>0)out.add(new Need(VillageCrafting.PLANK,k.planks(),"доски"));
        return out;
    }
    @Override void stop(){if(fetch!=null)fetch.cancel();if(table!=null)table.cancel();if(approach!=null)approach.cancel();}

    /**
     * The crafting grid for one campfire taken from a copy of the bag, which loses exactly those items;
     * null when something is missing. VillageCrafting has one material per recipe, the campfire two.
     */
    static List<ItemStack> grid(List<ItemStack> bag){
        var grid=new ArrayList<ItemStack>();
        for(String row:PATTERN)for(char c:row.toCharArray()){
            if(c==' '){grid.add(ItemStack.EMPTY);continue;}
            Predicate<ItemStack> ingredient=c=='S'?VillageCrafting.STICK:c=='C'?VillageCrafting.FUEL:VillageCrafting.LOG;
            ItemStack chosen=bag.stream().filter(s->!s.isEmpty()&&Inventory.isUsableForCrafting(s)&&ingredient.test(s)).findFirst().orElse(null);
            if(chosen==null)return null;
            grid.add(chosen.copyWithCount(1));chosen.shrink(1);
        }
        return grid;
    }
    /** Crafts one campfire from the bag at a workbench through the vanilla recipe, as {@link VillageCrafting#craft} does. */
    static boolean craft(BotHandle b,Village v){
        if(v.level.getGameRules().get(GameRules.LIMITED_CRAFTING))return false;
        var before=Supplies.copy(b.inventory().snapshot().slots());var next=Supplies.copy(before);
        var grid=grid(next);
        if(grid==null)return false;
        CraftingInput input=CraftingInput.of(3,3,grid);
        var recipe=v.level.recipeAccess().getRecipeFor(RecipeType.CRAFTING,input,v.level).orElse(null);
        if(recipe==null)return false;
        ItemStack output=recipe.value().assemble(input);
        if(output.isEmpty()||!CAMPFIRE_ITEM.test(output)||!output.isItemEnabled(v.level.enabledFeatures()))return false;
        if(v.index.tables.stream().noneMatch(p->VillageCrafting.nearTable(b,v,p)))return false;
        for(ItemStack rest:recipe.value().getRemainingItems(input))if(!rest.isEmpty()&&!Supplies.insert(next,rest))return false;
        boolean made=Supplies.insert(next,output)&&Supplies.replace(b,before,next);
        if(made){b.entity().swing(InteractionHand.MAIN_HAND);v.chronicle.crafted(output);}
        return made;
    }

    /**
     * An evening by the fire: the resident walks to its own cell of the ring, stands facing the fire for
     * a while, now and then says something, and leaves at once when the evening ends, the fire goes out or
     * the alarm sounds.
     */
    static final class Evening extends Job {
        final BlockPos fire,cell;
        final int linger;
        private Walk walk;
        private long arrivedAt=-1;
        private int rewalks;
        Evening(Worker w,BlockPos fire,BlockPos cell,int linger){super(w,"идёт к костру");this.fire=fire.immutable();this.cell=cell.immutable();this.linger=linger;}
        @Override String kind(){return "fireside";}
        @Override BlockPos target(){return cell;}
        @Override int timeLimit(){return 1200;}
        @Override boolean tick(){
            if(!CampfireFeature.gathering(v.level.getOverworldClockTime())||v.alarm()||!CampfireFeature.lit(v,fire)||CampfireFeature.wet(v,fire))return false;
            Vec3 spot=v.feetPosition(cell);
            if(arrivedAt<0||body().position().distanceToSqr(spot)>2.5*2.5){
                if(walk==null){if(arrivedAt>=0&&++rewalks>2)return false;walk=new Walk(spot,.7);}
                var s=walk.tick();
                if(s==Approach.State.FAILED)return arrivedAt>=0?false:fail("не дойти до костра");
                if(s==Approach.State.MOVING){status="идёт к костру";return true;}
                walk=null;
                if(arrivedAt<0){arrivedAt=now();CampfireFeature.arrived(v);}
            }
            status="отдыхает у костра";
            var e=body();
            e.getLookControl().setLookAt(fire.getX()+.5,fire.getY()+.4,fire.getZ()+.5);
            var random=e.getRandom();
            if(random.nextInt(90)==0){
                v.level.playSound(null,e.blockPosition(),random.nextBoolean()?SoundEvents.VILLAGER_AMBIENT:SoundEvents.VILLAGER_YES,SoundSource.NEUTRAL,.8f,.9f+random.nextFloat()*.2f);
                v.level.sendParticles(ParticleTypes.NOTE,e.getX(),e.getY()+2.2,e.getZ(),0,random.nextInt(25)/24.0,0,0,1);
            }
            return now()-arrivedAt<linger;
        }
        @Override void stop(){if(walk!=null)walk.cancel();}
    }
}
