package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Days a player remembers: a fair every seventh day once the village has its market (the bell, a
 * wandering trader at the stalls, music and the stalls stocked twice), and a feast at the tavern on
 * the evening a building is finished. Sounds, particles, a chat line and the chronicle; the only thing
 * that enters the world is the trader, who leaves on his own.
 */
final class LifeEvents {
    static final int FAIR_EVERY=7;
    /** The fair is on day 7, 14, 21... (game days counted from 0). */
    static boolean fairDay(long day){return day>0&&day%FAIR_EVERY==0;}
    /** A short folk tune: semitones above F#3 (0..24), played a note every four ticks. */
    static final int[] TUNE={12,14,16,12, 12,14,16,12, 16,17,19,-1, 16,17,19,-1, 19,21,19,17,16,12, 19,21,19,17,16,12, 12,7,12,-1};
    private final Village v;
    private int completed=-1;
    private String feastFor;
    private final PriorityQueue<Note> music=new PriorityQueue<>(Comparator.comparingLong(Note::at));
    private record Note(long at,BlockPos where,SoundEvent sound,float pitch){}
    LifeEvents(Village v){this.v=v;}

    void tick(long time){
        playMusic(time);
        if(!AutonomousVillagesMod.CONFIG.lifeEvents)return;
        if(time%10==4)watchLanterns();
        if(time%20!=11)return;
        long clock=v.level.getOverworldClockTime(),day=Math.floorDiv(clock,24000),hour=Math.floorMod(clock,24000);
        watchCompletions();
        watchBirths();
        if(fairDay(day)&&hour>=1000&&hour<2000&&v.state.fairDay<day)openFair(day);
        if(v.state.fairDay==day&&hour>=1000&&hour<11000&&time%200==11)fairBustle();
        if(feastFor!=null&&hour>=12000&&hour<13500)feast();
    }

