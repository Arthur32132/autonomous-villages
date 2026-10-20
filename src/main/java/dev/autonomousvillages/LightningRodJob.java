package dev.autonomousvillages;

import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.LightningRodBlock;

/**
 * Sets a lightning rod on the roof of the tower (or the barn): one from the store, else crafted at a workbench
 * from three copper ingots of the store, then carried up and placed on the cell above the roof.
 */
final class LightningRodJob extends Job {
    /** Any lightning rod, plain, weathered or waxed. */
    static final Predicate<ItemStack> ROD=s->s.getItem() instanceof BlockItem b&&b.getBlock() instanceof LightningRodBlock;
    final BlockPos site;
    final boolean tower;
    private CraftJob craft;
    private Fetch fetch;
    private Approach approach;
    LightningRodJob(Worker w,BlockPos site,boolean tower){super(w,"ставит громоотвод");this.site=site.immutable();this.tower=tower;}
    @Override String kind(){return "rod";}
    @Override BlockPos target(){return site;}
    /** Crafting first can cross the village twice: to the store and to a workbench. */
    @Override int timeLimit(){return 6000;}
    @Override boolean keeps(ItemStack s){return ROD.test(s)||StormFeature.COPPER.test(s);}
    @Override boolean tick(){
        // Nobody climbs to a roof under thunder; the rod waits for the storm to pass.
        if(v.level.isThundering())return false;
        if(StormFeature.isRod(v,site)){StormFeature.rodPlaced(v,site,tower,false);return false;}
        if(!w.has(ROD)){
            if(craft==null&&fetch==null){
                if(v.stock.count(ROD)>0)fetch=new Fetch(List.of(new Need(ROD,1,"громоотвод")));
                else craft=new CraftJob(w,StormFeature.ROD,1);
            }
            if(fetch!=null){
                if(fetch.tick()==Approach.State.FAILED)return fail("нет на складе: "+fetch.missing);
                return true;
            }
            if(craft.tick()){status=craft.status;return true;}
            if(!craft.failure.isEmpty())return fail(craft.failure);
            if(!w.has(ROD))return fail("не вышло: громоотвод");
        }
        if(!Construction.clear(v.level.getBlockState(site)))return fail("место для громоотвода занято");
        if(approach==null)approach=new Approach(site,4.5,false);
        var state=approach.tick();
        if(state==Approach.State.MOVING){status="несёт громоотвод на крышу";return true;}
        if(state==Approach.State.FAILED){unreachableCell(site);return fail("не дотянуться до крыши");}
        int slot=Supplies.find(bot(),ROD);
        if(slot<0||!(w.bag().get(slot).getItem() instanceof BlockItem item))return fail("не вышло: громоотвод");
        if(!Construction.place(v,bot(),new Construction.Step(site,item.getBlock().defaultBlockState(),item)))return fail("не удалось поставить громоотвод");
        StormFeature.rodPlaced(v,site,tower,true);
        return false;
    }
    @Override void stop(){if(fetch!=null)fetch.cancel();if(craft!=null)craft.stop();if(approach!=null)approach.cancel();}
}
