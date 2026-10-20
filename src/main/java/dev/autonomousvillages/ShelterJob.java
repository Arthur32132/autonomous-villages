package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.phys.Vec3;

/**
 * A resident without a bed spends the night indoors: on the floor of a village house, inside its walls
 * and under its roof, standing still until morning instead of wandering where zombies walk.
 */
final class ShelterJob extends Job {
    /** Cells tested per search at most: the floors of the nearest houses first, then around beds. */
    static final int SEARCH_BUDGET=640;
    /** How far along the floor a wall is looked for; a roofed hall wider than that counts as closed. */
    static final int RAY=6;
    final BlockPos cell;
    private final int night;
    private Walk walk;
    private boolean holding;
    private long renewed;
    private ShelterJob(Worker w,BlockPos cell){super(w,"ночует под крышей");this.cell=cell;night=PatrolJob.nightLeft(v.level.getOverworldClockTime());renewed=now();}
    /** Null when no free indoor cell is known near the resident. */
    static ShelterJob find(Worker w){
        var v=w.v;BlockPos from=w.body().blockPosition();int[] budget={SEARCH_BUDGET};
        var doors=v.town.doors().stream().filter(d->d.distSqr(from)<=72*72).toList();
        var houses=v.state.buildings.stream().filter(b->b.completed&&(b.kind.equals("house")||b.kind.equals("native_house"))&&BlockPos.of(b.origin).distSqr(from)<=64*64)
                .sorted(Comparator.comparingDouble(b->BlockPos.of(b.origin).distSqr(from))).toList();
        for(var b:houses){
            if(budget[0]<=0)break;
            BlockPos best=pick(w,floor(b),from,doors,budget);
            if(best!=null)return new ShelterJob(w,best);
        }
        // Beds outside any recorded house: the floor around them, nearest first.
        var beds=v.index.beds.stream().filter(b->b.distSqr(from)<=64*64).sorted(Comparator.comparingDouble(b->b.distSqr(from))).toList();
        for(BlockPos bed:beds){
            if(budget[0]<=0)break;
            BlockPos best=pick(w,BlockPos.betweenClosed(bed.offset(-3,-1,-3),bed.offset(3,1,3)),from,doors,budget);
            if(best!=null)return new ShelterJob(w,best);
        }
        return null;
    }
    /** The best free indoor cell among the candidates, spending the search budget; null when none. */
    private static BlockPos pick(Worker w,Iterable<BlockPos> candidates,BlockPos from,List<BlockPos> doors,int[] budget){
        var v=w.v;var cells=cells(v);BlockPos best=null;double score=Double.MAX_VALUE;
        for(BlockPos q:candidates){
            if(budget[0]--<=0)break;
            BlockPos p=q.immutable();
            if(!indoor(v,p)||!v.ready(p,w.id))continue;
            double s=score(p.distSqr(from),depth(cells,p,doors));
            if(s<score){score=s;best=p;}
        }
        return best;
    }
    /** Floor cells of a house plan: the cell above each planned block that is not planned itself, lowest first. */
    static List<BlockPos> floor(TownBuilding b){
        var planned=new HashSet<Long>();for(var p:b.blocks)planned.add(p.pos);
        var out=new ArrayList<BlockPos>();
        for(var p:b.blocks){BlockPos up=p.position().above();if(planned.add(up.asLong()))out.add(up);}
        out.sort(Comparator.comparingInt(BlockPos::getY));
        return out;
    }
    static boolean indoor(Village v,BlockPos p){
        return v.town.protects(p.below())&&v.level.hasChunkAt(p)&&!v.level.canSeeSky(p)&&!v.level.canSeeSky(p.above())
                &&!(v.level.getBlockState(p).getBlock() instanceof BedBlock)&&!(v.level.getBlockState(p.below()).getBlock() instanceof BedBlock)
                &&v.safeFeet(p)&&enclosed(cells(v),p);
    }

