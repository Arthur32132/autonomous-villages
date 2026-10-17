package dev.autonomousvillages;

import java.util.Comparator;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;

/** A resident carries existing workshop supplies to a traveller whose last pick wore out. */
final class SupplyRuns {
    static void tick(Village v){
        if(v.level.getGameTime()%100!=0||v.workers.values().stream().anyMatch(r->"supply".equals(r.job)))return;
        for(var stranded:v.workers.values()){
            if(stranded.bot()==null||!stranded.body().isAlive()||v.state.contains(stranded.body().blockPosition())
                    ||stranded.combat!=null||stranded.mining!=null||stranded.excavation!=null
                    ||Supplies.find(stranded.bot(),s->s.is(ItemTags.PICKAXES)&&Supplies.usable(s))>=0)continue;
            var helper=v.workers.values().stream().filter(r->r!=stranded&&r.canHelp()
                    &&!v.expeditions.owns(r.id)&&v.state.contains(r.body().blockPosition())&&r.body().distanceToSqr(stranded.body())<256*256
                    &&Supplies.find(r.bot(),s->s.is(ItemTags.PICKAXES)&&Supplies.usable(s)&&s.getMaxDamage()-s.getDamageValue()>=32)>=0
                    &&(r.bot().inventory().count(Items.CRAFTING_TABLE)>0||Supplies.find(r.bot(),s->s.is(ItemTags.PLANKS)&&s.getCount()>=8)>=0))
                    .min(Comparator.<Resident>comparingInt(r->v.porter(r.id)?0:1)
                            .thenComparingDouble(r->r.body().distanceToSqr(stranded.body()))).orElse(null);
            if(helper==null)continue;
            helper.cancel();
            helper.assign("supply",stranded.body().blockPosition());
            if(!"supply".equals(helper.job))continue;
            helper.supplyRecipient=stranded.id;helper.status="несёт припасы застрявшему путнику";
            v.event("Отправили жителя с припасами на помощь путнику");return;
        }
    }
}
