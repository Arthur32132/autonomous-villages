package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BiConsumer;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The v4 save contract: a save written by master still loads with every value, every field survives a round trip,
 * and broken metadata never stops the village from saving; only a broken identity is still refused.
 * <p>
 * Regenerating {@code saves/v4-master-5781aae.json}: {@code git worktree add ../wt/master-fixture 5781aae} (the build
 * needs {@code ../MinecraftmodNavigationAi} beside it); there add a throwaway test in this package that bootstraps the
 * registries, builds the village {@link #masterSaveLoadsUnchanged} checks (every master field set: a house with plans,
 * an optional plan, cuts, design and path, a wall, a policy zone and private chest, names, positions, roles, chronicle,
 * news, a grave, a cemetery, a nameplate, donations, goods days, a fair, a request, one pending release), calls
 * compactMetadata and validate, and writes {@code VillageRuntime.GSON.toJson(save)}; run it with
 * {@code ./gradlew :test --tests '*ThatTest*'}, copy the file here and {@code git worktree remove --force} the worktree.
 */
class SaveFormatTest {
    @BeforeAll static void bootstrapRegistries(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}

    /** Every field the save writes; a new one must be added here and filled in {@link #populated()}. */
    static final List<String> POPULATED=List.of("name","owner","x","y","z","radius","active","residents","storage","furnaces",
            "plantedTrees","plantedCrops","fellingTrees","treeWork","reforestation","mineEntrance","mineDirection","mineLength",
            "mineStatus","mineFailures","mineRetryAt","completedShelters","bounds","buildings","capturedPieces","residentNames",
            "residentPositions","residentRoles","policy","lights","chronicle","lastBirth","productDays","lastGolem","newsDay",
            "newsTotals","newsResidents","graves","cemetery","nameplates","donations","donorNames","honoured","goodsDays","fairDay",
            "request","nextRequest","noticeBoard","features");
    /** Fields master 5781aae did not write: a master save must load with each at its default. */
    static final Set<String> SINCE_MASTER=Set.of("mineStatus","mineFailures","mineRetryAt","noticeBoard","features");

    static UUID id(int n){return UUID.fromString("00000000-0000-4000-8000-0000000000"+String.format("%02d",n));}
    static long p(int x,int y,int z){return new BlockPos(x,y,z).asLong();}
    static String fixture(){
        try(var in=SaveFormatTest.class.getResourceAsStream("/saves/v4-master-5781aae.json")){
            assertNotNull(in,"fixture missing");return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }catch(java.io.IOException e){throw new AssertionError(e);}
    }
    static VillageRuntime.Save read(String json){return VillageRuntime.GSON.fromJson(json,VillageRuntime.Save.class);}
    static String write(VillageState... villages){var save=new VillageRuntime.Save();save.villages=List.of(villages);return VillageRuntime.GSON.toJson(save);}
    static VillageState roundTrip(VillageState s){return read(write(s)).villages.getFirst();}
    static JsonObject tree(VillageState s){return VillageRuntime.GSON.toJsonTree(s).getAsJsonObject();}
    /** Every non-static, non-transient field: exactly what Gson writes into the save. */
    static Set<String> persistedFields(){
        var names=new TreeSet<String>();
        for(var f:VillageState.class.getDeclaredFields())if(!Modifier.isStatic(f.getModifiers())&&!Modifier.isTransient(f.getModifiers()))names.add(f.getName());
        return names;
    }
    /** Every value of {@code expected} is in {@code actual} unchanged; keys {@code actual} adds are allowed. */
    static void assertKeeps(JsonElement expected,JsonElement actual,String path){
        if(expected.isJsonObject()){
            assertTrue(actual!=null&&actual.isJsonObject(),path+" is no longer an object: "+actual);
            for(var e:expected.getAsJsonObject().entrySet())assertKeeps(e.getValue(),actual.getAsJsonObject().get(e.getKey()),path+"."+e.getKey());
        }else if(expected.isJsonArray()){
            assertTrue(actual!=null&&actual.isJsonArray(),path+" is no longer an array: "+actual);
            var a=expected.getAsJsonArray();var b=actual.getAsJsonArray();
            assertEquals(a.size(),b.size(),path+" size");
            for(int i=0;i<a.size();i++)assertKeeps(a.get(i),b.get(i),path+"["+i+"]");
        }else assertEquals(expected.toString(),String.valueOf(actual),path);
    }

