package dev.autonomousvillages;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;

/** One field action: harvest and replant a ripe crop, plant empty farmland, till soil by water or gather seeds from grass. */
final class FarmJob extends Job {
    enum Kind { HARVEST, PLANT, TILL, SEEDS }
    final Kind task;
    final BlockPos cell;
    private Approach approach;
    private Harvest harvest;
    FarmJob(Worker w,Kind task,BlockPos cell){
        super(w,switch(task){case HARVEST->"собирает урожай";case PLANT->"сажает";case TILL->"вспахивает землю";case SEEDS->"ищет семена в траве";});
        this.task=task;this.cell=cell.immutable();
    }
    @Override String kind(){return "farm";}
    @Override String activity(){return "farm";}
    @Override boolean mayBreak(BlockPos p){return task==Kind.HARVEST&&p.equals(cell);}
    @Override BlockPos target(){return cell;}
    @Override int timeLimit(){return 1200;}
    @Override boolean atomic(){return harvest!=null;}
    @Override boolean tick(){
        if(harvest==null){
            if(approach==null)approach=new Approach(cell,4.0,false);
            var state=approach.tick();
            if(state==Approach.State.FAILED)return fail("не подойти к грядке");
            if(state==Approach.State.MOVING)return true;
            switch(task){
                case PLANT->{plant(cell);return false;}
                case TILL->{
                    if(!v.level.getBlockState(cell).isAir()&&Construction.clear(v.level.getBlockState(cell))){harvest=new Harvest(cell);return true;}
                    till();return false;
                }
                default->harvest=new Harvest(cell);
            }
        }
        var state=harvest.tick();
        if(state==Harvest.State.WORKING)return true;
        if(state==Harvest.State.FAILED)return fail("не вышло: "+harvest.error);
        if(task==Kind.HARVEST){plant(cell);v.chronicle.harvested();}
        if(task==Kind.TILL)till();
        return false;
    }
    private void till(){
        BlockPos soil=cell.below();
        int hoe=Supplies.find(bot(),s->s.is(ItemTags.HOES)&&Supplies.usable(s));
        if(hoe<0||!v.level.getBlockState(cell).isAir()||!VillageIndex.naturalSoil(v.level.getBlockState(soil))||!watered(soil)||!v.state.policy.allows("farm",soil))return;
        bot().inventory().select(hoe);
        v.level.setBlock(soil,Blocks.FARMLAND.defaultBlockState(),3);
        body().getMainHandItem().hurtAndBreak(1,body(),EquipmentSlot.MAINHAND);
        v.index.soil.add(cell);v.state.plantedCrops.add(cell.asLong());
        plant(cell);
    }
    static Item seed(Worker w){
        for(Item item:new Item[]{Items.WHEAT_SEEDS,Items.CARROT,Items.POTATO,Items.BEETROOT_SEEDS})if(w.count(s->s.is(item))>0)return item;
        return null;
    }
    private void plant(BlockPos p){
        Item item=seed(w);
        if(item==null||!v.state.policy.allows("farm",p)||!v.level.getBlockState(p).isAir()||!v.level.getBlockState(p.below()).is(Blocks.FARMLAND))return;
        Block block=item==Items.WHEAT_SEEDS?Blocks.WHEAT:item==Items.CARROT?Blocks.CARROTS:item==Items.POTATO?Blocks.POTATOES:Blocks.BEETROOTS;
        if(block.defaultBlockState().canSurvive(v.level,p)&&Supplies.consume(bot(),item,1)){
            v.level.setBlock(p,block.defaultBlockState(),3);v.index.crops.add(p);v.state.plantedCrops.add(p.asLong());
            body().swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        }
    }
    static boolean watered(net.minecraft.world.level.Level level,BlockPos soil){
        for(BlockPos p:BlockPos.betweenClosed(soil.offset(-4,0,-4),soil.offset(4,1,4)))if(level.getFluidState(p).is(FluidTags.WATER))return true;
        return false;
    }
    private boolean watered(BlockPos soil){return watered(v.level,soil);}
    @Override void stop(){if(approach!=null)approach.cancel();if(harvest!=null)harvest.cancel();}
}
