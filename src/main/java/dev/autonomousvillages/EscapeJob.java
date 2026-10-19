package dev.autonomousvillages;

import dev.navigationai.api.NavigationPolicy;

/**
 * A worker that keeps failing to walk anywhere from the same spot is trapped (a pit left by a tree,
 * a ledge, a flooded hole). It digs and steps its way back to the village centre; the library's
 * break permission still protects buildings, farmland and private chests.
 */
final class EscapeJob extends Job {
    static final NavigationPolicy DIG=new NavigationPolicy(true,true,true,true,32_000,1024,32,200,3);
    private Walk walk;
    EscapeJob(Worker w){super(w,"выбирается из ловушки");}
    @Override String kind(){return "escape";}
    @Override String activity(){return "mine";}
    @Override int timeLimit(){return 2400;}
    @Override boolean tick(){
        if(walk==null)walk=new Walk(v.feetPosition(v.meetingPoint()),6,DIG);
        return switch(walk.tick()){
            case MOVING->true;
            case FAILED->fail("не выбраться");
            default->false;
        };
    }
    @Override void stop(){if(walk!=null)walk.cancel();}
}
