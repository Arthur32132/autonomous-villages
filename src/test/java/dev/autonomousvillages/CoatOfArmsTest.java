package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.DyeColor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CoatOfArmsTest {
    @BeforeAll static void bootstrapRegistries(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}

    @Test void theSameNameAlwaysGivesTheSameArms() {
        var a=CoatOfArms.of("Ольховка");
        assertEquals(a,CoatOfArms.of("Ольховка"));
        assertEquals(a,CoatOfArms.of("  ольховка "),"case and spaces do not change the arms");
        assertFalse(a.layers().isEmpty());
        assertTrue(a.layers().size()<=2);
    }

    @Test void differentNamesSpreadOverColoursAndCharges() {
        var bases=EnumSet.noneOf(DyeColor.class);var charges=new HashSet<CoatOfArms.Charge>();var designs=new HashSet<CoatOfArms>();
        for(int i=0;i<400;i++){
            var arms=CoatOfArms.of("Деревня "+i);
            bases.add(arms.base());designs.add(arms);
            for(var l:arms.layers()){
                charges.add(l.charge());
                assertNotEquals(arms.base(),l.colour(),"a charge in the field's colour would not show");
            }
            assertEquals(arms.layers().size(),arms.layers().stream().map(CoatOfArms.Layer::charge).distinct().count(),"no charge twice");
        }
        assertEquals(16,bases.size(),"every dye colour can be a field");
        assertEquals(CoatOfArms.CHARGES.size(),charges.size());
        assertTrue(designs.size()>380,"names rarely share arms: "+designs.size());
        // White comes up most: the village's own sheep give it.
        long white=java.util.stream.IntStream.range(0,400).filter(i->CoatOfArms.of("Деревня "+i).base()==DyeColor.WHITE).count();
        assertTrue(white>40&&white<130,"white fields: "+white);
    }

    @Test void everyChargeIsMadeOnTheLoomWithADyeAlone() throws Exception {
        var in=Bootstrap.class.getClassLoader().getResourceAsStream("data/minecraft/tags/banner_pattern/no_item_required.json");
        assertNotNull(in,"vanilla tag on the classpath");
        String tag=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        for(var c:CoatOfArms.CHARGES)assertTrue(tag.contains("\""+c.key().identifier()+"\""),c.key().identifier()+" needs a pattern item");
    }

    @Test void coloursHaveRussianNamesInEveryGender() {
        var seen=new HashSet<String>();
        for(DyeColor c:DyeColor.values())for(int g=0;g<3;g++){
            String name=CoatOfArms.colour(c,g);
            assertTrue(name.matches("[а-яё-]+"),name);
            assertTrue(seen.add(name),"duplicate "+name);
        }
        assertEquals("синее",CoatOfArms.colour(DyeColor.BLUE,CoatOfArms.NEUTER));
        assertEquals("жёлтый",CoatOfArms.colour(DyeColor.YELLOW,CoatOfArms.MASCULINE));
        assertEquals("красная",CoatOfArms.colour(DyeColor.RED,CoatOfArms.FEMININE));
        var arms=new CoatOfArms(DyeColor.BLUE,List.of(new CoatOfArms.Layer(CoatOfArms.CHARGES.get(1),DyeColor.YELLOW),new CoatOfArms.Layer(CoatOfArms.CHARGES.get(0),DyeColor.WHITE)));
        assertEquals("синее поле, жёлтый крест, белая кайма",arms.describe());
        assertTrue(CoatOfArms.of("Берёзовка").describe().contains(" поле"));
    }

    @Test void woolGoesIntoABannerOnlyBeyondWhatBedsNeed() {
        var wool=new EnumMap<DyeColor,Integer>(DyeColor.class);
        wool.put(DyeColor.WHITE,9);
        assertTrue(CoatOfArms.woolFree(wool,DyeColor.WHITE,false),"six for the banner, three left for a bed");
        assertFalse(CoatOfArms.woolFree(wool,DyeColor.WHITE,true),"never while a house waits for beds");
        wool.put(DyeColor.WHITE,8);
        assertFalse(CoatOfArms.woolFree(wool,DyeColor.WHITE,false),"two left would not make a bed");
        wool.put(DyeColor.BLACK,3);
        assertTrue(CoatOfArms.woolFree(wool,DyeColor.WHITE,false),"three black wool make the bed");
        assertFalse(CoatOfArms.woolFree(wool,DyeColor.RED,false),"no red wool at all");
        wool.put(DyeColor.WHITE,5);
        assertFalse(CoatOfArms.woolFree(wool,DyeColor.WHITE,false),"five wool make no banner");
    }

    @Test void theFieldComesFromItsWoolOrFromWhiteWoolAndSixDyes() {
        var wool=new EnumMap<DyeColor,Integer>(Map.of(DyeColor.WHITE,12));var dyes=new EnumMap<DyeColor,Integer>(DyeColor.class);
        assertEquals(CoatOfArms.Field.WOOL,CoatOfArms.field(DyeColor.WHITE,wool,dyes,false));
        assertNull(CoatOfArms.field(DyeColor.RED,wool,dyes,false),"no red wool and no red dye");
        dyes.put(DyeColor.RED,5);
        assertNull(CoatOfArms.field(DyeColor.RED,wool,dyes,false),"five dyes colour five wool only");
        dyes.put(DyeColor.RED,6);
        assertEquals(CoatOfArms.Field.DYED,CoatOfArms.field(DyeColor.RED,wool,dyes,false));
        assertNull(CoatOfArms.field(DyeColor.RED,wool,dyes,true),"beds first");
        wool.put(DyeColor.RED,6);wool.put(DyeColor.WHITE,3);
        assertEquals(CoatOfArms.Field.WOOL,CoatOfArms.field(DyeColor.RED,wool,dyes,false));
    }

    @Test void eachLayerTakesOneDyeAndAMissingOneEndsTheDesign() {
        var cross=CoatOfArms.CHARGES.get(1);var border=CoatOfArms.CHARGES.get(0);
        var twoRed=new CoatOfArms(DyeColor.WHITE,List.of(new CoatOfArms.Layer(cross,DyeColor.RED),new CoatOfArms.Layer(border,DyeColor.RED)));
        assertEquals(0,twoRed.affordable(Map.of()));
        assertEquals(1,twoRed.affordable(Map.of(DyeColor.RED,1)));
        assertEquals(2,twoRed.affordable(Map.of(DyeColor.RED,5)));
        assertEquals(Map.of(DyeColor.RED,2),twoRed.dyesFor(2));
        var mixed=new CoatOfArms(DyeColor.WHITE,List.of(new CoatOfArms.Layer(cross,DyeColor.BLUE),new CoatOfArms.Layer(border,DyeColor.YELLOW)));
        assertEquals(0,mixed.affordable(Map.of(DyeColor.YELLOW,3)),"the second layer never goes on without the first");
        assertEquals(1,mixed.affordable(Map.of(DyeColor.BLUE,3)));
        assertEquals(Map.of(DyeColor.BLUE,1),mixed.dyesFor(1));
        assertEquals(Map.of(DyeColor.BLUE,1,DyeColor.YELLOW,1),mixed.dyesFor(2));
        assertTrue(mixed.dyesFor(0).isEmpty());
    }

    /** The watchtower of Projects.tower: a 3×3 shaft, a 5×5 lookout four up with a railing, torches on the corners. */
    private static Set<BlockPos> tower(BlockPos base,Set<BlockPos> decoration){
        var out=new HashSet<BlockPos>();
        for(int x=1;x<=3;x++)for(int z=1;z<=3;z++)for(int y=0;y<=3;y++)if(!(x==2&&z==2&&y>0))out.add(base.offset(x,y,z));
        for(int y=1;y<=4;y++)out.add(base.offset(2,y,2));
        for(int x=0;x<5;x++)for(int z=0;z<5;z++){if(x!=2||z!=2)out.add(base.offset(x,4,z));if(x==0||z==0||x==4||z==4)out.add(base.offset(x,5,z));}
        for(int[] c:new int[][]{{0,0},{0,4},{4,0},{4,4}})decoration.add(base.offset(c[0],6,c[1]));
        return out;
    }

    @Test void theTowerFlagStandsOnTheLookoutAwayFromTheGuard() {
        BlockPos base=new BlockPos(100,64,-40);var decoration=new HashSet<BlockPos>();
        var required=tower(base,decoration);var all=new HashSet<>(required);all.addAll(decoration);
        BlockPos top=CoatOfArms.towerTop(required,all);
        assertEquals(base.offset(3,5,3),top);
        assertFalse(all.contains(top),"never in a cell of the plan");
        assertTrue(required.contains(top.below()),"stands on the lookout floor");
        assertNotEquals(base.offset(1,5,1),top,"the guard's post stays free");
        assertNull(CoatOfArms.towerTop(Set.of(),Set.of()));
    }

    @Test void theBarnFlagHangsOnTheFrontBesideTheDoor() {
        // A 7×5 barn with walls three high and its door in the north wall at x=3.
        BlockPos base=new BlockPos(0,70,0);var cells=new HashSet<BlockPos>();
        for(int x=0;x<7;x++)for(int z=0;z<5;z++){cells.add(base.offset(x,0,z));for(int y=1;y<=3;y++)if(x==0||z==0||x==6||z==4)cells.add(base.offset(x,y,z));}
        cells.remove(base.offset(3,2,0));BlockPos door=base.offset(3,1,0);
        var site=CoatOfArms.front("storehouse",cells,door);
        assertNotNull(site);
        assertEquals(Direction.NORTH,site.facing());
        assertEquals(base.offset(5,2,-1),site.pos());
        assertTrue(cells.contains(site.pos().relative(site.facing().getOpposite())),"hangs on the wall");
        assertFalse(cells.contains(site.pos()));
        // The same barn turned with its door to the east.
        var east=new HashSet<BlockPos>();for(var p:cells)east.add(new BlockPos(-(p.getZ()-base.getZ())+base.getX()+10,p.getY(),p.getX()-base.getX()+base.getZ()));
        BlockPos eastDoor=new BlockPos(base.getX()+10,door.getY(),base.getZ()+3);
        var turned=CoatOfArms.front("storehouse",east,eastDoor);
        assertNotNull(turned);
        assertEquals(Direction.EAST,turned.facing());
        assertFalse(east.contains(turned.pos()));
        assertTrue(east.contains(turned.pos().west()));
        assertNull(CoatOfArms.front("storehouse",cells,base.offset(3,1,2)),"a door inside the walls has no front");
    }

    @Test void aGateFlagStandsOnTheNextPostButNotOnATorch() {
        // A fence run along x with a gate at x=5; the post at x=6 carries a torch, the one at x=4 is two high.
        var fences=new HashSet<BlockPos>();var all=new HashSet<BlockPos>();
        for(int x=0;x<10;x++)if(x!=5){fences.add(new BlockPos(x,65,0));}
        fences.add(new BlockPos(4,66,0));
        all.addAll(fences);all.add(new BlockPos(5,65,0));all.add(new BlockPos(6,66,0));
        BlockPos gate=new BlockPos(5,65,0);
        var site=CoatOfArms.gate("gate:1",fences,all,gate,Direction.NORTH);
        assertNotNull(site);
        assertEquals(new BlockPos(4,67,0),site.pos());
        assertNull(site.facing());
        // Both posts under torches: no place for a flag.
        all.add(new BlockPos(4,67,0));
        assertNull(CoatOfArms.gate("gate:1",fences,all,gate,Direction.NORTH));
        // A run along z: the posts are north and south of the gate.
        var zFences=Set.of(new BlockPos(0,65,4),new BlockPos(0,64,6));
        var zSite=CoatOfArms.gate("gate:2",zFences,zFences,new BlockPos(0,65,5),Direction.EAST);
        assertNotNull(zSite);
        assertTrue(zSite.pos().equals(new BlockPos(0,66,4))||zSite.pos().equals(new BlockPos(0,65,6)));
    }

    @Test void theBellFlagTakesTheFirstFreeColumnTwoBlocksAway() {
        BlockPos bell=new BlockPos(10,64,10);
        assertEquals(new BlockPos(12,64,10),CoatOfArms.besideBell(bell,p->p));
        var taken=Set.of(new BlockPos(12,64,10),new BlockPos(8,64,10));
        assertEquals(new BlockPos(10,65,12),CoatOfArms.besideBell(bell,p->taken.contains(p)?null:p.above()));
        assertNull(CoatOfArms.besideBell(bell,p->null));
        var seen=new HashSet<BlockPos>();
        CoatOfArms.besideBell(bell,p->{seen.add(p);assertEquals(2,Math.max(Math.abs(p.getX()-10),Math.abs(p.getZ()-10)));return null;});
        assertEquals(16,seen.size());
    }

    @Test void sitesGoUpInOrderUpToSix() {
        var sites=new ArrayList<CoatOfArms.Site>();
        for(String k:List.of("tower","storehouse","bell","gate:1","gate:2","gate:3","gate:4"))sites.add(new CoatOfArms.Site(k,BlockPos.ZERO,null));
        sites.add(1,null);
        assertEquals("tower",CoatOfArms.next(sites,Set.of()).key());
        assertEquals("bell",CoatOfArms.next(sites,Set.of("tower","storehouse")).key());
        assertNull(CoatOfArms.next(sites,Set.of("tower","storehouse","bell","gate:1","gate:2","gate:3")),"six banners at most");
        assertNull(CoatOfArms.next(List.of(),Set.of()));
    }
}
