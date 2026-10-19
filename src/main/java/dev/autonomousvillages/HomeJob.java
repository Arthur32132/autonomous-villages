package dev.autonomousvillages;

import net.minecraft.world.phys.Vec3;

/** Walks back to the worker's bed before night; vanilla takes over for the last steps and sleep. */
final class HomeJob extends Job {
    private Walk walk;
    /** Sent home to rest or to hide: once it is day and the alarm is over, there is no reason to go on. */
    private final boolean rest;
    HomeJob(Worker w){this(w,true);}
    HomeJob(Worker w,boolean rest){super(w,"возвращается домой");this.rest=rest;}
    @Override String kind(){return "home";}
    @Override int timeLimit(){return 3000;}
    @Override boolean tick(){
        // An alarm by day sent the unarmed home; when it ends they go back to work from where they are,
        // not first all the way home and out again.
        if(rest&&!w.hiding())return false;
        if(walk==null)walk=new Walk(Vec3.atBottomCenterOf(w.restHome()),3);
        return switch(walk.tick()){
            case MOVING->true;
            // The vanilla brain finds its own bed and opens its own doors: leave the rest of the night to it.
            case FAILED->{w.cooldown("home",6000);yield fail("не нашёл дорогу домой");}
            default->false;
        };
    }
    @Override void stop(){if(walk!=null)walk.cancel();}
}
