package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagLoader;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Vanilla village houses from the Minecraft jar on the test classpath, audited for builder reach. */
class BlueprintsAuditTest {
    static final List<String> STYLES=List.of("plains","desert","savanna","snowy","taiga");
    /** Registries with the vanilla tags bound, as on a server: Blueprints.structural tests planks, logs, stairs and doors by tag. */
    @BeforeAll static void bootstrapRegistries() throws Exception{
        SharedConstants.tryDetectVersion();Bootstrap.bootStrap();
        try(var data=new MultiPackResourceManager(PackType.SERVER_DATA,List.of(ServerPacksSource.createVanillaPackSource()))){
            TagLoader.loadTagsForExistingRegistries(data,RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)).forEach(Registry.PendingTags::apply);
        }
        assertTrue(Blocks.OAK_PLANKS.defaultBlockState().is(BlockTags.PLANKS));
    }

    /** Every template of {@code village/<style>/houses/}, parsed the way the server's structure manager and Blueprints.load do. */
    private static List<Blueprints.Template> vanilla;
    static List<Blueprints.Template> vanilla() throws Exception{
        if(vanilla!=null)return vanilla;
        var url=Bootstrap.class.getClassLoader().getResource("data/minecraft/structure/village/plains/houses/plains_small_house_1.nbt");
        assertNotNull(url,"vanilla village templates on the test classpath");
        var jar=((JarURLConnection)url.openConnection()).getJarFile();
        var out=new ArrayList<Blueprints.Template>();
        for(var entry:Collections.list(jar.entries())){
            String path=entry.getName();
            if(!path.matches("data/minecraft/structure/village/[a-z]+/houses/[a-z0-9_]+\\.nbt"))continue;
            CompoundTag nbt;
            try(InputStream in=jar.getInputStream(entry)){nbt=NbtIo.readCompressed(in,NbtAccounter.unlimitedHeap());}
            nbt=DataFixTypes.STRUCTURE.updateToCurrentVersion(DataFixers.getDataFixer(),nbt,NbtUtils.getDataVersion(nbt,500));
            var template=new StructureTemplate();template.load(BuiltInRegistries.BLOCK,nbt);
            var id=Identifier.withDefaultNamespace(path.substring("data/minecraft/structure/".length(),path.length()-".nbt".length()));
            out.add(Blueprints.parse(BuiltInRegistries.BLOCK,id,template.getSize(),template.save(new CompoundTag())));
        }
        out.sort(Comparator.comparing(t->t.id().toString()));
        return vanilla=List.copyOf(out);
    }

    @Test void vanillaHousesAudit() throws Exception{
        var all=vanilla();
        assertTrue(all.size()>=140,"templates found: "+all.size());
        var scaffold=new TreeMap<String,List<String>>();long nanos=0;
        for(var t:all){
            long start=System.nanoTime();var audit=Blueprints.audit(t);nanos+=System.nanoTime()-start;
            String name=Blueprints.name(t.id());
            String kind=t.beds()>0?"house":Blueprints.workshop(name)?"workshop":"other";
            int top=audit.unreachable().stream().mapToInt(BlockPos::getY).max().orElse(-1);
            var lifted=new TreeMap<String,Integer>();
            var at=new HashMap<BlockPos,BlockState>();for(var e:t.blocks())at.put(e.pos(),e.state());
            for(var p:audit.unsupported()){
                var under=Blueprints.support(p,at.get(p)).map(at::get).map(s->BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath()).orElse("nothing");
                lifted.merge(BuiltInRegistries.BLOCK.getKey(at.get(p).getBlock()).getPath()+" on "+under,1,Integer::sum);
            }
            System.out.printf("AUDIT %-48s %-8s size=%-9s door=%d beds=%d work=%3d unreachable=%3d%s unsupported=%d %s%s%n",t.id().getPath(),kind,
                    t.size().getX()+"x"+t.size().getY()+"x"+t.size().getZ(),t.doorLevel(),t.beds(),audit.structural(),audit.unreachable().size(),
                    top<0?"":" (top y="+top+")",audit.unsupported().size(),lifted.isEmpty()?"":lifted.toString(),audit.needsScaffolding()?"  NEEDS SCAFFOLDING":"");
            if(audit.needsScaffolding())scaffold.computeIfAbsent(kind,k->new ArrayList<>()).add(name);
            assertEquals(t.work(),audit.structural());
        }
        scaffold.forEach((kind,names)->System.out.println("AUDIT needs scaffolding ("+kind+"): "+names.size()+" "+names));
        System.out.printf("AUDIT %d templates audited in %d ms%n",all.size(),nanos/1_000_000);
        // Every style keeps bed houses the builders can finish without scaffolding.
        for(String style:STYLES)assertTrue(all.stream().anyMatch(t->t.id().getPath().contains("/"+style+"/")&&t.beds()>0&&!Blueprints.audit(t).needsScaffolding()),style);
    }

    /** What Blueprints.civic and Blueprints.houses offer per style: every trade stays, scaffolding only where a trade has nothing else. */
    @Test void everyProfessionKeepsAWorkplace() throws Exception{
        var all=vanilla();java.util.function.Predicate<Blueprints.Template> scaffolding=t->Blueprints.audit(t).needsScaffolding();
        for(String style:STYLES){
            var designs=all.stream().filter(t->t.id().getPath().startsWith("village/"+style+"/")).toList();
            var civic=Blueprints.workplaces(designs.stream().filter(t->Blueprints.workshop(Blueprints.name(t.id()))).toList(),scaffolding);
            var houses=Blueprints.dwellings(designs,scaffolding);
            var trades=new TreeSet<String>();for(var t:civic)trades.add(Blueprints.trade(Blueprints.name(t.id())));
            System.out.println("AUDIT "+style+" civic: "+civic.stream().map(t->Blueprints.name(t.id())+(scaffolding.test(t)?"*":"")).toList());
            System.out.println("AUDIT "+style+" houses: "+houses.stream().map(t->Blueprints.name(t.id())+(scaffolding.test(t)?"*":"")).toList());
            for(String trade:List.of("armorer","fletcher","mason","shepherd","cartographer","library","tannery","tool_smith","weaponsmith","butcher_shop","temple","fisher"))
                assertTrue(trades.contains(trade),style+" "+trade+" in "+trades);
            assertFalse(houses.isEmpty(),style);
            assertTrue(houses.stream().noneMatch(scaffolding),style+" builds only houses its builders can finish");
        }
    }

    // --- synthetic plans ------------------------------------------------------------------------------
    private static Blueprints.Template plan(Vec3i size,Map<BlockPos,BlockState> blocks){
        var entries=new ArrayList<Blueprints.Entry>();blocks.forEach((p,s)->entries.add(new Blueprints.Entry(p,s)));
        var clear=new ArrayList<BlockPos>();
        for(int x=0;x<size.getX();x++)for(int y=0;y<size.getY();y++)for(int z=0;z<size.getZ();z++){var p=new BlockPos(x,y,z);if(!blocks.containsKey(p))clear.add(p);}
        return new Blueprints.Template(Identifier.withDefaultNamespace("test"),size,entries,clear,Direction.NORTH,0);
    }
    /** A solid 3×3 tower on a floor: a builder on the street reaches layer 5 (as Landmarks.REACH_TOP), not above. */
    @Test void aTallTowerNeedsScaffolding(){
        var blocks=new LinkedHashMap<BlockPos,BlockState>();
        for(int y=0;y<9;y++)for(int x=0;x<3;x++)for(int z=0;z<3;z++)blocks.put(new BlockPos(x,y,z),Blocks.COBBLESTONE.defaultBlockState());
        var audit=Blueprints.audit(plan(new Vec3i(3,9,3),blocks));
        assertTrue(audit.needsScaffolding());
        assertEquals(Landmarks.REACH_TOP+1,audit.unreachable().stream().mapToInt(BlockPos::getY).min().orElse(-1));
        assertEquals(27,audit.unreachable().size());
        assertEquals(81,audit.structural());
    }
    /** A 5×5 hut with a doorway: the middle of the roof is placed from inside. */
    @Test void aLowHutIsBuiltFromTheStreet(){
        var blocks=new LinkedHashMap<BlockPos,BlockState>();
        for(int x=0;x<5;x++)for(int z=0;z<5;z++){
            blocks.put(new BlockPos(x,0,z),Blocks.OAK_PLANKS.defaultBlockState());blocks.put(new BlockPos(x,4,z),Blocks.OAK_PLANKS.defaultBlockState());
            if(x==0||z==0||x==4||z==4)for(int y=1;y<4;y++)if(!(x==2&&z==0&&y<3))blocks.put(new BlockPos(x,y,z),Blocks.COBBLESTONE.defaultBlockState());
        }
        var audit=Blueprints.audit(plan(new Vec3i(5,5,5),blocks));
        assertFalse(audit.needsScaffolding(),audit.unreachable().toString());assertTrue(audit.unsupported().isEmpty());
    }
    /** A ladder inside the tower lets the builder climb to the top layers. */
    @Test void aLadderReachesTheTop(){
        var blocks=new LinkedHashMap<BlockPos,BlockState>();
        for(int y=0;y<9;y++)for(int x=0;x<3;x++)for(int z=0;z<3;z++){
            var p=new BlockPos(x,y,z);
            if(x==1&&z==1&&y>=1&&y<8)blocks.put(p,Blocks.LADDER.defaultBlockState().setValue(net.minecraft.world.level.block.LadderBlock.FACING,Direction.SOUTH));
            else if(x==1&&z==0&&y>=1&&y<=2)continue; // doorway
            else if(!(x==1&&z==1&&y==8))blocks.put(p,Blocks.COBBLESTONE.defaultBlockState());
        }
        var audit=Blueprints.audit(plan(new Vec3i(3,9,3),blocks));
        assertFalse(audit.needsScaffolding(),audit.unreachable().toString());
    }
    @Test void aTorchOnDecorationIsReported(){
        var blocks=new LinkedHashMap<BlockPos,BlockState>();
        for(int x=0;x<3;x++)for(int z=0;z<3;z++)blocks.put(new BlockPos(x,0,z),Blocks.OAK_PLANKS.defaultBlockState());
        blocks.put(new BlockPos(1,1,1),Blocks.HAY_BLOCK.defaultBlockState());
        blocks.put(new BlockPos(1,1,0),Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING,Direction.NORTH));
        blocks.put(new BlockPos(0,1,0),Blocks.TORCH.defaultBlockState());
        var audit=Blueprints.audit(plan(new Vec3i(3,2,3),blocks));
        assertEquals(List.of(new BlockPos(1,1,0)),audit.unsupported());
        assertEquals(Optional.of(new BlockPos(1,1,1)),Blueprints.support(new BlockPos(1,1,0),blocks.get(new BlockPos(1,1,0))));
        assertEquals(Optional.of(new BlockPos(0,0,0)),Blueprints.support(new BlockPos(0,1,0),Blocks.TORCH.defaultBlockState()));
        assertEquals(Optional.empty(),Blueprints.support(new BlockPos(0,1,0),Blocks.COBBLESTONE.defaultBlockState()));
    }
}
