package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Rotation;
import org.junit.jupiter.api.Test;

class BlueprintsTest {
    private static final Vec3i SIZE=new Vec3i(7,5,11);
    @Test void turningKeepsEveryCellInsideTheRotatedFootprint(){
        for(Rotation r:Rotation.values()){
            var footprint=Blueprints.footprint(SIZE,r);var seen=new HashSet<BlockPos>();
            for(int x=0;x<SIZE.getX();x++)for(int z=0;z<SIZE.getZ();z++){
                BlockPos p=Blueprints.turn(new BlockPos(x,2,z),SIZE,r);
                assertTrue(p.getX()>=0&&p.getX()<footprint.getX()&&p.getZ()>=0&&p.getZ()<footprint.getZ(),r+" "+p);
                assertEquals(2,p.getY());assertTrue(seen.add(p));
            }
        }
    }
    @Test void cellsMoveTheWayBlockStatesRotate(){
        // A door on the north wall (z=0) must end up on the wall the rotated facing points to.
        BlockPos north=new BlockPos(3,1,0);
        assertEquals(0,Blueprints.turn(north,SIZE,Rotation.NONE).getZ());
        assertEquals(SIZE.getZ()-1,Blueprints.turn(north,SIZE,Rotation.CLOCKWISE_90).getX());      // north -> east
        assertEquals(SIZE.getZ()-1,Blueprints.turn(north,SIZE,Rotation.CLOCKWISE_180).getZ());     // north -> south
        assertEquals(0,Blueprints.turn(north,SIZE,Rotation.COUNTERCLOCKWISE_90).getX());           // north -> west
        assertEquals(Direction.EAST,Rotation.CLOCKWISE_90.rotate(Direction.NORTH));
    }
    @Test void entranceTurnsTowardTheBell(){
        for(Direction from:Direction.Plane.HORIZONTAL)for(Direction to:Direction.Plane.HORIZONTAL)
            assertEquals(to,Blueprints.facing(from,to).rotate(from));
    }
    @Test void villageStylesFollowVillagerTypes(){
        assertEquals("snowy",Blueprints.style("snow"));assertEquals("desert",Blueprints.style("desert"));
        assertEquals("plains",Blueprints.style("jungle"));assertEquals("plains",Blueprints.style("swamp"));
        assertEquals("taiga",Blueprints.style("taiga"));assertEquals("savanna",Blueprints.style("savanna"));
    }
}