    @Test void masterSaveLoadsUnchanged(){
        String json=fixture();
        assertTrue(json.getBytes(StandardCharsets.UTF_8).length<20_000);
        var fixture=JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("villages").get(0).getAsJsonObject();
        var save=read(json);
        assertEquals(4,save.version);assertEquals(VillageRuntime.Save.VERSION,save.version);assertEquals(1,save.villages.size());
        var s=save.villages.getFirst();
        s.compactMetadata();s.validate();
        assertKeeps(fixture,JsonParser.parseString(VillageRuntime.GSON.toJson(s)),"village");
        assertEquals("Берёзовка",s.name);assertEquals(id(99).toString(),s.owner);assertEquals(new BlockPos(10,70,5),s.center());
        assertEquals(96,s.radius);assertFalse(s.active);assertEquals(List.of(id(1),id(2),id(3)),List.copyOf(s.residents));
        assertEquals(List.of(-60,-40,80,50),List.of(s.bounds.minX,s.bounds.minZ,s.bounds.maxX,s.bounds.maxZ));
        assertEquals(List.of(p(12,70,6),p(14,70,6)),List.copyOf(s.storage));assertEquals(Set.of(p(16,70,8)),s.furnaces);
        assertEquals(List.of(p(-70,69,10),p(-70,70,10)),s.treeWork.get(p(-70,68,10)));
        assertEquals(p(70,70,5),s.mineEntrance);assertEquals(2,s.mineDirection);assertEquals(17,s.mineLength);assertEquals(3,s.completedShelters);
        assertEquals(List.of("house","wall"),s.buildings.stream().map(b->b.kind).toList());
        var house=s.buildings.getFirst();
        assertTrue(house.completed);assertEquals("plains_small_house_1",house.design);assertEquals(3,house.blocks.size());
        assertEquals(Blocks.OAK_DOOR,house.blocks.get(1).block().getBlock());assertTrue(house.blocks.get(2).optional);
        assertEquals(List.of(p(20,71,21)),house.cuts);assertEquals(2,house.path.size());
        assertEquals(p(-58,70,-38),s.buildings.get(1).origin);assertEquals(Blocks.OAK_FENCE,s.buildings.get(1).blocks.getFirst().block().getBlock());
        assertEquals("{\"text\":\"Аня\"}",s.residentNames.get(id(1)).json);assertTrue(s.residentNames.get(id(2)).untouched);
        assertEquals(p(11,70,5),s.residentPositions.get(id(1)));
        assertEquals(ResidentRoles.Role.MINER,s.residentRoles.get(id(1)).role);assertEquals("ближе всех к шахте",s.residentRoles.get(id(1)).reason);
        assertEquals("wood",s.policy.zones.getFirst().activity);assertEquals(Set.of(p(13,70,7)),s.policy.privateChests);
        assertEquals(List.of("north","east"),List.copyOf(s.policy.expansion));
        assertEquals(2L,s.chronicle.totals.get("births"));assertEquals(List.of("день 1: основано","день 3: построен дом"),s.chronicle.events);
        assertEquals(4800,s.lastBirth);assertEquals(3L,s.productDays.get(id(1)));assertEquals(12000,s.lastGolem);
        assertEquals(4,s.newsDay);assertEquals(Map.of("trees",10L),s.newsTotals);assertEquals(3,s.newsResidents);
        assertEquals("Борис",s.graves.getFirst().name);assertEquals("шахтёр",s.graves.getFirst().role);assertEquals(p(-40,70,-30),s.cemetery);
        assertEquals(p(20,70,20),s.nameplates.get(p(22,70,19)));
        assertEquals(12L,s.donations.get(id(50)));assertEquals("Steve",s.donorNames.get(id(50)));assertEquals(Set.of(id(50)),s.honoured);
        assertEquals(Map.of("forge",5L,"market",6L),s.goodsDays);assertEquals(7,s.fairDay);
        assertEquals(List.of("door",4,1,30000L),List.of(s.request.key,s.request.amount,s.request.delivered,s.request.posted));assertEquals(48000,s.nextRequest);
        assertEquals("{\"text\":\"Вера\"}",save.pendingReleases.get(id(7)).json);
        // What 0.3 added starts from its default.
        assertEquals(VillageState.MINE_DIGGING,s.mineStatus);assertEquals(0,s.mineFailures);assertEquals(0,s.mineRetryAt);
        assertNull(s.noticeBoard);assertTrue(s.features.isEmpty());
        var unwritten=new TreeSet<>(persistedFields());unwritten.removeAll(fixture.keySet());
        assertEquals(new TreeSet<>(SINCE_MASTER),unwritten,"a field master wrote is gone, or a new field is missing from SINCE_MASTER and its default check");
    }

