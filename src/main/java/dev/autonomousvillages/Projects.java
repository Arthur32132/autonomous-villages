package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Buildings of the village: generated houses are captured for protection, new projects are planned
 * on flat free ground next to the village. No road network or order book gates construction.
 */
final class Projects {
    static final int HOUSE_W=5,HOUSE_D=5;
    final Village v;
    String stage="осмотр деревни";
    private boolean captured;
    private final Set<Long> protectedBlocks=new HashSet<>();
    private final Set<BlockPos> protectedDoors=new HashSet<>();
    private int protectedCount=-1;
    private long protectionTick=-1;
    private long nextPlanning;
    private int siteCursor;
    Projects(Village v){this.v=v;}

    void invalidate(){protectedCount=-1;}
    boolean protects(BlockPos p){
        if(protectedCount<0||protectionTick!=v.level.getGameTime()){
            protectionTick=v.level.getGameTime();
            int count=v.state.buildings.stream().mapToInt(b->b.blocks.size()).sum()+v.state.buildings.size();
            if(count!=protectedCount){
                protectedBlocks.clear();protectedDoors.clear();
                for(var b:v.state.buildings)for(var s:b.blocks){
                    protectedBlocks.add(s.pos);
                    var block=s.block();
                    if(block.getBlock() instanceof DoorBlock){protectedDoors.add(s.position());protectedBlocks.add(s.position().above().asLong());}
                    if(block.getBlock() instanceof BedBlock)protectedBlocks.add(s.position().relative(block.getValue(BedBlock.FACING)).asLong());
                }
                protectedCount=count;
            }
        }
        return protectedBlocks.contains(p.asLong());
    }
    Collection<BlockPos> doors(){protects(BlockPos.ZERO);return protectedDoors;}

    // --- generated village houses ------------------------------------------------------------
    void capture(VillageDiscovery.Survey survey){
        for(var piece:survey.pieces()){
            if(v.state.buildings.size()>=256)break;
            var box=piece.getBoundingBox();
            long pieceId=new BlockPos(box.minX(),box.minY(),box.minZ()).asLong();
            if(v.state.capturedPieces.contains(pieceId))continue;
            v.state.capturedPieces.add(pieceId);
            if(survey.homes().stream().noneMatch(box::isInside)){
                // Smithies and stalls are not homes, but their chests (bread, often iron) serve the whole village.
                for(BlockPos q:BlockPos.betweenClosed(new BlockPos(box.minX(),box.minY(),box.minZ()),new BlockPos(box.maxX(),box.maxY(),box.maxZ())))
                    if(v.state.contains(q)&&v.level.getBlockEntity(q)!=null)adopt(q.immutable());
                continue;
            }
            var house=new TownBuilding("native_house",new BlockPos(box.minX(),box.minY(),box.minZ()));
            for(BlockPos q:BlockPos.betweenClosed(new BlockPos(box.minX(),box.minY(),box.minZ()),new BlockPos(box.maxX(),box.maxY(),box.maxZ()))){
                BlockPos p=q.immutable();BlockState s=v.level.getBlockState(p);
                if(!v.state.contains(p)||!structural(s)||upperHalf(s))continue;
                house.add(p,s);adopt(p);
            }
            if(!house.blocks.isEmpty()){house.completed=true;v.state.buildings.add(house);}
        }
        for(BlockPos home:survey.homes())captureHouse(home);
        captured=true;invalidate();
    }
    private void captureHouse(BlockPos home){
        if(v.state.buildings.size()>=256)return;
        if(v.state.buildings.stream().anyMatch(b->b.blocks.stream().anyMatch(s->s.block().getBlock() instanceof BedBlock&&s.position().distSqr(home)<4)))return;
        var house=new TownBuilding("native_house",home);var todo=new ArrayDeque<BlockPos>();var seen=new HashSet<BlockPos>();
        todo.add(home);todo.add(home.below());
        while(!todo.isEmpty()&&seen.size()<1500){
            BlockPos p=todo.removeFirst();if(!seen.add(p)||!v.state.contains(p)||p.distSqr(home)>16*16)continue;
            var s=v.level.getBlockState(p);if(!structural(s))continue;
            if(!upperHalf(s)){house.add(p,s);adopt(p);}
            for(Direction d:Direction.values())todo.add(p.relative(d));
        }
        if(!house.blocks.isEmpty()){house.completed=true;v.state.buildings.add(house);}
    }
    /** Unlocked chests and furnaces inside village houses become shared property on claim. */
    private void adopt(BlockPos p){
        if(!v.state.policy.privateChests.contains(p.asLong())&&v.state.storage.size()<128&&v.level.getBlockEntity(p) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity c&&!c.isLocked())v.state.storage.add(p.asLong());
        if(v.state.furnaces.size()<16&&v.level.getBlockEntity(p) instanceof net.minecraft.world.level.block.entity.FurnaceBlockEntity f&&!f.isLocked())v.state.furnaces.add(p.asLong());
        if(v.level.getBlockState(p).is(Blocks.CRAFTING_TABLE))v.index.tables.add(p);
    }
    static boolean upperHalf(BlockState s){return s.getBlock() instanceof DoorBlock&&s.getValue(DoorBlock.HALF)==DoubleBlockHalf.UPPER
            ||s.getBlock() instanceof BedBlock&&s.getValue(BedBlock.PART)==BedPart.HEAD;}
    static boolean structural(BlockState s){
        if(s.isAir()||s.getBlock().asItem()==Items.AIR||VillageIndex.naturalSoil(s)||s.is(BlockTags.LEAVES)||s.getBlock() instanceof CropBlock
                ||s.is(Blocks.STONE)||s.is(Blocks.DEEPSLATE)||s.is(Blocks.WATER)||s.is(Blocks.SAND)||s.is(Blocks.GRAVEL)
                ||s.is(Blocks.DIRT_PATH)||s.is(Blocks.FARMLAND)||s.is(Blocks.BUSH)||s.is(BlockTags.FLOWERS))return false;
        return !Construction.clear(s)&&!(s.getBlock() instanceof SaplingBlock);
    }