    // --- the fair ---------------------------------------------------------------------------------
    private TownBuilding built(String kind){
        return v.state.buildings.stream().filter(b->b.completed&&b.kind.equals(kind)&&v.level.hasChunkAt(BlockPos.of(b.origin))).findFirst().orElse(null);
    }
    private void openFair(long day){
        var market=built("market");
        if(market==null)return;
        v.state.fairDay=day;
        BlockPos square=middle(market);
        v.chronicle.announce("Ярмарка! Сегодня на рынке торгуют весь день.");
        for(int i=0;i<3;i++)music.add(new Note(v.level.getGameTime()+i*30L,v.meetingPoint(),SoundEvents.BELL_BLOCK,1f));
        tune(square,SoundEvents.NOTE_BLOCK_FLUTE.value());
        // The stalls are set out once more for the fair.
        if(AutonomousVillagesMod.CONFIG.landmarkGoods)LandmarkGoods.market(v,market);
        // A travelling merchant comes for the day, unless one is about already.
        if(v.level.getEntitiesOfClass(WanderingTrader.class,new net.minecraft.world.phys.AABB(square).inflate(48)).isEmpty()){
            int y=v.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,square.getX(),square.getZ());
            var trader=EntityTypes.WANDERING_TRADER.spawn(v.level,new BlockPos(square.getX(),y,square.getZ()),EntitySpawnReason.EVENT);
            if(trader!=null){trader.setDespawnDelay(12000);v.chronicle.add("fairTraders",1);}
        }
        v.chronicle.add("fairs",1);
    }
    /** Through the fair day: sparks over the stalls and the chatter of the crowd. */
    private void fairBustle(){
        var market=built("market");
        if(market==null)return;
        BlockPos square=middle(market);
        v.level.sendParticles(ParticleTypes.HAPPY_VILLAGER,square.getX()+.5,square.getY()+2,square.getZ()+.5,12,3,1,3,0);
        v.level.playSound(null,square,SoundEvents.VILLAGER_TRADE,SoundSource.NEUTRAL,.8f,1f);
    }

    // --- the feast --------------------------------------------------------------------------------
    /** A building finished since the last look is celebrated at the tavern that evening. */
    private void watchCompletions(){
        int done=0;TownBuilding last=null;
        for(var b:v.state.buildings)if(b.completed){done++;last=b;}
        if(completed>=0&&done>completed&&last!=null&&!last.kind.equals("tavern"))feastFor=Projects.label(last);
        completed=done;
    }
    private void feast(){
        String reason=feastFor;feastFor=null;
        var tavern=built("tavern");
        if(tavern==null)return;
        BlockPos hall=middle(tavern);
        v.chronicle.announce("Пир в таверне в честь: "+reason+"!");
        tune(hall,SoundEvents.NOTE_BLOCK_HARP.value());
        v.level.sendParticles(ParticleTypes.NOTE,hall.getX()+.5,hall.getY()+2,hall.getZ()+.5,10,2,.6,2,1);
        v.chronicle.add("feasts",1);
    }

    // --- births, the watch, the herald -------------------------------------------------------------
    private long births=-1;
    /** A newborn: the bell twice and a cradle song at the meeting point. */
    private void watchBirths(){
        long now=v.chronicle.total("births");
        if(births>=0&&now>births){
            BlockPos bell=v.meetingPoint();long t=v.level.getGameTime();
            music.add(new Note(t,bell,SoundEvents.BELL_BLOCK,1.3f));music.add(new Note(t+25,bell,SoundEvents.BELL_BLOCK,1.3f));
            long start=t+50;
            for(int i=0;i<CRADLE.length;i++)if(CRADLE[i]>=0)music.add(new Note(start+i*6L,bell,SoundEvents.NOTE_BLOCK_BELL.value(),pitch(CRADLE[i])));
            v.level.sendParticles(ParticleTypes.HEART,bell.getX()+.5,bell.getY()+2,bell.getZ()+.5,8,1.5,.6,1.5,0);
        }
        births=now;
    }
    /** A slow lullaby for a birth. */
    static final int[] CRADLE={19,16,16,-1,19,16,16,-1,19,21,19,17,16};
    /** A guard on the night watch carries the glow of a lantern: a flame at the hand while walking the rounds. */
    private void watchLanterns(){
        if(!v.level.isDarkOutside())return;
        for(Worker w:v.workers.values()){
            if(!(w.job instanceof PatrolJob)||!w.active())continue;
            var body=w.body();
            double yaw=Math.toRadians(body.yBodyRot);
            double x=body.getX()-Math.sin(yaw)*.45-Math.cos(yaw)*.35,z=body.getZ()+Math.cos(yaw)*.45-Math.sin(yaw)*.35;
            v.level.sendParticles(ParticleTypes.SMALL_FLAME,x,body.getY()+1.0,z,1,.02,.02,.02,0);
        }
    }
    private long lastHerald=Long.MIN_VALUE;
    /** News the village announces is struck once on the bell, so players far off look up; once in ten seconds at most. */
    void herald(){
        if(!AutonomousVillagesMod.CONFIG.lifeEvents)return;
        long t=v.level.getGameTime();
        if(t-lastHerald<200)return;
        lastHerald=t;
        BlockPos bell=v.meetingPoint();
        if(v.level.getBlockState(bell).is(net.minecraft.world.level.block.Blocks.BELL))music.add(new Note(t,bell,SoundEvents.BELL_BLOCK,1.1f));
    }

    // --- shared -----------------------------------------------------------------------------------
    static BlockPos middle(TownBuilding b){
        if(b.blocks.isEmpty())return BlockPos.of(b.origin);
        int minY=b.blocks.stream().mapToInt(p->p.position().getY()).min().orElse(0);
        double x=b.blocks.stream().mapToInt(p->p.position().getX()).average().orElse(0),z=b.blocks.stream().mapToInt(p->p.position().getZ()).average().orElse(0);
        return BlockPos.containing(x,minY+1,z);
    }
    /** Pitch of a note block note (0..24). */
    static float pitch(int note){return (float)Math.pow(2,(note-12)/12.0);}
    private void tune(BlockPos where,SoundEvent sound){
        long start=v.level.getGameTime()+20;
        for(int i=0;i<TUNE.length;i++)if(TUNE[i]>=0)music.add(new Note(start+i*4L,where,sound,pitch(TUNE[i])));
    }
    private void playMusic(long time){
        while(!music.isEmpty()&&music.peek().at()<=time){
            var n=music.poll();
            v.level.playSound(null,n.where(),n.sound(),SoundSource.RECORDS,n.sound()==SoundEvents.BELL_BLOCK?2f:1.5f,n.pitch());
            if(n.sound()!=SoundEvents.BELL_BLOCK)
                v.level.sendParticles(ParticleTypes.NOTE,n.where().getX()+.5,n.where().getY()+1.5,n.where().getZ()+.5,1,.8,.3,.8,1);
        }
    }
}