    /** A village with every persisted field away from its default. */
    static VillageState populated(){
        var s=new VillageState("Полный",id(98),new BlockPos(3,64,-2),64);
        s.active=false;s.bounds=new VillageState.Bounds(-40,-40,40,40);
        s.residents.addAll(List.of(id(1),id(2),id(3)));
        s.storage.addAll(List.of(p(2,64,2),p(3,64,2)));s.furnaces.add(p(4,64,2));
        s.plantedTrees.add(p(60,64,10));s.plantedCrops.add(p(10,64,10));s.fellingTrees.add(p(-50,64,0));
        s.treeWork.put(p(-50,64,0),new ArrayList<>(List.of(p(-50,65,0),p(-50,66,0))));s.reforestation.add(p(-52,64,1));
        s.mineEntrance=p(30,64,0);s.mineDirection=1;s.mineLength=9;s.mineStatus=VillageState.MINE_BLOCKED;s.mineFailures=3;s.mineRetryAt=96_000;
        s.completedShelters=2;
        var house=new TownBuilding("house",new BlockPos(10,64,-10));house.completed=true;house.design="plains_small_house_1";
        house.add(new BlockPos(10,64,-10),Blocks.OAK_PLANKS.defaultBlockState());house.decorate(new BlockPos(11,65,-10),Blocks.FLOWER_POT.defaultBlockState());
        house.cuts.add(p(10,65,-9));house.path.add(p(11,63,-8));
        var wall=new TownBuilding("wall",new BlockPos(-36,64,-36));
        wall.add(new BlockPos(-36,64,-36),Blocks.OAK_FENCE.defaultBlockState());wall.add(new BlockPos(-35,64,-36),Blocks.OAK_FENCE_GATE.defaultBlockState());
        s.buildings.addAll(List.of(house,wall));
        s.capturedPieces.add(p(12,64,12));
        var named=new VillageState.ResidentName();named.json="{\"text\":\"Ива\"}";named.visible=true;s.residentNames.put(id(1),named);
        s.residentPositions.put(id(1),p(1,64,1));
        s.residentRoles.put(id(1),new ResidentRoles.Assignment(id(1),ResidentRoles.Role.MINER,"minecraft:toolsmith","родная профессия"));
        s.residentRoles.put(id(2),new ResidentRoles.Assignment(id(2),ResidentRoles.Role.FARMER,"minecraft:farmer","нужен специалист"));
        var zone=new VillagePolicy.Zone();zone.activity="mine";zone.minX=-5;zone.minZ=-5;zone.maxX=5;zone.maxZ=5;
        s.policy.zones.add(zone);s.policy.privateChests.add(p(5,64,5));s.policy.expansion=new LinkedHashSet<>(List.of("south","west"));
        s.lights.add(p(6,65,6));
        s.chronicle.totals.put("births",1L);s.chronicle.events.add("день 2: построен дом");
        s.lastBirth=2400;s.productDays.put(id(1),2L);s.lastGolem=7000;s.newsDay=2;s.newsTotals=new LinkedHashMap<>(Map.of("trees",4L));s.newsResidents=3;
        s.graves.add(new VillageState.Grave("Лука","сторож",2));s.cemetery=p(-30,64,30);
        s.nameplates.put(p(9,64,-11),p(10,64,-10));
        s.donations.put(id(50),20L);s.donorNames.put(id(50),"Alex");s.honoured.add(id(50));
        s.goodsDays.put("mill",3L);s.fairDay=5;
        s.request=new VillageState.Request();s.request.key="bed";s.request.amount=2;s.request.delivered=1;s.request.posted=24_000;s.nextRequest=50_000;
        s.noticeBoard=p(1,64,-1);
        s.features.put("orchard","{\"trees\":[1,2]}");s.features.put("campfire","{}");
        return s;
    }

