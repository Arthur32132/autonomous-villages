package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.Identifier;
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

    /** Profession templates of the vanilla houses folders (names as in the 26.2 jar). */
    static final List<String> PROFESSIONS=List.of(
            "plains_armorer_house_1","plains_butcher_shop_1","plains_butcher_shop_2","plains_cartographer_1","plains_fisher_cottage_1","plains_fletcher_house_1",
            "plains_large_farm_1","plains_library_1","plains_library_2","plains_masons_house_1","plains_shepherds_house_1","plains_small_farm_1","plains_tannery_1",
            "plains_temple_3","plains_temple_4","plains_tool_smith_1","plains_weaponsmith_1",
            "desert_armorer_1","desert_butcher_shop_1","desert_cartographer_house_1","desert_farm_1","desert_fisher_1","desert_fletcher_house_1","desert_library_1",
            "desert_mason_1","desert_shepherd_house_1","desert_tannery_1","desert_temple_1","desert_tool_smith_1","desert_weaponsmith_1",
            "savanna_armorer_1","savanna_butchers_shop_1","savanna_cartographer_1","savanna_fisher_cottage_1","savanna_fletcher_house_1","savanna_library_1",
            "savanna_mason_1","savanna_shepherd_1","savanna_small_farm","savanna_tannery_1","savanna_temple_1","savanna_tool_smith_1","savanna_weaponsmith_2",
            "snowy_armorer_house_1","snowy_armorer_house_2","snowy_butchers_shop_1","snowy_cartographer_house_1","snowy_farm_1","snowy_fisher_cottage",
            "snowy_fletcher_house_1","snowy_library_1","snowy_masons_house_1","snowy_masons_house_2","snowy_shepherds_house_1","snowy_tannery_1","snowy_temple_1",
            "snowy_tool_smith_1","snowy_weapon_smith_1",
            "taiga_armorer_2","taiga_armorer_house_1","taiga_butcher_shop_1","taiga_cartographer_house_1","taiga_fisher_cottage_1","taiga_fletcher_house_1",
            "taiga_large_farm_1","taiga_library_1","taiga_masons_house_1","taiga_shepherds_house_1","taiga_tannery_1","taiga_temple_1","taiga_tool_smith_1","taiga_weaponsmith_1");
    @Test void everyProfessionHouseIsAWorkshop(){
        for(String name:PROFESSIONS)assertTrue(Blueprints.workshop(name),name);
        for(String name:List.of("plains_small_house_1","plains_medium_house_2","plains_big_house_1","desert_small_house_8","snowy_medium_house_3","taiga_small_house_5",
                "plains_animal_pen_1","plains_meeting_point_4","plains_accessory_1","plains_stable_2","snowy_animal_pen_2"))assertFalse(Blueprints.workshop(name),name);
    }
    @Test void professionHousesNameTheirTrade(){
        assertEquals("armorer",Blueprints.trade("plains_armorer_house_1"));assertEquals("armorer",Blueprints.trade("taiga_armorer_2"));
        assertEquals("mason",Blueprints.trade("plains_masons_house_1"));assertEquals("mason",Blueprints.trade("desert_mason_1"));
        assertEquals("shepherd",Blueprints.trade("snowy_shepherds_house_1"));assertEquals("shepherd",Blueprints.trade("savanna_shepherd_1"));
        assertEquals("fletcher",Blueprints.trade("desert_fletcher_house_1"));assertEquals("cartographer",Blueprints.trade("taiga_cartographer_house_1"));
        assertEquals("fisher",Blueprints.trade("snowy_fisher_cottage"));assertEquals("small_farm",Blueprints.trade("savanna_small_farm"));
        assertEquals("butcher_shop",Blueprints.trade("savanna_butchers_shop_1"));assertEquals("weaponsmith",Blueprints.trade("snowy_weapon_smith_1"));
        assertEquals("бронная мастерская",Projects.trade("snowy_armorer_house_2"));assertEquals("мастерская каменщика",Projects.trade("taiga_masons_house_1"));
        assertEquals("овчарня",Projects.trade("plains_shepherds_house_1"));assertEquals("мастерская лучника",Projects.trade("plains_fletcher_house_1"));
        assertEquals("картографическая",Projects.trade("desert_cartographer_house_1"));
        // Every profession template names a trade the label knows, except the fisher's.
        for(String name:PROFESSIONS)if(!name.contains("fisher"))assertNotEquals("мастерская",Projects.trade(name),name);
    }
    @Test void templateListingIsScannedOncePerSource(){
        var scans=new AtomicInteger();var listing=new Blueprints.Listing();
        var ids=List.of(Identifier.withDefaultNamespace("village/plains/houses/plains_temple_3"),Identifier.withDefaultNamespace("village/plains/houses/plains_armorer_house_1"),
                Identifier.withDefaultNamespace("village/taiga/houses/taiga_library_1"),Identifier.withDefaultNamespace("village/plains/zombie/houses/plains_small_house_1"),
                Identifier.withDefaultNamespace("village/plains/streets/corner_01"),Identifier.fromNamespaceAndPath("other","village/plains/houses/x_1"));
        Object server=new Object(),next=new Object();
        java.util.function.Supplier<Stream<Identifier>> all=()->{scans.incrementAndGet();return ids.stream();};
        var plains=listing.of(server,"plains",all);
        assertEquals(List.of(ids.get(1),ids.get(0)),plains);
        assertEquals(List.of(ids.get(2)),listing.of(server,"taiga",all));
        assertEquals(List.of(),listing.of(server,"desert",all));
        for(int i=0;i<100;i++)listing.of(server,"plains",all);
        assertEquals(1,scans.get());assertTrue(listing.fresh(server));
        listing.of(next,"plains",all);assertEquals(2,scans.get());assertFalse(listing.fresh(server));
        listing.forget();listing.of(next,"plains",all);assertEquals(3,scans.get());
    }
}
