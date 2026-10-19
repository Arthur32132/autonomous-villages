package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;

/**
 * The village's own big buildings, built after homes, the barn, the well and the watchtower: a forge,
 * a market square, a windmill, a chapel, a tavern, a stable and a pier. Walls, floors, roofs, furnaces and light are made from wood and stone
 * like any house; workstations, barrels, carpets and tables are decoration the guild brings on the
 * housewarming. Designs face north (the entrance on the z=0 side) and are turned toward the bell.
 */
final class Landmarks {
    private Landmarks(){}
    record Piece(BlockPos pos,BlockState state,boolean optional){}
    /** Cells relative to the north-west floor corner; layer 0 is the floor laid into the ground. */
    record Design(String kind,int width,int depth,List<Piece> pieces){
        int height(){return pieces.stream().mapToInt(p->p.pos().getY()).max().orElse(0);}
        Vec3i size(){return new Vec3i(width,height()+1,depth);}
        /** Built out over water from the bank at z=-1, not on dry ground. */
        boolean afloat(){return kind.equals("dock");}
    }
    /** The order the village builds them in, and the residents it needs for each. */
    static final List<String> ORDER=List.of("forge","market","mill","chapel","tavern","stable","dock");
    static int residents(String kind){return switch(kind){case "forge"->6;case "market"->7;case "mill"->8;case "chapel"->9;case "tavern"->10;case "stable"->11;default->12;};}
    /**
     * The next landmark the village is big enough for; one at a time, in order. A deferred one (no site
     * found lately, no water for a pier) lets the next take its turn.
     */
    static String due(Set<String> built,int residents,Set<String> deferred){
        for(String kind:ORDER)if(!built.contains(kind)&&!deferred.contains(kind))return residents>=residents(kind)?kind:null;
        return null;
    }
    static boolean kind(String kind){return ORDER.contains(kind);}
    static String label(String kind){return switch(kind){case "forge"->"большая кузница";case "market"->"рынок";case "mill"->"мельница";case "dock"->"пристань";
        case "chapel"->"часовня";case "stable"->"конюшня";default->"таверна";};}
    static boolean feminine(String kind){return !kind.equals("market");}
    /** What the sign at the building says above the village's name. */
    static String title(String kind){return switch(kind){case "forge"->"КУЗНИЦА";case "market"->"РЫНОК";case "mill"->"МЕЛЬНИЦА";case "dock"->"ПРИСТАНЬ";
        case "chapel"->"ЧАСОВНЯ";case "stable"->"КОНЮШНЯ";default->"ТАВЕРНА";};}
    static Design design(String kind){return switch(kind){case "forge"->forge();case "market"->market();case "mill"->mill();case "dock"->dock();
        case "chapel"->chapel();case "stable"->stable();default->tavern();};}
    /**
     * How far above the floor a builder reaches: {@code Village.stand} wants the eye (feet + 1.62) within
     * 3.55 of the block's centre, so from the floor nothing above layer 5 can be placed.
     */
    static final int REACH_TOP=5;

    private static final BlockState COBBLE=Blocks.COBBLESTONE.defaultBlockState(),PLANKS=Blocks.OAK_PLANKS.defaultBlockState(),
            LOG=Blocks.OAK_LOG.defaultBlockState(),FENCE=Blocks.OAK_FENCE.defaultBlockState(),GLASS=Blocks.GLASS_PANE.defaultBlockState();