    @Test void everyFieldSurvivesARoundTrip(){
        var s=populated();
        var before=tree(s);
        var restored=roundTrip(s);
        restored.compactMetadata();restored.validate();
        assertEquals(before,tree(restored));
    }

    @Test void roundTripCoversEveryField(){
        var missing=new TreeSet<>(persistedFields());POPULATED.forEach(missing::remove);
        var stale=new TreeSet<>(POPULATED);persistedFields().forEach(stale::remove);
        assertEquals(new TreeSet<>(POPULATED),persistedFields(),"add the new field to SaveFormatTest (POPULATED, populated(), and SINCE_MASTER with its default check); missing "+missing+", gone "+stale);
        var defaults=tree(new VillageState());var full=tree(populated());
        for(String field:POPULATED)assertNotEquals(defaults.get(field),full.get(field),field+" is left at its default in populated(): add the new field to SaveFormatTest");
    }

    @Test void newSaveIsAdditiveForOldReaders(){
        var old=JsonParser.parseString(fixture()).getAsJsonObject();
        var save=read(fixture());
        for(var s:save.villages){s.compactMetadata();s.validate();}
        var written=JsonParser.parseString(VillageRuntime.GSON.toJson(save)).getAsJsonObject();
        assertEquals(4,written.get("version").getAsInt());
        assertTrue(written.keySet().containsAll(old.keySet()),written.keySet().toString());
        var before=old.getAsJsonArray("villages").get(0).getAsJsonObject();var after=written.getAsJsonArray("villages").get(0).getAsJsonObject();
        assertTrue(after.keySet().containsAll(before.keySet()),"dropped "+before.keySet().stream().filter(k->!after.has(k)).toList());
        assertKeeps(old,written,"save");
        // An old reader skips what it does not know: only fields added since master are new.
        var added=new TreeSet<>(after.keySet());added.removeAll(before.keySet());
        assertTrue(SINCE_MASTER.containsAll(added),added.toString());
    }

