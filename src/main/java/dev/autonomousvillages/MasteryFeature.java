package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * Мастерство: every {@link Mastery#SAMPLE} ticks each working resident's job counts toward its craft; long
 * work at one craft earns a title (ученик, подмастерье, мастер), told once in the chronicle with a small
 * celebration at the resident. Presentation and chronicle only: no effect on jobs, roles or the economy.
 */
final class MasteryFeature implements Features.Feature {
    static final String KEY="mastery";
    /** The live book of each loaded village, read from and written back to its feature slot. */
    private static final Map<Village,Mastery.Book> BOOKS=new WeakHashMap<>();
    @Override public String key(){return KEY;}
    @Override public boolean enabled(){return AutonomousVillagesMod.CONFIG.mastery;}

    @Override public void tick(Village v,long time){
        if(Math.floorMod(time,Mastery.SAMPLE)!=71)return;
        var jobs=new HashMap<UUID,String>();
        for(Worker w:v.workers.values()){String kind=w.jobKind();if(Mastery.craft(kind)!=null&&w.active())jobs.put(w.id,kind);}
        var book=book(v);
        var promotions=book.sample(jobs,v.state.residents,Mastery.SAMPLE);
        if(book.dirty){book.dirty=false;Features.store(v,KEY,book);}
        for(var p:promotions)promote(v,p);
    }

    /** The resident's best title ("мастер-лесоруб"), or null without one or with the feature off; for the name label. */
    static String title(Village v,UUID id){
        if(v==null||id==null||!AutonomousVillagesMod.CONFIG.mastery)return null;
        try{
            var best=book(v).best(id);
            return best==null?null:Mastery.title(best.craft(),best.rank(),Mastery.feminine(v.presentation.personName(id)));
        }catch(RuntimeException broken){return null;}
    }
    /** The label's role with the resident's title: "мастер-лесоруб", or "строитель, ученик-фермер"; just the role without one. */
    static String withTitle(Village v,UUID id,String role){return Mastery.withTitle(role,title(v,id));}

    private static Mastery.Book book(Village v){
        return BOOKS.computeIfAbsent(v,k->{var b=Features.load(k,KEY,Mastery.Book.class,Mastery.Book::new);b.sanitize();return b;});
    }

    private static void promote(Village v,Mastery.Promotion p){
        String line=Mastery.promotion(v.presentation.personName(p.id()),p.craft(),p.rank());
        // An apprentice is a quiet line in the chronicle; a journeyman or a master is news for the whole village.
        if(p.rank()>=Mastery.JOURNEYMAN)v.chronicle.announce(line);else v.chronicle.event(line);
        celebrate(v,p);
    }

    /** Happy sparks and a cheer at the resident; a master also gets a golden burst and a fanfare. */
    private static void celebrate(Village v,Mastery.Promotion p){
        if(!AutonomousVillagesMod.CONFIG.celebrations)return;
        Worker w=v.workers.get(p.id());
        if(w==null||!w.active())return;
        var e=w.body();
        v.level.sendParticles(ParticleTypes.HAPPY_VILLAGER,e.getX(),e.getY()+1.2,e.getZ(),6+4*p.rank(),.4,.5,.4,0);
        v.level.playSound(null,e.blockPosition(),SoundEvents.VILLAGER_CELEBRATE,SoundSource.NEUTRAL,1f,1f);
        if(p.rank()<Mastery.MASTER)return;
        v.level.sendParticles(ParticleTypes.TOTEM_OF_UNDYING,e.getX(),e.getY()+1,e.getZ(),30,.3,.6,.3,.3);
        v.level.playSound(null,e.blockPosition(),SoundEvents.PLAYER_LEVELUP,SoundSource.NEUTRAL,.8f,1.2f);
    }
}
