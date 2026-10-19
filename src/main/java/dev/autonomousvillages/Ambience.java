package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * What a player near the village sees and hears besides the work itself: smoke over lived-in homes
 * from evening to morning, fireflies over the gardens at night, residents greeting a passer-by and
 * frowning when a task fails. Particles and sounds only; nothing here changes the world, and with
 * no player near it costs nothing.
 */
final class Ambience {
    /** Clock hours (of 24000) when the hearths are lit: from evening until the morning. */
    static boolean hearthHours(long clock){long hour=Math.floorMod(clock,24000);return hour>=11000||hour<1500;}
    static final int GREET_RANGE=5;
    private final Village v;
    /** Top block of each home, where the chimney smoke rises; and whether anyone sleeps there. */
    private final Map<Long,BlockPos> chimneys=new HashMap<>();
    private final Map<Long,Boolean> occupied=new HashMap<>();
    private long occupancyChecked=Village.LONG_AGO;
    private final Map<UUID,Long> greeted=new HashMap<>();
    private final Map<UUID,Long> frowned=new HashMap<>();
    private final Random random=new Random();
    Ambience(Village v){this.v=v;}

    void tick(long time){
        if(!AutonomousVillagesMod.CONFIG.ambience||time%20!=9)return;
        var players=nearPlayers();
        if(players.isEmpty())return;
        frowned.values().removeIf(until->until<=time);
        long clock=v.level.getOverworldClockTime();
        if(hearthHours(clock)||v.level.isRaining())chimneySmoke(time);
        if(v.level.isDarkOutside()&&!v.level.isRaining())fireflies();
        if(!Worker.nightRestWindow(clock,v.level.isDarkOutside()))greet(players,time);
    }
    private List<ServerPlayer> nearPlayers(){
        var area=VillageDiscovery.area(v.state,48);var out=new ArrayList<ServerPlayer>();
        for(var player:v.runtime.server.getPlayerList().getPlayers())
            if(player.level()==v.level&&!player.isSpectator()&&area.contains(player.position()))out.add(player);
        return out;
    }
    private void chimneySmoke(long time){
        if(time-occupancyChecked>=600){occupancyChecked=time;occupied.clear();}
        for(var b:v.state.buildings){
            if(!NameplateJob.home(b))continue;
            if(!occupied.computeIfAbsent(b.origin,o->!NameplateJob.residents(v,b).isEmpty()))continue;
            BlockPos top=chimneys.computeIfAbsent(b.origin,o->roofTop(b));
            if(top==null||!v.level.hasChunkAt(top)||!v.level.canSeeSky(top.above()))continue;
            // One wisp a second, drifting straight up like a campfire's; seen from across the valley.
            v.level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE,true,false,top.getX()+.5+random.nextGaussian()*.1,top.getY()+1.1,top.getZ()+.5+random.nextGaussian()*.1,
                    0,0,1,0,.07);
        }
    }
    /** The highest block of the building, the one nearest its middle on a tie. */
    static BlockPos roofTop(TownBuilding b){
        if(b.blocks.isEmpty())return null;
        double cx=b.blocks.stream().mapToInt(p->p.position().getX()).average().orElse(0),cz=b.blocks.stream().mapToInt(p->p.position().getZ()).average().orElse(0);
        return b.blocks.stream().map(TownBuilding.Plan::position)
                .max(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparingDouble(p->-Math.hypot(p.getX()-cx,p.getZ()-cz))).orElse(null);
    }
    /** A few fireflies drift over the grass by two homes at a time. */
    private void fireflies(){
        var homes=v.state.buildings.stream().filter(NameplateJob::home).toList();
        if(homes.isEmpty())return;
        for(int i=0;i<2;i++){
            var b=homes.get(random.nextInt(homes.size()));
            BlockPos origin=b.blocks.isEmpty()?BlockPos.of(b.origin):b.blocks.get(random.nextInt(b.blocks.size())).position();
            int x=origin.getX()+random.nextInt(9)-4,z=origin.getZ()+random.nextInt(9)-4;
            BlockPos ground=new BlockPos(x,origin.getY(),z);
            if(!v.level.hasChunkAt(ground))continue;
            int y=v.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z);
            if(Math.abs(y-origin.getY())>6)continue;
            v.level.sendParticles(ParticleTypes.FIREFLY,x+.5,y+.8,z+.5,3,1.2,.6,1.2,0);
        }
    }
    /** A resident a player walks up to turns to them and says hello, at most once in two minutes. */
    private void greet(List<ServerPlayer> players,long time){
        greeted.values().removeIf(until->until<=time);
        for(var player:players){
            if(player.isInvisible())continue;
            for(Worker w:v.workers.values()){
                if(!w.active()||greeted.containsKey(w.id)||w.fleeing||w.combat!=null)continue;
                Villager body=w.body();
                if(body.isSleeping()||body.isTrading()||body.distanceToSqr(player)>GREET_RANGE*GREET_RANGE||!body.hasLineOfSight(player))continue;
                greeted.put(w.id,time+2400);
                body.getLookControl().setLookAt(player);
                v.level.playSound(null,body.blockPosition(),SoundEvents.VILLAGER_AMBIENT,SoundSource.NEUTRAL,.8f,1f+(random.nextFloat()-.5f)*.2f);
                v.level.sendParticles(ParticleTypes.HAPPY_VILLAGER,body.getX(),body.getY()+2.1,body.getZ(),3,.25,.15,.25,0);
                break; // one hello per player a second is plenty
            }
        }
    }
    /** A cross face over a resident whose task just failed, so a player sees who is stuck; once a minute at most. */
    void frown(Worker w){
        if(!AutonomousVillagesMod.CONFIG.ambience||!w.active())return;
        long now=v.level.getGameTime();
        if(frowned.getOrDefault(w.id,Village.LONG_AGO)>now)return;
        frowned.put(w.id,now+1200);
        var body=w.body();
        v.level.sendParticles(ParticleTypes.ANGRY_VILLAGER,body.getX(),body.getY()+2.1,body.getZ(),1,.2,.1,.2,0);
    }
    /** A puff of the block's own dust where a builder set it. */
    static void puff(Village v,BlockPos p,BlockState state){
        if(!AutonomousVillagesMod.CONFIG.ambience||state.isAir())return;
        v.level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK,state),p.getX()+.5,p.getY()+.5,p.getZ()+.5,6,.3,.3,.3,0);
    }
}