    record Corruption(String name,BiConsumer<VillageState,Random> apply){}
    static long inside(VillageState s,Random r){var b=s.bounds;return p(b.minX+r.nextInt(b.maxX-b.minX+1),60+r.nextInt(20),b.minZ+r.nextInt(b.maxZ-b.minZ+1));}
    static long outside(VillageState s,Random r){return p(s.bounds.maxX+1+r.nextInt(500),64,s.bounds.minZ-1-r.nextInt(500));}
    static void some(Random r,List<Runnable> changes){for(var c:changes)if(r.nextBoolean())c.run();}
    /** Damage only to metadata; "missing collections" stays last so the others find their collections. */
    static final List<Corruption> CORRUPTIONS=List.of(
            new Corruption("storage past its cap",(s,r)->{int n=VillageState.MAX_STORAGE+1+r.nextInt(64);while(s.storage.size()<n)s.storage.add(inside(s,r));}),
            new Corruption("furnaces past their cap",(s,r)->{int n=VillageState.MAX_FURNACES+1+r.nextInt(16);while(s.furnaces.size()<n)s.furnaces.add(inside(s,r));}),
            new Corruption("2000 lights",(s,r)->{while(s.lights.size()<2000)s.lights.add(inside(s,r));}),
            new Corruption("300 nameplates",(s,r)->{while(s.nameplates.size()<300)s.nameplates.put(inside(s,r),inside(s,r));}),
            new Corruption("negative donations",(s,r)->{s.donations.put(id(50),-5L);s.donations.put(new UUID(r.nextLong(),r.nextLong()),-1L-r.nextInt(1000));}),
            new Corruption("impossible request",(s,r)->{var q=new VillageState.Request();q.key=r.nextInt(4)==0?null:"door";q.amount=r.nextInt(4)==0?VillageRequests.MAX_AMOUNT+1:0;q.delivered=-r.nextInt(3);s.request=q;}),
            new Corruption("unknown goodsDays key",(s,r)->{s.goodsDays.put("castle",3L);s.goodsDays.put("forge",null);}),
            new Corruption("30 graves with nulls",(s,r)->{for(int i=0;i<30;i++)s.graves.add(switch(r.nextInt(4)){
                case 0->null;case 1->new VillageState.Grave(null,"шахтёр",i);case 2->new VillageState.Grave("Имя"+i,null,i);default->new VillageState.Grave("Имя"+i,"фермер",i);});}),
            new Corruption("mine failures 99",(s,r)->s.mineFailures=r.nextBoolean()?99:-3),
            new Corruption("mine retry before time began",(s,r)->s.mineRetryAt=r.nextBoolean()?-5:Long.MIN_VALUE),
            new Corruption("notice board outside",(s,r)->s.noticeBoard=outside(s,r)),
            new Corruption("40000-char feature",(s,r)->s.features.put("orchard","x".repeat(40_000))),
            new Corruption("null entries",(s,r)->some(r,List.of(()->s.residents.add(null),()->s.storage.add(null),()->s.furnaces.add(null),()->s.lights.add(null),
                    ()->s.plantedTrees.add(null),()->s.plantedCrops.add(null),()->s.fellingTrees.add(null),()->s.reforestation.add(null),()->s.capturedPieces.add(null),
                    ()->s.treeWork.values().forEach(l->l.add(null)),()->s.treeWork.put(inside(s,r),null),()->s.treeWork.put(null,new ArrayList<>()),
                    ()->s.residentNames.put(id(2),null),()->s.residentPositions.put(id(2),null),()->s.residentRoles.put(id(3),null),()->s.productDays.put(id(2),null),
                    ()->s.policy.zones.add(null),()->s.policy.privateChests.add(null),()->s.policy.expansion.add(null),()->s.policy.expansion.add("up"),
                    ()->s.chronicle.events.add(null),()->s.chronicle.totals.put("trees",null),()->s.newsTotals.put("births",null),()->s.graves.add(null),
                    ()->s.nameplates.put(null,inside(s,r)),()->s.nameplates.put(inside(s,r),null),()->s.donations.put(null,1L),()->s.donations.put(id(51),null),
                    ()->s.donorNames.put(id(50),null),()->s.honoured.add(null),()->s.goodsDays.put(null,1L),()->s.features.put(null,"{}"),()->s.features.put("storm",null),
                    ()->s.buildings.add(null),()->s.buildings.getFirst().blocks.add(null),()->s.buildings.getFirst().cuts.add(null),()->s.buildings.getFirst().path.add(null)))),
            new Corruption("counters out of range",(s,r)->{s.lastBirth=-1-r.nextInt(100);s.lastGolem=-1-r.nextInt(100);s.completedShelters=r.nextBoolean()?999:-3;
                    s.mineDirection=r.nextInt(20)-10;s.mineLength=r.nextBoolean()?999:-5;s.mineStatus=r.nextBoolean()?"exploded":null;}),
            new Corruption("broken role assignments",(s,r)->{s.residentRoles.get(id(1)).schedule=24+r.nextInt(10);s.residentRoles.get(id(2)).role=null;s.residentRoles.put(id(77),s.residentRoles.get(id(1)));}),
            new Corruption("stale positions outside",(s,r)->{s.storage.add(outside(s,r));s.furnaces.add(outside(s,r));s.lights.add(outside(s,r));s.cemetery=outside(s,r);
                    s.mineEntrance=outside(s,r);s.nameplates.put(outside(s,r),inside(s,r));s.plantedCrops.add(outside(s,r));s.plantedTrees.add(p(5000,64,5000));}),
            new Corruption("unreadable buildings",(s,r)->{s.buildings.add(new TownBuilding("castle",BlockPos.of(inside(s,r))));
                    var bad=new TownBuilding("house",BlockPos.of(inside(s,r)));var plan=new TownBuilding.Plan();plan.pos=bad.origin;
                    plan.state=r.nextBoolean()?"{":"{\"Name\":\"minecraft:no_such_block\"}";bad.blocks.add(plan);s.buildings.add(bad);}),
            new Corruption("overlong donor name",(s,r)->s.donorNames.put(id(50),"x".repeat(33+r.nextInt(100)))),
            new Corruption("missing collections",(s,r)->some(r,List.of(()->s.storage=null,()->s.furnaces=null,()->s.plantedTrees=null,()->s.plantedCrops=null,
                    ()->s.fellingTrees=null,()->s.treeWork=null,()->s.reforestation=null,()->s.buildings=null,()->s.capturedPieces=null,()->s.residentNames=null,
                    ()->s.residentPositions=null,()->s.residentRoles=null,()->s.lights=null,()->s.productDays=null,()->s.newsTotals=null,()->s.graves=null,
                    ()->s.nameplates=null,()->s.donations=null,()->s.donorNames=null,()->s.honoured=null,()->s.goodsDays=null,()->s.features=null,
                    ()->{s.policy.zones=null;s.policy.privateChests=null;s.policy.expansion=null;},()->s.policy=null,
                    ()->{s.chronicle.events=null;s.chronicle.totals=null;},()->s.chronicle=null))));
    static String identity(VillageState s){
        var b=s.bounds;
        return s.name+"|"+s.owner+"|"+s.x+","+s.y+","+s.z+"|"+s.radius+"|"+s.active+"|"+(b==null?"-":b.minX+","+b.minZ+","+b.maxX+","+b.maxZ);
    }
    /** Damage the case's own corruption plus a random few of the others; the same seed always gives the same village. */
    static List<String> corrupt(VillageState s,int run,Random r){
        var applied=new ArrayList<String>();
        for(int i=0;i<CORRUPTIONS.size();i++)if(i==run%CORRUPTIONS.size()||r.nextInt(4)==0){var c=CORRUPTIONS.get(i);c.apply().accept(s,r);applied.add(c.name());}
        return applied;
    }