    /** Cells by position; a later block in the same cell replaces the earlier one and moves after it. */
    private static final class Plan {
        final Map<BlockPos,Piece> cells=new LinkedHashMap<>();
        void put(int x,int y,int z,BlockState s){var p=new BlockPos(x,y,z);cells.remove(p);cells.put(p,new Piece(p,s,false));}
        void deco(int x,int y,int z,BlockState s){var p=new BlockPos(x,y,z);cells.remove(p);cells.put(p,new Piece(p,s,true));}
        void clear(int x,int y,int z){cells.remove(new BlockPos(x,y,z));}
        /**
         * A pitched roof along x over walls {@code top} high: stairs up both long sides (eaves one block
         * out), {@code rise} rows of them, the ridge in planks; the gable ends filled under it.
         */
        void roof(int w,int d,int top,int rise,BlockState gable){
            for(int x=-1;x<=w;x++)for(int z=-1;z<=d;z++){
                int f=Math.min(z+1,d-z);
                if(f>rise){put(x,top+rise,z,PLANKS);continue;}
                put(x,top+f,z,Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING,z<d/2?Direction.SOUTH:Direction.NORTH));
            }
            for(int z=0;z<d;z++){int roofY=top+Math.min(Math.min(z+1,d-z),rise);for(int y=top+1;y<roofY;y++){put(0,y,z,gable);put(w-1,y,z,gable);}}
        }
        Design design(String kind,int w,int d){return new Design(kind,w,d,List.copyOf(cells.values()));}
    }
    private static BlockState facing(Block b,Direction d){return b.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING,d);}
    private static BlockState torch(Direction d){return Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING,d);}
    private static BlockState beam(Direction.Axis axis){return LOG.setValue(RotatedPillarBlock.AXIS,axis);}

    /**
     * A stone smithy 7×6, open to the street behind a low wall: two furnaces under a chimney that
     * smokes, a blast furnace, smithing table, grindstone, anvil and a quenching cauldron.
     */
    static Design forge(){
        int w=7,d=6;var p=new Plan();
        for(int x=0;x<w;x++)for(int z=0;z<d;z++)p.put(x,0,z,COBBLE);
        for(int y=1;y<=3;y++)for(int x=0;x<w;x++)for(int z=0;z<d;z++){
            boolean edge=x==0||z==0||x==w-1||z==d-1;if(!edge)continue;
            boolean corner=(x==0||x==w-1)&&(z==0||z==d-1);
            if(corner){p.put(x,y,z,LOG);continue;}
            if(z==0){
                // The front: a log beam on the corner posts over a waist-high wall, a gap to walk in.
                if(y==3)p.put(x,y,z,beam(Direction.Axis.X));else if(y==1&&x!=3)p.put(x,y,z,Blocks.COBBLESTONE_WALL.defaultBlockState());
                continue;
            }
            p.put(x,y,z,y==2&&z==2&&(x==0||x==w-1)?GLASS:COBBLE);
        }
        p.roof(w,d,3,2,COBBLE);
        // The chimney rises through the back slope over the furnaces.
        for(int y=4;y<=REACH_TOP;y++)p.put(3,y,d-1,COBBLE);
        p.put(2,1,d-2,facing(Blocks.FURNACE,Direction.NORTH));p.put(3,1,d-2,facing(Blocks.FURNACE,Direction.NORTH));
        p.put(1,2,3,torch(Direction.EAST));p.put(w-2,2,3,torch(Direction.WEST));
        p.deco(3,REACH_TOP+1,d-1,Blocks.CAMPFIRE.defaultBlockState());
        p.deco(1,1,d-2,facing(Blocks.BLAST_FURNACE,Direction.NORTH));
        p.deco(4,1,d-2,Blocks.SMITHING_TABLE.defaultBlockState());
        p.deco(5,1,d-2,facing(Blocks.GRINDSTONE,Direction.NORTH).setValue(FaceAttachedHorizontalDirectionalBlock.FACE,AttachFace.FLOOR));
        p.deco(4,1,2,facing(Blocks.ANVIL,Direction.EAST));
        p.deco(1,1,1,Blocks.CAULDRON.defaultBlockState());
        p.deco(0,2,-1,Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING,Direction.NORTH));
        return p.design("forge",w,d);
    }

    /**
     * A cobbled square 9×9 with four stalls under coloured awnings, each with its trade's workstation
     * on the counter and a barrel behind; a lamp post, benches and a sign in the middle, produce at the edges.
     */
    static Design market(){
        int w=9,d=9;var p=new Plan();
        for(int x=0;x<w;x++)for(int z=0;z<d;z++)p.put(x,0,z,COBBLE);
        stall(p,0,0,1,Blocks.CARPET.red(),Blocks.SMOKER);
        stall(p,6,0,1,Blocks.CARPET.yellow(),Blocks.LOOM);
        stall(p,0,8,7,Blocks.CARPET.lightBlue(),Blocks.FLETCHING_TABLE);
        stall(p,6,8,7,Blocks.CARPET.lime(),Blocks.CARTOGRAPHY_TABLE);
        p.put(4,1,4,FENCE);p.put(4,2,4,FENCE);p.put(4,3,4,Blocks.TORCH.defaultBlockState());
        p.deco(2,1,4,facing(Blocks.OAK_STAIRS,Direction.WEST));p.deco(6,1,4,facing(Blocks.OAK_STAIRS,Direction.EAST));
        p.deco(4,1,3,Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION,8));
        p.deco(3,1,0,Blocks.HAY_BLOCK.defaultBlockState());p.deco(5,1,0,Blocks.PUMPKIN.defaultBlockState());
        p.deco(3,1,d-1,Blocks.MELON.defaultBlockState());p.deco(5,1,d-1,Blocks.HAY_BLOCK.defaultBlockState());
        return p.design("market",w,d);
    }
    /** A stall three wide: fence posts at its four corners, a slab roof under a carpet awning; {@code front} is the row facing the square. */
    private static void stall(Plan p,int x0,int back,int front,Block carpet,Block counter){
        Direction out=front>back?Direction.SOUTH:Direction.NORTH;
        for(int z:new int[]{back,front})for(int x=x0;x<x0+3;x++){
            if(x!=x0+1)for(int y=1;y<=2;y++)p.put(x,y,z,FENCE);
            p.put(x,3,z,Blocks.OAK_SLAB.defaultBlockState());
            p.deco(x,4,z,carpet.defaultBlockState());
        }
        p.deco(x0+1,1,front,counter.defaultBlockState().hasProperty(HorizontalDirectionalBlock.FACING)?facing(counter,out):counter.defaultBlockState());
        p.deco(x0+1,1,back,Blocks.BARREL.defaultBlockState());
    }

    /**
     * A timber tavern 9×7 under a broad roof with a flat ridge: a bar along the back with barrels behind it, a lantern,
     * a brewing stand and a cake on the counter, two tables with chairs, a sign by the door.
     */
    static Design tavern(){
        int w=9,d=7;var p=new Plan();
        for(int x=0;x<w;x++)for(int z=0;z<d;z++)p.put(x,0,z,x==0||z==0||x==w-1||z==d-1?COBBLE:PLANKS);
        for(int y=1;y<=3;y++)for(int x=0;x<w;x++)for(int z=0;z<d;z++){
            boolean edge=x==0||z==0||x==w-1||z==d-1;if(!edge)continue;
            boolean corner=(x==0||x==w-1)&&(z==0||z==d-1);
            if(x==4&&z==0&&y<=2)continue; // door
            boolean window=y==2&&((z==0||z==d-1)&&(x==2||x==6)||(x==0||x==w-1)&&z==3);
            p.put(x,y,z,corner?LOG:y==3?beam(z==0||z==d-1?Direction.Axis.X:Direction.Axis.Z):window?GLASS:PLANKS);
        }
        p.roof(w,d,3,2,PLANKS);
        p.put(4,1,0,facing(Blocks.OAK_DOOR,Direction.SOUTH));
        for(int x=2;x<=6;x++)p.put(x,1,4,PLANKS); // the bar
        p.put(1,2,1,torch(Direction.SOUTH));p.put(w-2,2,1,torch(Direction.SOUTH));
        p.put(3,2,d-2,torch(Direction.NORTH));p.put(5,2,d-2,torch(Direction.NORTH));
        p.deco(2,2,4,Blocks.LANTERN.defaultBlockState());p.deco(4,2,4,Blocks.BREWING_STAND.defaultBlockState());p.deco(6,2,4,Blocks.CAKE.defaultBlockState());
        for(int x:new int[]{1,w-2})for(int y=1;y<=2;y++)p.deco(x,y,d-2,Blocks.BARREL.defaultBlockState());
        for(int x:new int[]{2,6}){
            p.deco(x,1,2,FENCE);p.deco(x,2,2,Blocks.OAK_PRESSURE_PLATE.defaultBlockState());
            p.deco(x-1,1,2,facing(Blocks.OAK_STAIRS,Direction.WEST));p.deco(x+1,1,2,facing(Blocks.OAK_STAIRS,Direction.EAST));
        }
        p.deco(5,2,-1,Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING,Direction.NORTH));
        return p.design("tavern",w,d);
    }

    /**
     * A tower windmill 5×5: a stone ground floor under timber, the farmer's composter and hay inside, and
     * four sails of fence and white cloth turning on an axle out of the east wall.
     */
    static Design mill(){
        int w=5,d=5;var p=new Plan();
        for(int x=0;x<w;x++)for(int z=0;z<d;z++)p.put(x,0,z,COBBLE);
        for(int y=1;y<=4;y++)for(int x=0;x<w;x++)for(int z=0;z<d;z++){
            boolean edge=x==0||z==0||x==w-1||z==d-1;if(!edge)continue;
            boolean corner=(x==0||x==w-1)&&(z==0||z==d-1);
            if(x==2&&z==0&&y<=2)continue; // door
            boolean window=y==3&&(z==2&&(x==0||x==w-1)||x==2&&z==d-1);
            p.put(x,y,z,corner?LOG:window?GLASS:y<=2?COBBLE:PLANKS);
        }
        p.roof(w,d,4,1,PLANKS);
        p.put(2,1,0,facing(Blocks.OAK_DOOR,Direction.SOUTH));
        p.put(1,2,2,torch(Direction.EAST));p.put(w-2,2,2,torch(Direction.WEST));
        p.deco(1,1,3,Blocks.COMPOSTER.defaultBlockState());p.deco(1,1,1,Blocks.BARREL.defaultBlockState());
        p.deco(3,1,3,Blocks.HAY_BLOCK.defaultBlockState());p.deco(3,2,3,Blocks.HAY_BLOCK.defaultBlockState());
        // The sails stand clear of the wall; nobody has to reach them, the guild hangs them on the housewarming.
        int hub=4,mid=2,sails=w+1;
        p.deco(w,hub,mid,beam(Direction.Axis.X));p.deco(sails,hub,mid,beam(Direction.Axis.X));
        for(int dy:new int[]{-1,1})for(int dz:new int[]{-1,1})for(int k=1;k<=3;k++){
            p.deco(sails,hub+dy*k,mid+dz*k,FENCE);
            if(k>=2)p.deco(sails,hub+dy*k,mid+dz*(k-1),Blocks.WOOL.white().defaultBlockState());
        }
        p.deco(1,2,-1,Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING,Direction.NORTH));
        return p.design("mill",w,d);
    }

    /**
     * A pier three wide and six long over water, its deck level with the bank (z=-1): railings, torches
     * at the far end, the fisherman's barrels at the landing and a sign on the bank.
     */
    static Design dock(){
        int w=3,d=6;var p=new Plan();
        // From the bank outward: each plank is laid standing on the one before it.
        for(int z=0;z<d;z++)for(int x=0;x<w;x++)p.put(x,0,z,PLANKS);
        for(int z=1;z<d;z++){p.put(0,1,z,FENCE);p.put(w-1,1,z,FENCE);}
        p.put(1,1,d-1,FENCE);
        p.put(0,2,d-1,Blocks.TORCH.defaultBlockState());p.put(w-1,2,d-1,Blocks.TORCH.defaultBlockState());
        p.deco(0,1,0,Blocks.BARREL.defaultBlockState());p.deco(w-1,1,0,Blocks.BARREL.defaultBlockState());
        p.deco(0,1,-1,Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION,8));
        return p.design("dock",w,d);
    }

    /**
     * A stone chapel 7×5: pews either side of an aisle, the cleric's brewing stand on the altar between
     * candles, a little steeple on the ridge that the guild raises on the housewarming.
     */
    static Design chapel(){
        int w=7,d=5;var p=new Plan();
        for(int x=0;x<w;x++)for(int z=0;z<d;z++)p.put(x,0,z,COBBLE);
        for(int y=1;y<=3;y++)for(int x=0;x<w;x++)for(int z=0;z<d;z++){
            boolean edge=x==0||z==0||x==w-1||z==d-1;if(!edge)continue;
            if(x==3&&z==0&&y<=2)continue; // door
            boolean window=y==2&&((z==0||z==d-1)&&(x==1||x==5)||(x==0||x==w-1)&&z==2);
            p.put(x,y,z,window?GLASS:COBBLE);
        }
        p.roof(w,d,3,2,COBBLE);
        p.put(3,1,0,facing(Blocks.OAK_DOOR,Direction.SOUTH));
        p.put(2,2,1,torch(Direction.SOUTH));p.put(4,2,1,torch(Direction.SOUTH));
        for(int z=1;z<=2;z++)for(int x:new int[]{1,2,4,5})p.deco(x,1,z,facing(Blocks.OAK_STAIRS,Direction.NORTH));
        p.deco(3,1,d-2,Blocks.BREWING_STAND.defaultBlockState());
        for(int x:new int[]{2,4})p.deco(x,1,d-2,Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.CANDLES,3).setValue(CandleBlock.LIT,true));
        p.deco(3,REACH_TOP+1,2,Blocks.COBBLESTONE_WALL.defaultBlockState());p.deco(3,REACH_TOP+2,2,Blocks.COBBLESTONE_WALL.defaultBlockState());
        p.deco(3,REACH_TOP+3,2,Blocks.LANTERN.defaultBlockState());
        p.deco(4,2,-1,Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING,Direction.NORTH));
        return p.design("chapel",w,d);
    }

    /**
     * A timber stable 10×5 with three stalls behind fence gates, hay and a trough in each; on the
     * housewarming two horses move in (see {@code Projects.stableHorses}).
     */
    static Design stable(){
        int w=10,d=5;var p=new Plan();
        for(int x=0;x<w;x++)for(int z=0;z<d;z++)p.put(x,0,z,x==0||z==0||x==w-1||z==d-1?COBBLE:PLANKS);
        for(int y=1;y<=3;y++)for(int x=0;x<w;x++)for(int z=0;z<d;z++){
            boolean edge=x==0||z==0||x==w-1||z==d-1;if(!edge)continue;
            boolean corner=(x==0||x==w-1)&&(z==0||z==d-1);
            if(corner){p.put(x,y,z,LOG);continue;}
            if(z==0){
                // The front: a beam over the stall fronts, a gate into each stall.
                if(y==3)p.put(x,y,z,beam(Direction.Axis.X));
                else if(y==1)p.put(x,y,z,STALLS.contains(x)?facing(Blocks.OAK_FENCE_GATE,Direction.NORTH):FENCE);
                continue;
            }
            p.put(x,y,z,y==3?beam(z==d-1?Direction.Axis.X:Direction.Axis.Z):y==2&&z==2?GLASS:PLANKS);
        }
        for(int x:new int[]{3,6})for(int z=1;z<d-1;z++)p.put(x,1,z,FENCE);
        p.roof(w,d,3,2,PLANKS);
        for(int x:STALLS){
            p.put(x+1,2,d-2,torch(Direction.NORTH));
            p.deco(x+1,1,d-2,Blocks.HAY_BLOCK.defaultBlockState());p.deco(x,1,d-2,Blocks.CAULDRON.defaultBlockState());
        }
        p.deco(0,2,-1,Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING,Direction.NORTH));
        return p.design("stable",w,d);
    }
    /** The x of each stall's gate; the stall is that column and the next. */
    static final List<Integer> STALLS=List.of(1,4,7);
}