    // --- projects --------------------------------------------------------------------------------
    void tick(){
        long now=v.level.getGameTime();
        if(!captured&&now%100==0){
            try{capture(VillageDiscovery.survey(v.level,v.state.center()));v.runtime.save();}catch(IllegalArgumentException ignored){}
        }
        if(now<nextPlanning)return;
        nextPlanning=now+100;
        for(var b:v.state.buildings)if(!b.completed&&remaining(b)==0){
            b.completed=true;v.chronicle.announce("Построено: "+label(b)+" — новоселье!");
            if(b.kind.equals("house")||b.kind.equals("civic"))Festivities.housewarming(v,b);
            if(b.kind.equals("civic"))furnish(b);
            if(b.kind.equals("well"))fillWell(b);
            if(b.path.isEmpty())b.path.addAll(footpath(b));
            v.runtime.save();
        }
        var unfinished=unfinished();
        var active=unfinished.isEmpty()?null:unfinished.getFirst();
        if(active!=null){
            stage=(waiting(active)?"ждут материалы: ":"строят ")+label(active)+" "+built(active)+"/"+size(active);
            // Only houses hold up the next house: a half-built watchtower never keeps a family homeless.
            var houses=unfinished.stream().filter(b->b.kind.equals("house")).toList();
            if(houses.size()>=3||!houses.stream().allMatch(this::waiting))return;
        }
        if(v.state.buildings.size()>=256||v.state.completedShelters>=64){stage="все проекты завершены";return;}
        int beds=v.index.beds.size();
        if(beds<v.state.residents.size()+2){
            var house=templateHouse();
            if(house==null){
                BlockPos site=findSite(HOUSE_W,HOUSE_D);
                if(site==null){stage="ищут место для нового дома";return;}
                house=cottage(site);
            }
            v.state.buildings.add(house);v.state.completedShelters++;invalidate();
            v.chronicle.announce("Заложен "+label(house)+" у "+BlockPos.of(house.origin).toShortString());v.runtime.save();
            stage="заложен "+label(house);
        }else if(AutonomousVillagesMod.CONFIG.storehouse&&unfinished.isEmpty()&&v.state.residents.size()>=4&&storehouse()==null){
            // The store moves under a roof: a timber barn by the bell with chests inside.
            BlockPos site=null;
            for(int attempt=0;attempt<32&&site==null;attempt++){
                BlockPos hub=v.meetingPoint();int ring=5+attempt/8*2;double angle=attempt*Math.PI/4+attempt/8*0.4;
                site=siteAt(hub.getX()+(int)Math.round(Math.cos(angle)*ring)-BARN_W/2,hub.getZ()+(int)Math.round(Math.sin(angle)*ring)-BARN_D/2,BARN_W,BARN_D);
            }
            if(site==null){stage="жилья хватает; ищут место для амбара";return;}
            var built=storehouse(site);v.state.buildings.add(built);invalidate();
            v.chronicle.announce("Заложен амбар у "+site.toShortString());v.runtime.save();
            stage="заложен амбар";
        }else if(AutonomousVillagesMod.CONFIG.storehouse&&unfinished.isEmpty()&&storehouse()!=null&&well()==null&&!v.level.getBlockState(v.meetingPoint()).is(Blocks.WATER)){
            BlockPos site=null;
            for(int attempt=0;attempt<32&&site==null;attempt++){
                BlockPos hub=v.meetingPoint();int ring=4+attempt/8*2;double angle=attempt*Math.PI/4+0.2+attempt/8*0.4;
                site=siteAt(hub.getX()+(int)Math.round(Math.cos(angle)*ring)-2,hub.getZ()+(int)Math.round(Math.sin(angle)*ring)-2,4,4);
            }
            if(site==null){stage="жилья хватает; ищут место для колодца";return;}
            var built=well(site);v.state.buildings.add(built);invalidate();
            v.chronicle.announce("Заложен колодец у "+site.toShortString());v.runtime.save();
            stage="заложен колодец";
        }else if(AutonomousVillagesMod.CONFIG.watchtower&&unfinished.isEmpty()&&v.state.residents.size()>=4&&tower()==null){
            // With every family housed the village puts up a watchtower by the bell for its guards.
            BlockPos site=findSite(TOWER,TOWER);
            if(site==null){stage="жилья хватает; ищут место для вышки";return;}
            var built=tower(site);v.state.buildings.add(built);invalidate();
            v.chronicle.announce("Заложена "+label(built)+" у "+site.toShortString());v.runtime.save();
            stage="заложена "+label(built);
        }else if(AutonomousVillagesMod.CONFIG.civicBuildings&&unfinished.isEmpty()&&tower()!=null&&civic().size()<Math.min(6,v.state.residents.size()/4)){
            // Then the workplaces of a real village, one of each trade, as the village grows.
            var built=civicBuilding();
            if(built==null){stage="жилья хватает; ищут место для мастерской";return;}
            v.state.buildings.add(built);invalidate();
            v.chronicle.announce("Заложена "+label(built));v.runtime.save();
            stage="заложена "+label(built);
        }else if(active==null)stage="жилья хватает; копят запасы";
    }
    List<TownBuilding> civic(){return v.state.buildings.stream().filter(b->b.kind.equals("civic")).toList();}
    /** The next trade's workplace of the village's style, a trade not built yet, entrance toward the bell. */
    TownBuilding civicBuilding(){
        var have=new HashSet<String>();for(var b:civic())have.add(Blueprints.trade(b.design));
        var designs=Blueprints.civic(v.level,style()).stream().filter(t->{String p=t.id().getPath();return !have.contains(Blueprints.trade(p.substring(p.lastIndexOf('/')+1)));})
                .sorted(Comparator.comparingInt(Blueprints.Template::work)).toList();
        BlockPos hub=v.meetingPoint();
        for(var design:designs){
            var b=place(design,hub);
            if(b!=null){b.kind="civic";return b;}
        }
        return null;
    }
    /**
     * A finished workplace is furnished by its guild: the template's workstation and decoration that
     * the village could not make appear on the housewarming, so a new trade can move in. A field gets
     * its farmland, water and first sowing the same way.
     */
    void furnish(TownBuilding b){
        int placed=0;
        for(var p:b.blocks){
            var here=v.level.getBlockState(p.position());
            // Fields are laid into the ground: soil and sand give way to the template's farmland.
            if(!p.optional||!v.level.hasChunkAt(p.position())||!Construction.clear(here)&&!VillageIndex.naturalSoil(here)&&!here.is(Blocks.SAND))continue;
            var s=p.block();
            if(s.getBlock() instanceof BedBlock||s.getBlock() instanceof DoorBlock||!s.canSurvive(v.level,p.position()))continue;
            v.level.setBlock(p.position(),s,3);placed++;
        }
        if(placed>0)v.chronicle.add("furnished",placed);
    }
    TownBuilding well(){return v.state.buildings.stream().filter(b->b.kind.equals("well")).findFirst().orElse(null);}
    /** A well: a cobblestone ring round a 2×2 basin, oak posts at the corners and a slab canopy. */
    TownBuilding well(BlockPos base){
        var b=new TownBuilding("well",base);var cobble=Blocks.COBBLESTONE.defaultBlockState();
        for(int x=0;x<4;x++)for(int z=0;z<4;z++){
            BlockPos floor=base.offset(x,0,z);boolean basin=x>=1&&x<=2&&z>=1&&z<=2;
            for(int y=-2;y<0;y++){BlockPos fill=floor.offset(0,y,0);if(v.level.getBlockState(fill).isAir())b.add(fill,cobble);}
            if(!basin)b.add(floor,cobble);else b.add(floor.below(),cobble);
            for(int y=1;y<=5;y++)if(!Construction.clear(v.level.getBlockState(floor.above(y))))b.cuts.add(floor.above(y).asLong());
            if(basin&&!Construction.clear(v.level.getBlockState(floor)))b.cuts.add(floor.asLong());
            boolean corner=(x==0||x==3)&&(z==0||z==3);
            if(!basin)b.add(floor.above(),corner?cobble:Blocks.COBBLESTONE_WALL.defaultBlockState());
            if(corner)for(int y=2;y<=3;y++)b.add(floor.above(y),Blocks.OAK_FENCE.defaultBlockState());
            b.add(floor.above(4),(x==0||x==3||z==0||z==3)?Blocks.OAK_SLAB.defaultBlockState():Blocks.OAK_PLANKS.defaultBlockState());
        }
        b.blocks.removeIf(p->!v.state.contains(p.position()));
        return b;
    }
    /** The well is filled on its housewarming: the basin turns to water. */
    void fillWell(TownBuilding b){
        BlockPos base=BlockPos.of(b.origin);
        for(int x=1;x<=2;x++)for(int z=1;z<=2;z++){BlockPos p=base.offset(x,0,z);if(Construction.clear(v.level.getBlockState(p)))v.level.setBlock(p,Blocks.WATER.defaultBlockState(),3);}
    }
    TownBuilding storehouse(){return v.state.buildings.stream().filter(b->b.kind.equals("storehouse")).findFirst().orElse(null);}
    static final int BARN_W=7,BARN_D=5;
    /**
     * A timber barn for the village store: log corners and a log sill, plank walls with a window on
     * each side, a pitched plank-and-stair roof, three chests along the back wall, a workbench and a
     * torch inside, the door facing south.
     */
    TownBuilding storehouse(BlockPos base){
        var b=new TownBuilding("storehouse",base);
        var log=Blocks.OAK_LOG.defaultBlockState();var planks=Blocks.OAK_PLANKS.defaultBlockState();
        for(int x=0;x<BARN_W;x++)for(int z=0;z<BARN_D;z++){
            BlockPos floor=base.offset(x,0,z);
            for(int y=-2;y<0;y++){BlockPos fill=floor.offset(0,y,0);if(v.level.getBlockState(fill).isAir())b.add(fill,Blocks.COBBLESTONE.defaultBlockState());}
            b.add(floor,(x==0||z==0||x==BARN_W-1||z==BARN_D-1)?Blocks.COBBLESTONE.defaultBlockState():planks);
            for(int y=1;y<=6;y++)if(!Construction.clear(v.level.getBlockState(floor.above(y))))b.cuts.add(floor.above(y).asLong());
        }
        for(int y=1;y<=3;y++)for(int x=0;x<BARN_W;x++)for(int z=0;z<BARN_D;z++){
            boolean edge=x==0||z==0||x==BARN_W-1||z==BARN_D-1;if(!edge)continue;
            boolean corner=(x==0||x==BARN_W-1)&&(z==0||z==BARN_D-1);
            if(x==3&&z==0&&y<=2)continue; // door
            if(!corner&&y==2&&(z==2&&(x==0||x==BARN_W-1)||x==3&&z==BARN_D-1)){b.add(base.offset(x,y,z),Blocks.GLASS_PANE.defaultBlockState());continue;}
            b.add(base.offset(x,y,z),corner||y==3?log:planks);
        }
        // Pitched roof along x: stairs on the long sides rising to a plank ridge.
        for(int x=-1;x<=BARN_W;x++){
            for(int z=-1;z<=BARN_D;z++){
                int fromEdge=Math.min(z+1,BARN_D-z);
                BlockPos p=base.offset(x,4+Math.min(fromEdge,2)-1,z);
                if(fromEdge>=3){b.add(p,planks);continue;}
                Direction up=z<BARN_D/2?Direction.SOUTH:Direction.NORTH;
                b.add(p,Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING,up));
            }
        }
        for(int z=1;z<BARN_D-1;z++){b.add(base.offset(0,4,z),planks);b.add(base.offset(BARN_W-1,4,z),planks);}
        b.add(base.offset(3,1,0),Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING,Direction.SOUTH));
        for(int x:new int[]{1,3,5})b.add(base.offset(x,1,BARN_D-2),Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING,Direction.SOUTH));
        b.add(base.offset(1,1,1),Blocks.CRAFTING_TABLE.defaultBlockState());
        b.add(base.offset(5,1,1),Blocks.TORCH.defaultBlockState());
        b.decorate(base.offset(2,1,BARN_D-2),Blocks.BARREL.defaultBlockState());
        b.decorate(base.offset(4,1,BARN_D-2),Blocks.BARREL.defaultBlockState());
        b.blocks.removeIf(p->!v.state.contains(p.position()));
        return b;
    }
    TownBuilding tower(){return v.state.buildings.stream().filter(b->b.kind.equals("tower")).findFirst().orElse(null);}
    /** Where a guard stands on the finished watchtower's lookout, if there is one. */
    BlockPos lookout(){
        var t=tower();
        return t==null||!t.completed?null:BlockPos.of(t.origin).offset(1,5,1);
    }
    TownBuilding active(){
        return v.state.buildings.stream().filter(b->!b.completed&&!b.kind.equals("native_house")).findFirst().orElse(null);
    }
    List<TownBuilding> unfinished(){return v.state.buildings.stream().filter(b->!b.completed&&!b.kind.equals("native_house")).toList();}
    /** Since when nothing of a project could be paid for (by origin); builders report it. */
    private final Map<Long,Long> unpaidSince=new HashMap<>();
    void unpaid(TownBuilding b){unpaidSince.putIfAbsent(b.origin,v.level.getGameTime());}
    void paid(TownBuilding b){unpaidSince.remove(b.origin);}
    /** Materials the village makes from wood, stone and sand; a shortage of these is only a matter of time. */
    static final Set<String> EVERYDAY=Set.of("planks","logs","door","torch","cobblestone","stairs","slab","fence","trapdoor","stone_stairs",
            "stone_slab","stone_wall","glass_pane","ladder","chest","crafting_table","furnace");
    /**
     * A house waiting for something the village cannot make yet (a bed without wool) does not block the
     * next one. Short of planks or cobble it does: a second site would only split the same wood. After a
     * whole day without progress the next house may start anyway.
     */
    boolean waiting(TownBuilding b){
        Long since=unpaidSince.get(b.origin);if(since==null)return false;
        long idle=v.level.getGameTime()-since;
        return idle>24000||idle>3000&&missing(b).keySet().stream().noneMatch(EVERYDAY::contains);
    }
    static String label(TownBuilding b){
        String base=switch(b.kind){case "house"->"дом";case "native_house"->"дом деревни";case "doors"->"двери для домов";case "tower"->"сторожевая вышка";case "civic"->trade(b.design);case "storehouse"->"амбар";case "well"->"колодец";default->b.kind;};
        if(b.kind.equals("civic"))return base;
        return b.design==null||b.design.isEmpty()?base:base+" «"+b.design+"»";
    }
    static String trade(String design){
        return switch(Blueprints.trade(design)){
            case "library"->"библиотека";case "temple"->"храм";case "tool_smith"->"кузница";case "weaponsmith","weapon_smith"->"оружейная";
            case "armorer"->"бронная мастерская";case "butcher_shop","butchers_shop"->"мясная лавка";case "tannery"->"дубильня";case "mason"->"мастерская каменщика";
            case "cartographer"->"картографическая";case "small_farm","large_farm","farm"->"поле";case "shepherd"->"овчарня";case "fletcher"->"мастерская лучника";default->"мастерская";
        };
    }
    /** Structural blocks of the plan; decoration does not count toward progress. */
    int size(TownBuilding b){return (int)b.blocks.stream().filter(p->!p.optional).count();}
    int built(TownBuilding b){return (int)b.blocks.stream().filter(p->!p.optional&&p.matches(v)).count();}
    int remaining(TownBuilding b){
        int n=0;for(long cut:b.cuts)if(!Construction.clear(v.level.getBlockState(BlockPos.of(cut)))&&removable(v.level.getBlockState(BlockPos.of(cut)))&&!planned(b,cut))n++;
        for(var p:b.blocks)if(!p.optional&&!p.matches(v)&&!blockedForever(p))n++;
        return n;
    }
    private boolean planned(TownBuilding b,long pos){return b.blocks.stream().anyMatch(p->p.pos==pos&&p.matches(v));}
    /** A foreign, non-natural block standing in a planned cell is left alone; the plan skips it. */
    boolean blockedForever(TownBuilding.Plan p){
        var actual=v.level.getBlockState(p.position());
        return !Construction.clear(actual)&&!Construction.sameMaterial(p.step(),actual)&&!removable(actual);
    }
    static boolean removable(BlockState s){
        return VillageIndex.naturalSoil(s)||s.is(Blocks.STONE)||s.is(Blocks.SAND)||s.is(Blocks.GRAVEL)||s.is(BlockTags.LEAVES)||s.is(BlockTags.FLOWERS)
                ||s.is(Blocks.DIRT_PATH)||s.getBlock() instanceof SaplingBlock||s.is(Blocks.BUSH)||s.is(Blocks.SNOW)||s.is(Blocks.SNOW_BLOCK);
    }
    /** Cells to clear first, then missing blocks bottom-up. */
    List<BlockPos> pendingCuts(TownBuilding b,int limit){
        var out=new ArrayList<BlockPos>();
        for(long cut:b.cuts){BlockPos p=BlockPos.of(cut);var s=v.level.getBlockState(p);if(!Construction.clear(s)&&removable(s)&&!planned(b,cut))out.add(p);if(out.size()>=limit)break;}
        for(var plan:b.blocks){if(out.size()>=limit)break;var s=v.level.getBlockState(plan.position());if(!plan.optional&&!plan.matches(v)&&!Construction.clear(s)&&removable(s)&&!out.contains(plan.position()))out.add(plan.position());}
        return out;
    }
    /** Missing structural blocks bottom-up, then decoration the store happens to hold. */
    List<TownBuilding.Plan> nextSteps(TownBuilding b,int limit){
        return b.blocks.stream().filter(p->!p.matches(v)&&!blockedForever(p)&&Construction.clear(v.level.getBlockState(p.position()))
                        &&(!p.optional||v.stock.count(s->s.is(p.step().item()))>0))
                .sorted(Comparator.<TownBuilding.Plan>comparingInt(p->p.optional?1:0).thenComparingInt(p->p.position().getY()).thenComparingInt(Projects::stepOrder))
                .limit(limit).toList();
    }
    /** Doors and beds after the walls around them; torches last. */
    private static int stepOrder(TownBuilding.Plan p){
        var s=p.block();
        if(s.getBlock() instanceof DoorBlock)return 2;
        if(s.getBlock() instanceof BedBlock)return 3;
        if(s.is(Blocks.TORCH)||s.is(Blocks.WALL_TORCH))return 4;
        return 0;
    }
    /** Missing blocks of a project grouped by the item that pays for them. */
    Map<String,Integer> missing(TownBuilding b){
        var out=new TreeMap<String,Integer>();
        for(var p:b.blocks)if(!p.optional&&!p.matches(v)&&!blockedForever(p))out.merge(materialKey(p.block()),Construction.cost(p.step()),Integer::sum);
        return out;
    }
    static String materialKey(BlockState s){
        if(s.is(BlockTags.PLANKS))return "planks";
        if(s.is(BlockTags.LOGS))return "logs";
        if(s.is(BlockTags.WOODEN_DOORS))return "door";
        if(s.getBlock() instanceof BedBlock)return "bed";
        if(s.is(Blocks.TORCH)||s.is(Blocks.WALL_TORCH))return "torch";
        if(s.is(Blocks.COBBLESTONE))return "cobblestone";
        if(s.is(BlockTags.WOODEN_STAIRS))return "stairs";
        if(s.is(BlockTags.WOODEN_SLABS))return "slab";
        if(s.is(BlockTags.WOODEN_FENCES))return "fence";
        if(s.is(BlockTags.WOODEN_TRAPDOORS))return "trapdoor";
        if(s.is(Blocks.COBBLESTONE_STAIRS))return "stone_stairs";
        if(s.is(Blocks.COBBLESTONE_SLAB))return "stone_slab";
        if(s.is(Blocks.COBBLESTONE_WALL))return "stone_wall";
        if(s.is(Blocks.GLASS_PANE))return "glass_pane";
        if(s.is(Blocks.LADDER))return "ladder";
        if(s.is(Blocks.CHEST))return "chest";
        if(s.is(Blocks.CRAFTING_TABLE))return "crafting_table";
        if(s.is(Blocks.FURNACE))return "furnace";
        return s.getBlock().asItem().toString();
    }

    // --- sites and blueprints ----------------------------------------------------------------------
    /** Flat, free, natural ground near the meeting point; rings outward a few candidates per call. */
    BlockPos findSite(int width,int depth){
        var bounds=v.state.bounds;if(bounds==null)return null;
        BlockPos hub=v.meetingPoint();
        for(int attempt=0;attempt<24;attempt++){
            int n=siteCursor++;int ring=6+(n/16%12)*4;double angle=(n%16)*Math.PI/8+(n/16)*0.37;
            int x=hub.getX()+(int)Math.round(Math.cos(angle)*ring)-width/2,z=hub.getZ()+(int)Math.round(Math.sin(angle)*ring)-depth/2;
            BlockPos base=siteAt(x,z,width,depth);
            if(base!=null)return base;
        }
        return null;
    }
    BlockPos siteAt(int x,int z,int width,int depth){
        int low=Integer.MAX_VALUE,high=Integer.MIN_VALUE;var heights=new ArrayList<Integer>();
        for(int dx=-2;dx<=width+1;dx++)for(int dz=-3;dz<=depth+1;dz++){
            BlockPos column=new BlockPos(x+dx,v.state.y,z+dz);
            if(!v.level.hasChunkAt(column)||!v.state.contains(column))return null;
            int h=v.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x+dx,z+dz);
            if(dx>=0&&dx<width&&dz>=0&&dz<depth){low=Math.min(low,h);high=Math.max(high,h);heights.add(h);}
        }
        if(high-low>1)return null;
        // The floor (layer 0) replaces the top layer of ground, as in a generated village: the door
        // then opens level with the street instead of a block above it.
        heights.sort(null);int y=heights.get(heights.size()/2)-1;
        BlockPos base=new BlockPos(x,y,z);
        for(int dx=-2;dx<=width+1;dx++)for(int dz=-3;dz<=depth+1;dz++)for(int dy=0;dy<=6;dy++){
            BlockPos p=base.offset(dx,dy,dz);var s=v.level.getBlockState(p);
            if(protects(p)||s.getBlock() instanceof BedBlock||s.is(Blocks.FARMLAND)||s.getBlock() instanceof CropBlock||!s.getFluidState().isEmpty()
                    ||s.is(BlockTags.LOGS)||s.getBlock() instanceof net.minecraft.world.level.block.ChestBlock||!v.state.policy.allows("build",p))return null;
            boolean inside=dx>=0&&dx<width&&dz>=0&&dz<depth;
            // Above ground the cells must be clearable; in the ground layer a foreign block (a cobblestone
            // street tile) simply stays as a tile of the floor.
            if(inside&&dy>=1&&!Construction.clear(s)&&!removable(s))return null;
            if(inside&&dy==0&&s.isAir()&&!v.level.getBlockState(p.below()).isFaceSturdy(v.level,p.below(),Direction.UP))return null;
        }
        if(v.state.buildings.stream().filter(b->b.kind.equals("house")||b.kind.equals("native_house")||b.kind.equals("civic")).anyMatch(b->BlockPos.of(b.origin).distSqr(base)<10*10))return null;
        return base;
    }
    /** Vanilla style of the village, by the most common villager type among residents. */
    String style(){
        var counts=new HashMap<String,Integer>();
        for(UUID id:v.state.residents)if(v.level.getEntity(id) instanceof net.minecraft.world.entity.npc.villager.Villager body)
            counts.merge(body.getVillagerData().type().unwrapKey().map(k->k.identifier().getPath()).orElse("plains"),1,Integer::sum);
        return Blueprints.style(counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("plains"));
    }
    /** A vanilla house of the village's style, its entrance turned toward the meeting point. */
    TownBuilding templateHouse(){
        var designs=Blueprints.houses(v.level,style());
        if(designs.isEmpty()||v.state.bounds==null)return null;
        // The three houses with the most beds for the work, in turn, so the street does not repeat itself.
        var best=designs.stream().sorted(Comparator.comparingDouble(Blueprints.Template::value).reversed()).limit(3).toList();
        BlockPos hub=v.meetingPoint();
        // A big house needs a bigger flat site; when none is found the next design gets its turn.
        for(int turn=0;turn<best.size();turn++){
            var design=best.get(Math.floorMod(v.state.completedShelters+turn,best.size()));
            var house=place(design,hub);
            if(house!=null)return house;
        }
        return null;
    }
    private TownBuilding place(Blueprints.Template design,BlockPos hub){
        for(int attempt=0;attempt<16;attempt++){
            int n=siteCursor++;int ring=8+(n/16%12)*4;double angle=(n%16)*Math.PI/8+(n/16)*0.37;
            int cx=hub.getX()+(int)Math.round(Math.cos(angle)*ring),cz=hub.getZ()+(int)Math.round(Math.sin(angle)*ring);
            Direction toHub=Math.abs(hub.getX()-cx)>=Math.abs(hub.getZ()-cz)?(hub.getX()>cx?Direction.EAST:Direction.WEST):(hub.getZ()>cz?Direction.SOUTH:Direction.NORTH);
            var rotation=Blueprints.facing(design.front(),toHub);
            var footprint=Blueprints.footprint(design.size(),rotation);
            BlockPos base=siteAt(cx-footprint.getX()/2,cz-footprint.getZ()/2,footprint.getX(),footprint.getZ());
            if(base==null)continue;
            // A template whose door stands higher than layer 1 sinks further, so the door is at street level.
            var house=fromTemplate(design,base.below(design.doorLevel()-1),rotation);
            if(doorwayOpen(house))return house;
        }
        return null;
    }
    TownBuilding fromTemplate(Blueprints.Template t,BlockPos base,Rotation rotation){
        var b=new TownBuilding("house",base);
        String path=t.id().getPath();b.design=path.substring(path.lastIndexOf('/')+1);
        for(var entry:t.blocks()){
            BlockState s=entry.state().rotate(rotation);
            if(s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)&&s.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)==DoubleBlockHalf.UPPER
                    ||s.getBlock() instanceof BedBlock&&s.getValue(BedBlock.PART)==BedPart.HEAD)continue;
            BlockPos p=base.offset(Blueprints.turn(entry.pos(),t.size(),rotation));
            if(Blueprints.structural(s))b.add(p,Blueprints.buildable(s));else b.decorate(p,s);
            // The floor rests on a cobblestone footing where the ground is lower.
            if(entry.pos().getY()==0&&Blueprints.structural(s))
                for(int y=1;y<=3&&v.level.getBlockState(p.below(y)).isAir();y++)b.add(p.below(y),Blocks.COBBLESTONE.defaultBlockState());
        }
        for(BlockPos rel:t.clear()){BlockPos p=base.offset(Blueprints.turn(rel,t.size(),rotation));if(!Construction.clear(v.level.getBlockState(p)))b.cuts.add(p.asLong());}
        b.blocks.removeIf(p->!v.state.contains(p.position()));b.cuts.removeIf(p->!v.state.contains(BlockPos.of(p)));
        return b;
    }
    /**
     * The front door opens onto two walkable cells outside the house, at the door's level or a step
     * up or down. A door facing a lamp post or a well traps whoever stands in the doorway.
     */
    boolean doorwayOpen(TownBuilding b){
        // The template's own doorstep and porch are part of its blocks, so "outside" is told by
        // direction: the door's neighbour farther from the middle of the building.
        double cx=b.blocks.stream().mapToInt(p->p.position().getX()).average().orElse(0),cz=b.blocks.stream().mapToInt(p->p.position().getZ()).average().orElse(0);
        boolean anyDoor=false;
        for(var plan:b.blocks){
            if(!(plan.block().getBlock() instanceof DoorBlock))continue;
            anyDoor=true;BlockPos door=plan.position();
            Direction out=null;double best=0;
            for(Direction d:Direction.Plane.HORIZONTAL){
                double away=d.getStepX()*(door.getX()+.5-cx)+d.getStepZ()*(door.getZ()+.5-cz);
                if(away>best){best=away;out=d;}
            }
            if(out!=null&&walkable(door.relative(out))&&walkable(door.relative(out,2)))return true;
        }
        return !anyDoor;
    }
    private boolean walkable(BlockPos feet){
        for(int dy:new int[]{0,1,-1}){
            BlockPos f=feet.above(dy);
            if(!v.level.hasChunkAt(f)||protects(f)||protects(f.above()))continue;
            var at=v.level.getBlockState(f);var head=v.level.getBlockState(f.above());var ground=v.level.getBlockState(f.below());
            if(at.getCollisionShape(v.level,f).isEmpty()&&at.getFluidState().isEmpty()&&head.getCollisionShape(v.level,f.above()).isEmpty()
                    &&!ground.getCollisionShape(v.level,f.below()).isEmpty()&&!VillageIndex.hazard(ground))return true;
        }
        return false;
    }
    /**
     * Ground cells from the door of a building toward the bell: grass and dirt on the way become a
     * footpath. The path ends where it meets an existing street or close to the centre.
     */
    List<Long> footpath(TownBuilding b){
        var door=b.blocks.stream().filter(p->p.block().getBlock() instanceof DoorBlock).map(TownBuilding.Plan::position).findFirst().orElse(null);
        if(door==null)return List.of();
        BlockPos hub=v.meetingPoint();
        var footprint=new HashSet<Long>();for(var p:b.blocks)footprint.add(BlockPos.asLong(p.position().getX(),0,p.position().getZ()));
        BlockPos start=null;double best=Double.MAX_VALUE;
        for(Direction d:Direction.Plane.HORIZONTAL){
            BlockPos out=door.relative(d);
            if(footprint.contains(BlockPos.asLong(out.getX(),0,out.getZ())))continue;
            double dist=out.distSqr(hub);if(dist<best){best=dist;start=out;}
        }
        if(start==null)return List.of();
        var out=new ArrayList<Long>();
        int x0=start.getX(),z0=start.getZ(),x1=hub.getX(),z1=hub.getZ(),dx=Math.max(1,Math.abs(x1-x0)),dz=Math.max(1,Math.abs(z1-z0)),sx=x0<x1?1:-1,sz=z0<z1?1:-1;
        int lastY=start.getY()-1;
        for(int steps=0;steps<160;steps++){
            if(Math.abs(x0-x1)<=2&&Math.abs(z0-z1)<=2)break;
            int top=v.level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x0,z0)-1;
            BlockPos ground=new BlockPos(x0,top,z0);var state=v.level.getBlockState(ground);
            if(state.is(Blocks.DIRT_PATH)&&steps>2)break; // joined a street
            if(Math.abs(top-lastY)>1)break;                 // a cliff: the path would lead nowhere
            if(!footprint.contains(BlockPos.asLong(x0,0,z0))&&(state.is(Blocks.GRASS_BLOCK)||state.is(Blocks.DIRT)||state.is(Blocks.COARSE_DIRT)||state.is(Blocks.PODZOL))
                    &&v.state.contains(ground)&&!protects(ground))out.add(ground.asLong());
            lastY=top;
            // One axis at a time, toward whichever is further behind the straight line: a footpath
            // made of diagonal corners cannot be walked along.
            if((long)Math.abs(x1-x0)*dz>=(long)Math.abs(z1-z0)*dx&&x0!=x1)x0+=sx;else z0+=sz;
        }
        return out;
    }
    /** Path cells of finished buildings that are still grass or dirt. */
    List<BlockPos> unpaved(int limit){
        var out=new ArrayList<BlockPos>();
        for(var b:v.state.buildings){
            if(!b.completed)continue;
            for(long packed:b.path){
                BlockPos p=BlockPos.of(packed);
                if(!v.level.hasChunkAt(p))continue;
                var s=v.level.getBlockState(p);
                if((s.is(Blocks.GRASS_BLOCK)||s.is(Blocks.DIRT)||s.is(Blocks.COARSE_DIRT)||s.is(Blocks.PODZOL))&&Construction.clear(v.level.getBlockState(p.above()))){
                    out.add(p);if(out.size()>=limit)return out;
                }
            }
        }
        return out;
    }
    /** A small timber cottage: log corners, plank walls with window gaps, flat roof, two beds, door and lights. */
    static final int TOWER=5;
    /**
     * A watchtower: a 3×3 cobblestone shaft with a door and a ladder up the middle, a 5×5 plank
     * lookout four blocks up with a fence railing, and torches on the railing's corners when the store
     * has them.
     */
    TownBuilding tower(BlockPos base){
        var b=new TownBuilding("tower",base);
        var cobble=Blocks.COBBLESTONE.defaultBlockState();
        for(int x=1;x<=3;x++)for(int z=1;z<=3;z++){
            BlockPos floor=base.offset(x,0,z);
            for(int y=-2;y<0;y++){BlockPos fill=floor.offset(0,y,0);if(v.level.getBlockState(fill).isAir())b.add(fill,cobble);}
            b.add(floor,cobble);
        }
        for(int x=0;x<TOWER;x++)for(int z=0;z<TOWER;z++)for(int y=1;y<=6;y++){
            BlockPos p=base.offset(x,y,z);if(!Construction.clear(v.level.getBlockState(p)))b.cuts.add(p.asLong());
        }
        for(int y=1;y<=3;y++)for(int x=1;x<=3;x++)for(int z=1;z<=3;z++){
            if(x==2&&z==2||x==2&&z==1&&y<=2)continue; // the ladder shaft and the doorway
            b.add(base.offset(x,y,z),cobble);
        }
        b.add(base.offset(2,1,1),Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING,Direction.SOUTH));
        for(int y=1;y<=4;y++)b.add(base.offset(2,y,2),Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING,Direction.WEST));
        // Every block is within a builder's reach from the ground around the tower.
        for(int x=0;x<TOWER;x++)for(int z=0;z<TOWER;z++)if(x!=2||z!=2)b.add(base.offset(x,4,z),Blocks.OAK_PLANKS.defaultBlockState());
        for(int x=0;x<TOWER;x++)for(int z=0;z<TOWER;z++)if(x==0||z==0||x==TOWER-1||z==TOWER-1)b.add(base.offset(x,5,z),Blocks.OAK_FENCE.defaultBlockState());
        for(int[] c:new int[][]{{0,0},{0,4},{4,0},{4,4}})b.decorate(base.offset(c[0],6,c[1]),Blocks.TORCH.defaultBlockState());
        b.blocks.removeIf(p->!v.state.contains(p.position()));
        return b;
    }
    TownBuilding cottage(BlockPos base){
        var b=new TownBuilding("house",base);
        int w=HOUSE_W,d=HOUSE_D;
        for(int x=0;x<w;x++)for(int z=0;z<d;z++){
            BlockPos floor=base.offset(x,0,z);
            for(int y=-2;y<0;y++){BlockPos fill=floor.offset(0,y,0);if(v.level.getBlockState(fill).isAir())b.add(fill,Blocks.COBBLESTONE.defaultBlockState());}
            b.add(floor,Blocks.OAK_PLANKS.defaultBlockState());
            for(int y=1;y<=4;y++)if(!Construction.clear(v.level.getBlockState(floor.above(y))))b.cuts.add(floor.above(y).asLong());
        }
        for(int y=1;y<=2;y++)for(int x=0;x<w;x++)for(int z=0;z<d;z++){
            boolean edge=x==0||z==0||x==w-1||z==d-1;if(!edge)continue;
            boolean corner=(x==0||x==w-1)&&(z==0||z==d-1);
            if(x==2&&z==0)continue; // door
            if(!corner&&y==2&&(x==2&&z==d-1||z==2&&(x==0||x==w-1)))continue; // windows
            b.add(base.offset(x,y,z),corner?Blocks.OAK_LOG.defaultBlockState():Blocks.OAK_PLANKS.defaultBlockState());
        }
        for(int x=-1;x<=w;x++)for(int z=-1;z<=d;z++){
            boolean rim=x==-1||z==-1||x==w||z==d;
            b.add(base.offset(x,3,z),rim?Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE,SlabType.BOTTOM):Blocks.OAK_PLANKS.defaultBlockState());
        }
        b.add(base.offset(2,1,0),Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING,Direction.SOUTH));
        b.add(base.offset(1,1,2),Blocks.BED.white().defaultBlockState().setValue(BedBlock.FACING,Direction.SOUTH).setValue(BedBlock.PART,BedPart.FOOT));
        b.add(base.offset(3,1,2),Blocks.BED.white().defaultBlockState().setValue(BedBlock.FACING,Direction.SOUTH).setValue(BedBlock.PART,BedPart.FOOT));
        b.add(base.offset(1,2,3),Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING,Direction.NORTH));
        b.add(base.offset(1,2,-1),Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING,Direction.NORTH));
        b.blocks.removeIf(p->!v.state.contains(p.position()));
        return b;
    }
}