    @Test void metadataOnlyCorruptionNeverBlocksTheSave(){
        for(int run=0;run<200;run++){
            var r=new Random(4_2026L+run);
            var s=populated();String identity=identity(s);var people=new LinkedHashSet<>(s.residents);
            String label="case "+run+" "+corrupt(s,run,r);
            assertDoesNotThrow(()->{s.compactMetadata();s.validate();},label);
            assertEquals(identity,identity(s),label);assertEquals(people,s.residents,label);
            assertTrue(s.storage.size()<=VillageState.MAX_STORAGE&&s.furnaces.size()<=VillageState.MAX_FURNACES,label);
            assertTrue(s.lights.size()<=1024&&s.nameplates.size()<=256&&s.graves.size()<=16,label);
            assertTrue(s.graves.stream().allMatch(g->g!=null&&g.name!=null&&g.role!=null),label);
            assertTrue(s.donations.values().stream().allMatch(n->n>=0),label);
            assertTrue(s.request==null||s.request.key!=null&&s.request.amount>=1&&s.request.amount<=VillageRequests.MAX_AMOUNT&&s.request.delivered>=0,label);
            assertTrue(s.goodsDays.keySet().stream().allMatch(Landmarks::kind),label);
            assertTrue(s.mineFailures>=0&&s.mineFailures<=16&&s.mineRetryAt>=0,label);
            assertTrue(s.noticeBoard==null||s.contains(BlockPos.of(s.noticeBoard)),label);
            assertTrue(s.features.values().stream().allMatch(v->v.length()<=Features.MAX_STATE_CHARS),label);
            // The repaired village is written without a null entry and reads back as it was.
            String json=write(s);
            assertFalse(java.util.regex.Pattern.compile("\\bnull\\b").matcher(json).find(),label);
            var back=read(json).villages.getFirst();
            assertDoesNotThrow(()->{back.compactMetadata();back.validate();},label);
            assertEquals(tree(s),tree(back),label);
        }
    }

