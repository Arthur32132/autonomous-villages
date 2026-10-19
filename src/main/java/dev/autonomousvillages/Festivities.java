package dev.autonomousvillages;

import it.unimi.dsi.fastutil.ints.IntList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;

/** Small celebrations players can see from afar: a housewarming over a finished house, hearts for a newborn. */
final class Festivities {
    private Festivities() { }
    private static final int[][] COLOURS={{0xF9D71C,0xFF8C00},{0x3AB3DA,0xFFFFFF},{0x80C71F,0xF9D71C},{0xC74EBD,0x3AB3DA}};

    /** Fireworks well above the roof (nobody stands where they burst), a bell chime and happy sparks at the door. */
    static void housewarming(Village v,TownBuilding b){
        var level=v.level;BlockPos origin=BlockPos.of(b.origin);
        int top=b.blocks.stream().mapToInt(p->p.position().getY()).max().orElse(origin.getY());
        double cx=b.blocks.stream().mapToInt(p->p.position().getX()).average().orElse(origin.getX())+.5;
        double cz=b.blocks.stream().mapToInt(p->p.position().getZ()).average().orElse(origin.getZ())+.5;
        for(int i=0;i<3;i++){
            int[] c=COLOURS[Math.floorMod(b.origin+i,COLOURS.length)];
            var rocket=new ItemStack(Items.FIREWORK_ROCKET);
            rocket.set(DataComponents.FIREWORKS,new Fireworks(1+i%2,List.of(new FireworkExplosion(i==1?FireworkExplosion.Shape.STAR:FireworkExplosion.Shape.LARGE_BALL,
                    IntList.of(c[0]),IntList.of(c[1]),true,true))));
            level.addFreshEntity(new FireworkRocketEntity(level,cx+(i-1)*1.5,top+2,cz,rocket));
        }
        level.playSound(null,BlockPos.containing(cx,top,cz),SoundEvents.BELL_BLOCK,SoundSource.BLOCKS,1.5f,1.2f);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER,cx,origin.getY()+1.5,cz,40,3,1.5,3,0);
        // Residents nearby rejoice like after a good trade.
        for(var w:v.workers.values())if(w.active()&&w.body().distanceToSqr(cx,origin.getY(),cz)<24*24)
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER,w.body().getX(),w.body().getY()+2.1,w.body().getZ(),6,.3,.3,.3,0);
    }

    static void birth(Village v,Villager baby){
        v.level.sendParticles(ParticleTypes.HEART,baby.getX(),baby.getY()+.8,baby.getZ(),7,.5,.4,.5,0);
        v.level.playSound(null,baby.blockPosition(),SoundEvents.VILLAGER_CELEBRATE,SoundSource.NEUTRAL,1f,1.4f);
    }
}
