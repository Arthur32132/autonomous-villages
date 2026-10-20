package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CampfireFeatureTest {
    /** Registries with the vanilla tags bound, as on a server: the campfire grid tells logs and coal by tag. */
    @BeforeAll static void bootstrapRegistries() throws Exception{
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        // Item stacks need the items' default components, which a server binds when it loads its data.
        if(!Items.BREAD.builtInRegistryHolder().areComponentsBound())
            BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createLookup()).forEach(DataComponentInitializers.PendingComponents::apply);
        // Another test class may have bound them already in this JVM; unbound tags throw on lookup.
        try{if(new ItemStack(Items.OAK_LOG).is(ItemTags.LOGS_THAT_BURN))return;}catch(RuntimeException unbound){}
        try(var data=new MultiPackResourceManager(PackType.SERVER_DATA,List.of(ServerPacksSource.createVanillaPackSource()))){
            TagLoader.loadTagsForExistingRegistries(data,RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)).forEach(Registry.PendingTags::apply);
        }
    }

    /** Flat grass with ground at y=63 and the first open cell at 64; the test marks paths, plans, doors, roofs and walls. */
    static final class Square implements CampfireFeature.Ground {
        final Set<BlockPos> paths=new HashSet<>(),plan=new HashSet<>(),roofed=new HashSet<>(),wood=new HashSet<>(),blocked=new HashSet<>(),forbidden=new HashSet<>();
        final Map<BlockPos,Direction> doors=new HashMap<>();
        @Override public int top(int x,int z){return 64;}
        @Override public boolean solid(BlockPos p){return p.getY()<=63||wood.contains(p);}
        @Override public boolean ground(BlockPos p){return p.getY()==63;}
        @Override public boolean path(BlockPos p){return paths.contains(p);}
        @Override public boolean planned(BlockPos p){return plan.contains(p);}
        @Override public Direction door(BlockPos p){return doors.get(p);}
        @Override public boolean sky(BlockPos p){return !roofed.contains(p);}
        @Override public boolean wooden(BlockPos p){return wood.contains(p);}
        @Override public boolean clear(BlockPos p){return p.getY()>=64&&!wood.contains(p);}
        @Override public boolean allowed(BlockPos p){return !forbidden.contains(p);}
        @Override public boolean stand(BlockPos p){return p.getY()==64&&!blocked.contains(p)&&!wood.contains(p);}
    }
    static final BlockPos HUB=new BlockPos(0,64,0);

    @Test void eveningWindowEndsBeforeTheNightRest(){
        assertFalse(CampfireFeature.evening(10_499));
        assertTrue(CampfireFeature.evening(10_500));
        assertTrue(CampfireFeature.evening(24_000*3+11_000),"every day, not only the first");
        assertFalse(CampfireFeature.evening(CampfireFeature.LAST_JOIN),"too late to set out");
        assertTrue(CampfireFeature.gathering(CampfireFeature.LAST_JOIN));
        assertFalse(CampfireFeature.gathering(CampfireFeature.EVENING_END),"hard end");
        assertFalse(CampfireFeature.gathering(12_800));
        assertFalse(CampfireFeature.evening(-24_000+5_000),"a clock set back is still a day hour");
        // The whole gathering lies in daylight hours; the night schedule takes over exactly at its end.
        for(long h=CampfireFeature.EVENING_START;h<CampfireFeature.EVENING_END;h++)assertFalse(Worker.nightRestWindow(h,false),"night rest at "+h);
        assertTrue(Worker.nightRestWindow(CampfireFeature.EVENING_END,false));
        // Never built at night or during the evening itself.
        assertTrue(CampfireFeature.buildHours(4_000));
        assertFalse(CampfireFeature.buildHours(500));
        assertFalse(CampfireFeature.buildHours(10_600));
        assertFalse(CampfireFeature.buildHours(18_000));
    }

    @Test void ringCellsAreDistinctNearestFirstAndSkipBlockedCells(){
        var fire=new BlockPos(10,64,10);
        var blocked=new HashSet<BlockPos>(List.of(new BlockPos(12,64,10),new BlockPos(10,64,8)));
        var ring=CampfireFeature.ring(fire,p->p.getY()==64&&!blocked.contains(p));
        assertFalse(ring.isEmpty());
        assertEquals(ring.size(),new HashSet<>(ring).size(),"one cell per column");
        int last=0;
        for(var p:ring){
            int d2=(p.getX()-10)*(p.getX()-10)+(p.getZ()-10)*(p.getZ()-10);
            assertTrue(d2>=4&&d2<=16,"2-4 blocks out: "+p);
            assertTrue(d2>=last,"sorted by distance");last=d2;
            assertFalse(blocked.contains(p));
        }
        assertEquals(new BlockPos(8,64,10).distSqr(fire),ring.getFirst().distSqr(fire),"the inner circle comes first");
        // A step up or down beside the fire is still a place to stand.
        var hill=CampfireFeature.ring(fire,p->p.getY()==(p.getX()>10?65:64));
        assertTrue(hill.stream().anyMatch(p->p.getY()==65));
        assertTrue(CampfireFeature.ring(fire,p->false).isEmpty());
        // Residents take cells one by one: never the same cell twice, never a blocked one.
        var taken=new HashSet<BlockPos>();
        for(int i=0;i<ring.size();i++){
            var cell=CampfireFeature.assign(ring,p->!taken.contains(p));
            assertNotNull(cell);assertTrue(taken.add(cell),"distinct cells");
        }
        assertNull(CampfireFeature.assign(ring,p->!taken.contains(p)),"a full circle has no room left");
        assertEquals(0,CampfireFeature.cap(1),"a lone resident keeps working");
        assertEquals(4,CampfireFeature.cap(5),"all but one");
        for(int day=0;day<50;day++){int t=CampfireFeature.linger(UUID.nameUUIDFromBytes(new byte[]{(byte)day}),day);assertTrue(t>=200&&t<=400,"linger "+t);}
    }

    @Test void siteIsOpenNaturalGroundThreeToSixBlocksFromTheBell(){
        var g=new Square();
        var site=CampfireFeature.site(g,HUB);
        assertNotNull(site);
        double d=Math.sqrt(site.distSqr(HUB));
        assertTrue(d>=3&&d<=6,"distance "+d);
        assertEquals(64,site.getY());
        assertTrue(CampfireFeature.fits(g,site));
        assertTrue(CampfireFeature.ring(site,g::stand).size()>=CampfireFeature.MIN_RING_CELLS);
    }

    @Test void siteRejectsPathsDoorwaysPlansRoofsAndWalls(){
        var g=new Square();var p=new BlockPos(4,64,0);
        assertTrue(CampfireFeature.fits(g,p));
        g.paths.add(p.below());assertFalse(CampfireFeature.fits(g,p),"on a path");g.paths.clear();
        g.doors.put(new BlockPos(6,64,2),Direction.WEST);assertFalse(CampfireFeature.fits(g,p),"in a doorway approach");
        g.doors.clear();g.doors.put(new BlockPos(8,64,0),Direction.WEST);assertTrue(CampfireFeature.fits(g,p),"a door four blocks off is out of the way");g.doors.clear();
        g.plan.add(p.below());assertFalse(CampfireFeature.fits(g,p),"on a plan's floor");g.plan.clear();
        g.plan.add(p);assertFalse(CampfireFeature.fits(g,p),"in a plan cell");g.plan.clear();
        g.roofed.add(p);assertFalse(CampfireFeature.fits(g,p),"under a roof");g.roofed.clear();
        g.wood.add(new BlockPos(5,65,1));assertFalse(CampfireFeature.fits(g,p),"touching a wooden wall");g.wood.clear();
        g.wood.add(new BlockPos(6,64,0));assertTrue(CampfireFeature.fits(g,p),"two blocks from a wall is fine");g.wood.clear();
        g.forbidden.add(p);assertFalse(CampfireFeature.fits(g,p),"outside the policy or given up as unreachable");g.forbidden.clear();
        // A plaza paved all round the bell within six blocks has no site at all.
        for(int x=-7;x<=7;x++)for(int z=-7;z<=7;z++)g.paths.add(new BlockPos(x,63,z));
        assertNull(CampfireFeature.site(g,HUB));
        // One patch of grass left: the fire goes there, never on the paving.
        g.paths.remove(new BlockPos(0,63,5));
        assertEquals(new BlockPos(0,64,5),CampfireFeature.site(g,HUB));
        // A patch with no room to stand round it is no site either.
        for(int x=-4;x<=4;x++)for(int z=1;z<=9;z++)g.blocked.add(new BlockPos(x,64,z));
        assertNull(CampfireFeature.site(g,HUB));
    }

    @Test void brokenFireIsRebuiltOnlyAfterAGameDay(){
        var s=new CampfireFeature.State();
        assertTrue(s.mayBuild(0));
        s.built(new BlockPos(3,64,0));s.welcomed=true;
        assertFalse(s.mayBuild(5_000),"one fire at a time");
        s.lost(10_000);
        assertNull(s.fire);
        assertFalse(s.mayBuild(10_000));
        assertFalse(s.mayBuild(10_000+CampfireFeature.REBUILD_DELAY-1));
        assertTrue(s.mayBuild(10_000+CampfireFeature.REBUILD_DELAY));
        s.built(new BlockPos(0,64,4));
        assertFalse(s.welcomed,"the new fire gets its own first evening");
        // The state survives the save as JSON.
        var gson=new com.google.gson.Gson();
        var back=gson.fromJson(gson.toJson(s),CampfireFeature.State.class);
        assertEquals(s.fire,back.fire);assertEquals(s.rebuildAt,back.rebuildAt);assertFalse(back.welcomed);
        assertTrue(gson.fromJson("{}",CampfireFeature.State.class).mayBuild(0));
    }

    @Test void materialsLeaveWoodForBuildersAndCoalForTorches(){
        assertEquals(new CampfireFeature.Kit(3,0,3,1),CampfireFeature.kit(5,0));
        assertEquals(new CampfireFeature.Kit(0,2,3,1),CampfireFeature.kit(1,6));
        assertEquals(new CampfireFeature.Kit(0,0,4,1),CampfireFeature.kit(0,1),"a log more for planks and sticks");
        assertTrue(CampfireFeature.affordable(0,3,0,3+CampfireFeature.LOG_RESERVE,2,0));
        assertFalse(CampfireFeature.affordable(0,3,0,2+CampfireFeature.LOG_RESERVE,2,0),"logs kept for the builders");
        assertFalse(CampfireFeature.affordable(0,0,0,3+CampfireFeature.LOG_RESERVE,2,0),"the log for sticks counts too");
        assertFalse(CampfireFeature.affordable(0,3,0,20,1,0),"the last coal is for torches");
        assertTrue(CampfireFeature.affordable(0,3,0,20,1,8),"unless the torches are made");
        assertFalse(CampfireFeature.affordable(0,3,0,20,0,64));
        assertTrue(CampfireFeature.affordable(1,0,0,0,0,0),"a campfire in the store needs nothing else");
        // The vanilla grid takes exactly three sticks, one coal and three logs from the bag.
        var bag=new ArrayList<>(List.of(new ItemStack(Items.STICK,5),new ItemStack(Items.BREAD,2),new ItemStack(Items.CHARCOAL,2),new ItemStack(Items.OAK_LOG,2),new ItemStack(Items.BIRCH_LOG,2)));
        var grid=CampfireJob.grid(bag);
        assertNotNull(grid);assertEquals(9,grid.size());
        assertTrue(grid.get(0).isEmpty());assertTrue(grid.get(1).is(Items.STICK));assertTrue(grid.get(4).is(Items.CHARCOAL));
        for(int i=6;i<9;i++)assertTrue(grid.get(i).is(ItemTags.LOGS),"logs in the bottom row");
        assertEquals(7,grid.stream().filter(s->!s.isEmpty()).mapToInt(ItemStack::getCount).sum());
        assertEquals(2,Supplies.count(bag,s->s.is(Items.STICK)));
        assertEquals(1,Supplies.count(bag,s->s.is(Items.CHARCOAL)));
        assertEquals(1,Supplies.count(bag,s->s.is(ItemTags.LOGS)));
        assertEquals(2,Supplies.count(bag,s->s.is(Items.BREAD)),"nothing else is touched");
        assertNull(CampfireJob.grid(new ArrayList<>(List.of(new ItemStack(Items.STICK,3),new ItemStack(Items.OAK_LOG,3)))),"no coal, no campfire");
    }
}
