package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashSet;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

class PalisadeTest {
    @Test void theRingVisitsEveryEdgeCellOnce(){
        var ring=Palisade.ring(0,0,9,5);
        assertEquals(2*(10+6)-4,ring.size());
        var seen=new HashSet<String>();
        for(var c:ring){
            assertTrue(c.x()==0||c.x()==9||c.z()==0||c.z()==5,c.toString());
            assertTrue(seen.add(c.x()+","+c.z()),"twice: "+c);
        }
        // Consecutive cells touch: the ring is walked round, so torches spread evenly along it.
        for(int i=1;i<ring.size();i++){var a=ring.get(i-1);var b=ring.get(i);assertEquals(1,Math.abs(a.x()-b.x())+Math.abs(a.z()-b.z()),a+" "+b);}
        assertTrue(Palisade.ring(3,3,3,8).isEmpty());
    }
    @Test void gatesOpenAcrossTheFence(){
        var ring=Palisade.ring(0,0,4,4);
        for(var c:ring){
            var through=c.gateFacing();
            // A gate in a fence running along x is walked through north-south, and the other way round.
            assertEquals(c.along()==Direction.Axis.X?Direction.Axis.Z:Direction.Axis.X,through.getAxis());
        }
    }
    @Test void higherGroundBesideRaisesTheFence(){
        assertEquals(1,Palisade.courses(64,64));
        assertEquals(1,Palisade.courses(64,63));
        assertEquals(2,Palisade.courses(64,65));
        assertEquals(3,Palisade.courses(64,70));
        assertTrue(Palisade.torch(0)&&Palisade.torch(16)&&!Palisade.torch(3));
    }
}
