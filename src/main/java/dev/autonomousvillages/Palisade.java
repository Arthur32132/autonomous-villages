package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.Direction;

/**
 * The geometry of the village palisade: a fence ring a few blocks outside the buildings, gates where
 * streets and footpaths cross it, a torch on every eighth post. The world decides each column (ground
 * height, water, fields, other buildings); this class only lays the ring out.
 */
final class Palisade {
    private Palisade(){}
    /** Blocks between the outermost building and the fence. */
    static final int MARGIN=4;
    /** A longer ring would eat the wood of several houses; such a village goes without. */
    static final int MAX_COLUMNS=360;
    /** One column of the ring; {@code along} is the axis the fence runs, a gate opens across it. */
    record Column(int x,int z,Direction.Axis along){
        /** The way through a gate in this column. */
        Direction gateFacing(){return along==Direction.Axis.X?Direction.NORTH:Direction.EAST;}
    }
    /** The edge of the rectangle, each cell once, walking round it from the north-west corner. */
    static List<Column> ring(int minX,int minZ,int maxX,int maxZ){
        var out=new ArrayList<Column>();
        if(maxX<=minX||maxZ<=minZ)return out;
        for(int x=minX;x<=maxX;x++)out.add(new Column(x,minZ,Direction.Axis.X));
        for(int z=minZ+1;z<=maxZ;z++)out.add(new Column(maxX,z,Direction.Axis.Z));
        for(int x=maxX-1;x>=minX;x--)out.add(new Column(x,maxZ,Direction.Axis.X));
        for(int z=maxZ-1;z>minZ;z--)out.add(new Column(minX,z,Direction.Axis.Z));
        return out;
    }
    /**
     * Fence courses on a column: a fence stops a zombie only where the ground beside it is not higher,
     * so a course more for each block the higher neighbour stands above; at most three.
     */
    static int courses(int ground,int highestBeside){return Math.max(1,Math.min(3,1+highestBeside-ground));}
    /** The ring kept inside the village bounds (minX, minZ, maxX, maxZ); null when no ring is left. */
    static int[] clamp(int minX,int minZ,int maxX,int maxZ,int boundMinX,int boundMinZ,int boundMaxX,int boundMaxZ){
        int x0=Math.max(minX,boundMinX),z0=Math.max(minZ,boundMinZ),x1=Math.min(maxX,boundMaxX),z1=Math.min(maxZ,boundMaxZ);
        return x1<=x0||z1<=z0?null:new int[]{x0,z0,x1,z1};
    }
    static boolean torch(int index){return index%8==0;}
}