    @Test void aBrokenIdentityIsStillRefused(){
        List<Corruption> broken=List.of(new Corruption("bad name",(s,r)->s.name="bad name!"),new Corruption("no name",(s,r)->s.name=null),
                new Corruption("long name",(s,r)->s.name="x".repeat(33)),new Corruption("bad owner",(s,r)->s.owner="not-a-uuid"),
                new Corruption("no owner",(s,r)->s.owner=null),new Corruption("huge radius",(s,r)->s.radius=5000),new Corruption("tiny radius",(s,r)->s.radius=10),
                new Corruption("sky",(s,r)->s.y=999),new Corruption("world edge",(s,r)->s.x=30_000_000),
                new Corruption("inverted bounds",(s,r)->s.bounds=new VillageState.Bounds(10,10,-10,-10)));
        int cases=0;
        for(var b:broken)for(int run=0;run<10;run++,cases++){
            var r=new Random(7_2026L+cases);var s=populated();
            String label=b.name()+" "+corrupt(s,run*3+cases,r);
            b.apply().accept(s,r);
            assertDoesNotThrow(s::compactMetadata,label);
            assertThrows(IllegalArgumentException.class,s::validate,label);
        }
        assertTrue(cases>=100);
        assertTrue(VillageState.NAME.matcher("Берёзовка_2").matches());assertFalse(VillageState.NAME.matcher("bad name!").matches());
    }

    @Test void capsDropTheOldestStoresFirst(){
        var s=populated();s.storage.clear();s.furnaces.clear();
        for(int i=0;i<130;i++)s.storage.add(p(-40+i%80,64,i/80));
        for(int i=0;i<20;i++)s.furnaces.add(p(i,65,0));
        long newestStore=p(-40+129%80,64,1);
        s.compactMetadata();s.validate();
        assertEquals(VillageState.MAX_STORAGE,s.storage.size());assertFalse(s.storage.contains(p(-40,64,0)));assertFalse(s.storage.contains(p(-39,64,0)));
        assertTrue(s.storage.contains(p(-38,64,0)));assertTrue(s.storage.contains(newestStore));
        assertEquals(VillageState.MAX_FURNACES,s.furnaces.size());assertEquals(p(4,65,0),s.furnaces.iterator().next());
        s.graves.add(new VillageState.Grave("Ян",null,4));s.mineRetryAt=-5;s.compactMetadata();
        assertEquals("",s.graves.getLast().role);assertEquals("Ян",s.graves.getLast().name);assertEquals(0,s.mineRetryAt);
    }

    @Test void aWallWithItsOriginOutsideTheBoundsKeepsItsRing(){
        var s=populated();s.buildings.clear();
        // An old palisade: its corner lies beyond the footprint, its posts within.
        var kept=new TownBuilding("wall",new BlockPos(-44,64,-44));
        for(int x=-40;x<=-37;x++)kept.add(new BlockPos(x,64,-40),Blocks.OAK_FENCE.defaultBlockState());
        kept.add(new BlockPos(-36,64,-40),Blocks.OAK_FENCE_GATE.defaultBlockState());
        var lost=new TownBuilding("wall",new BlockPos(50,64,50));
        for(int x=50;x<=53;x++)lost.add(new BlockPos(x,64,50),Blocks.OAK_FENCE.defaultBlockState());
        s.buildings.addAll(List.of(kept,lost));
        var loaded=roundTrip(s);
        loaded.compactMetadata();loaded.validate();
        assertEquals(1,loaded.buildings.size());
        var wall=loaded.buildings.getFirst();
        assertEquals("wall",wall.kind);assertEquals(p(-40,64,-40),wall.origin);assertTrue(loaded.contains(BlockPos.of(wall.origin)));
        assertEquals(kept.blocks.stream().map(b->b.pos).toList(),wall.blocks.stream().map(b->b.pos).toList());
        assertEquals(Blocks.OAK_FENCE_GATE,wall.blocks.getLast().block().getBlock());
    }
}