    /** What the indoors test asks of the world: the level in game, a map of blocks in tests. */
    interface Cells { boolean solid(BlockPos p); boolean door(BlockPos p); boolean sky(BlockPos p); }
    /** The level's blocks; a cell in an unloaded chunk is open sky, never a wall, and is not loaded for the question. */
    static Cells cells(Village v){
        var level=v.level;
        return new Cells(){
            @Override public boolean solid(BlockPos p){return level.hasChunkAt(p)&&level.getBlockState(p).blocksMotion();}
            @Override public boolean door(BlockPos p){if(!level.hasChunkAt(p))return false;var b=level.getBlockState(p).getBlock();return b instanceof DoorBlock||b instanceof FenceGateBlock;}
            @Override public boolean sky(BlockPos p){return !level.hasChunkAt(p)||level.canSeeSky(p);}
        };
    }
    /**
     * Inside walls and under a roof: a solid block within four above, no door in or beside the cell, and every
     * way along the floor a wall or a door comes before open sky. A doorway or a covered porch is not indoors.
     */
    static boolean enclosed(Cells c,BlockPos p){
        if(c.sky(p)||c.door(p)||c.door(p.above()))return false;
        boolean roof=false;for(int k=1;k<=4&&!roof;k++)roof=c.solid(p.above(k));
        if(!roof)return false;
        for(Direction d:Direction.Plane.HORIZONTAL){BlockPos n=p.relative(d);if(c.door(n)||c.door(n.above()))return false;}
        for(Direction d:Direction.Plane.HORIZONTAL)if(wall(c,p,d)<0)return false;
        return true;
    }
    /** Steps along the floor to a wall (feet and head blocked) or a door; RAY when none is that near, -1 when open sky comes first. */
    static int wall(Cells c,BlockPos p,Direction d){
        for(int k=1;k<=RAY;k++){
            BlockPos q=p.relative(d,k);
            if(c.door(q)||c.solid(q)&&c.solid(q.above()))return k;
            if(c.sky(q))return -1;
        }
        return RAY;
    }
    /** How deep inside a cell is: a step off the walls, and how far the nearest door is; larger is safer. */
    static int depth(Cells c,BlockPos p,Collection<BlockPos> doors){
        int walls=RAY;for(Direction d:Direction.Plane.HORIZONTAL)walls=Math.min(walls,Math.abs(wall(c,p,d)));
        int door=RAY;
        for(BlockPos d:doors)if(Math.abs(d.getY()-p.getY())<=2)door=Math.min(door,Math.max(Math.abs(d.getX()-p.getX()),Math.abs(d.getZ()-p.getZ())));
        return Math.min(walls-1,2)+Math.min(door-1,4);
    }
    /** Lower is better: a few steps farther is worth it for a cell away from the door and the walls. */
    static double score(double distSqr,int depth){return Math.sqrt(distSqr)-3*depth;}

    @Override String kind(){return "shelter";}
    @Override BlockPos target(){return cell;}
    /** Until the morning: a shorter limit failed residents that started at dusk. */
    @Override int timeLimit(){return night+600;}
    @Override boolean tick(){
        if(!w.hiding())return false;
        // The cell stays this resident's all night: a lease left to lapse after a minute gave it to a second sleeper.
        if(now()-renewed>=600){v.reservations.acquire(cell.asLong(),w.id,now());renewed=now();}
        Vec3 point=v.feetPosition(cell);
        // Under the same roof a step or two away is shelter enough: the exact cell is often one a route
        // cannot end in (a slab, a bed edge), and failing there sent residents back out (rc13, seed 42).
        double away=body().position().distanceToSqr(point);
        if(away<=1.2||away<=6.25&&indoor(v,body().blockPosition())&&!Lighting.byDoor(v,body().blockPosition())){
            // Manual zero input keeps the native brain from walking the body back outside.
            if(!holding){bot().actions().setMovement(0,0,false,false,false);holding=true;status="ночует под крышей";}
            return true;
        }
        if(holding){bot().actions().stop();holding=false;}
        if(walk==null)walk=new Walk(point,.6);
        return switch(walk.tick()){
            case FAILED->{w.cooldown("shelter",1200);yield fail("не дойти до укрытия");}
            case ARRIVED->{walk=null;yield true;}
            default->{status="идёт в укрытие";yield true;}
        };
    }
    @Override void stop(){if(walk!=null)walk.cancel();if(holding)bot().actions().stop();holding=false;}
}
