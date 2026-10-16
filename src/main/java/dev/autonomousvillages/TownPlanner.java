package dev.autonomousvillages;

import dev.navigationai.api.*;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.tags.*;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.level.levelgen.Heightmap;

/** Long-lived shared projects; missing blocks are repairs, not forgotten after completion. */
final class TownPlanner {
    final Village v;
    private boolean captured;
    private int surveyCursor;
    private long nextPlanning;
    String stage="осмотр домов";
    private final Set<Long> protectedBlocks=new HashSet<>();
    private final Set<BlockPos> protectedDoors=new HashSet<>();
    private final Set<Long> constructionBlocks=new HashSet<>();
    private int protectedCount=-1;
    private long protectionTick=-1;
    private long materialsTick=-1;
    private List<TownBuilding.Plan> materials=List.of();
    private int pendingWood;
    private int missingSiteCycles;
    private long requestedTick=-1;
    private List<TownBuilding.Plan> requested=List.of();
    private boolean coalesced;
    private long shelterTick=-1;
    private final Map<Long,Boolean> shelterCache=new HashMap<>();
    TownPlanner(Village v){this.v=v;}
    void invalidate(){requestedTick=-1;materialsTick=-1;protectedCount=-1;shelterTick=-1;}
    boolean shelterIntact(BlockPos bed){
        long tick=v.level.getGameTime()/20;if(shelterTick!=tick){shelterCache.clear();shelterTick=tick;}
        return shelterCache.computeIfAbsent(bed.asLong(),ignored->{
            var house=v.state.buildings.stream().filter(b->b.blocks.stream().anyMatch(p->p.block().getBlock() instanceof BedBlock
                    &&(p.position().equals(bed)||p.position().relative(p.block().getValue(BedBlock.FACING)).equals(bed)))).findFirst().orElse(null);
            if(house==null)return true;
            for(var plan:house.blocks){
                BlockPos p=plan.position();var expected=plan.block();var actual=v.level.getBlockState(p);
                if(expected.getBlock() instanceof DoorBlock&&(!(actual.getBlock() instanceof DoorBlock)||!(v.level.getBlockState(p.above()).getBlock() instanceof DoorBlock)))return false;
                if(p.getY()>=bed.getY()-1&&p.getY()<=bed.getY()+4&&!plan.replaceOwned
                        &&!expected.getCollisionShape(v.level,p).isEmpty()&&Construction.clear(actual))return false;
            }
            return true;
        });
    }
    boolean protects(BlockPos p){
        if(protectedCount>=0&&protectionTick==v.level.getGameTime())return protectedBlocks.contains(p.asLong());
        protectionTick=v.level.getGameTime();
        int count=v.state.buildings.stream().mapToInt(b->b.blocks.size()).sum();
        if(count!=protectedCount){protectedBlocks.clear();protectedDoors.clear();constructionBlocks.clear();for(var b:v.state.buildings)for(var s:b.blocks){protectedBlocks.add(s.pos);if(s.block().getBlock() instanceof DoorBlock){protectedDoors.add(s.position());protectedBlocks.add(s.position().above().asLong());}if(s.block().getBlock() instanceof BedBlock)protectedBlocks.add(s.position().relative(s.block().getValue(BedBlock.FACING)).asLong());if(!b.kind.equals("native_house"))constructionBlocks.add(s.pos);}protectedCount=count;}
        return protectedBlocks.contains(p.asLong());
    }
    Collection<BlockPos> doors(){protects(v.state.center());return protectedDoors;}
    boolean constructionCell(BlockPos p){protects(p);return constructionBlocks.contains(p.asLong());}
    void capture(VillageDiscovery.Survey survey){
        for(var b:v.state.buildings)if(b.kind.equals("native_house"))v.state.capturedPieces.add(b.origin);
        for(var piece:survey.pieces()){
            if(v.state.buildings.size()>=256)break;
            var box=piece.getBoundingBox();
            long pieceId=new BlockPos(box.minX(),box.minY(),box.minZ()).asLong();
            if(v.state.capturedPieces.contains(pieceId))continue;
            if(survey.homes().stream().noneMatch(box::isInside))continue;
            v.state.capturedPieces.add(pieceId);
            var house=new TownBuilding("native_house",new BlockPos(box.minX(),box.minY(),box.minZ()));
            for(BlockPos q:BlockPos.betweenClosed(new BlockPos(box.minX(),box.minY(),box.minZ()),new BlockPos(box.maxX(),box.maxY(),box.maxZ()))){
                BlockPos p=q.immutable();BlockState s=v.level.getBlockState(p);
                if(!v.state.contains(p)||!structural(s)||upperHalf(s))continue;
                house.add(p,s);adopt(p);
            }
            if(!house.blocks.isEmpty()){house.completed=true;v.state.buildings.add(house);}
        }
        // Player-built villages have no structure pieces: flood the house touching each bed.
        for(BlockPos home:survey.homes())captureHouse(home);
        coalesceHouses();
        captured=true;
        invalidate();
    }
    private boolean coalesceHouses(){
        var byBed=new HashMap<Long,TownBuilding>();boolean changed=false;
        var houses=v.state.buildings.stream().filter(b->b.kind.equals("native_house")).sorted(Comparator.comparingInt((TownBuilding b)->b.tier).reversed()).toList();
        for(var house:houses){
            var beds=house.blocks.stream().filter(p->p.block().getBlock() instanceof BedBlock).map(p->p.pos).toList();
            var owner=beds.stream().map(byBed::get).filter(Objects::nonNull).findFirst().orElse(null);
            if(owner!=null&&owner!=house){
                var positions=new HashSet<>(owner.blocks.stream().map(p->p.pos).toList());
                for(var p:house.blocks)if(positions.add(p.pos))owner.blocks.add(p);
                v.state.buildings.remove(house);changed=true;
            }else owner=house;
            for(long bed:beds)byBed.put(bed,owner);
        }
        coalesced=true;return changed;
    }
    private void captureHouse(BlockPos home){
        if(v.state.buildings.size()>=256)return;
        if(v.state.buildings.stream().anyMatch(b->b.blocks.stream().anyMatch(s->s.block().getBlock() instanceof BedBlock&&s.position().distSqr(home)<4)))return;
        var house=new TownBuilding("native_house",home);var todo=new ArrayDeque<BlockPos>();var seen=new HashSet<BlockPos>();
        todo.add(home);todo.add(home.below());
        while(!todo.isEmpty()&&seen.size()<1500){BlockPos p=todo.removeFirst();if(!seen.add(p)||!v.state.contains(p)||p.distSqr(home)>16*16)continue;
            var s=v.level.getBlockState(p);if(!structural(s))continue;
            if(!upperHalf(s)){house.add(p,s);adopt(p);}for(Direction d:Direction.values())todo.add(p.relative(d));
        }
        if(!house.blocks.isEmpty()){house.completed=true;v.state.buildings.add(house);}
    }
    private void adopt(BlockPos p){
        if(v.state.storage.size()<128&&v.level.getBlockEntity(p) instanceof ChestBlockEntity c&&!c.isLocked())v.state.storage.add(p.asLong());
        if(v.state.furnaces.size()<16&&v.level.getBlockEntity(p) instanceof FurnaceBlockEntity f&&!f.isLocked())v.state.furnaces.add(p.asLong());
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
    void tick(){
        long now=v.level.getGameTime();
        if(!captured&&now%100==0){
            try{var survey=VillageDiscovery.survey(v.level,v.state.center());capture(survey);v.runtime.save();}catch(IllegalArgumentException ignored){}
        }
        if(now<nextPlanning)return;nextPlanning=now+100;
        boolean adjusted=!coalesced&&coalesceHouses();
        for(var building:v.state.buildings){
            if(building.kind.equals("native_house"))adjusted|=building.blocks.removeIf(p->!structural(p.block()));
            var unique=new LinkedHashMap<Long,TownBuilding.Plan>();for(var p:building.blocks)unique.put(p.pos,p);
            if(unique.size()!=building.blocks.size()){building.blocks=new ArrayList<>(unique.values());adjusted=true;}
            if(building.kind.equals("native_house")&&building.tier>0&&!building.completed){
                for(var p:List.copyOf(building.blocks))if(p.replaceOwned&&p.block().is(Blocks.COBBLESTONE)&&v.level.getBlockState(p.position()).is(BlockTags.PLANKS)
                        &&!Construction.clear(v.level.getBlockState(p.position().above()))){
                    var original=new TownBuilding.Plan(p.position(),v.level.getBlockState(p.position()));p.state=original.state;p.decodedReset();p.replaceOwned=false;
                    for(var other:building.blocks)if(other!=p&&other.block().is(BlockTags.PLANKS)&&reinforcementAccessible(other)){
                        var replacement=new TownBuilding.Plan(other.position(),Blocks.COBBLESTONE.defaultBlockState());other.state=replacement.state;other.decodedReset();other.replaceOwned=true;break;
                    }
                    adjusted=true;
                }
            }
            if(building.kind.equals("warehouse"))for(var p:building.blocks)
                if(!p.matches(v)&&v.level.getBlockState(p.position()).is(Blocks.CRAFTING_TABLE)&&v.index.tables.contains(p.position())&&!p.replaceOwned){p.replaceOwned=true;adjusted=true;}
        }
        if(adjusted)v.runtime.save();
        for(TownBuilding b:v.state.buildings){
            int built=b.built(v);if(built==b.blocks.size()&&!b.completed){b.completed=true;v.event("Завершено здание: "+label(b));v.runtime.save();}
        }
        if(v.state.buildings.size()>=256)return;
        if(v.state.buildings.stream().noneMatch(b->b.kind.equals("warehouse"))){
            BlockPos site=findSite(7,7);
            if(site!=null){TownBuilding b=warehouse(site);b.validate(v.state);v.state.buildings.add(b);invalidate();v.event("Размечена стройка отдельного склада");v.runtime.save();}
            else stage="ищут площадку склада, проверяют проходы";
            return;
        }
        if(v.state.buildings.stream().anyMatch(b->b.kind.equals("warehouse")&&!b.completed))return;
        if(v.needsChest()){
            var depot=v.state.buildings.stream().filter(b->b.kind.equals("warehouse")&&b.completed).findFirst().orElse(null);
            if(depot!=null){
                BlockPos origin=BlockPos.of(depot.origin);
                for(int z:new int[]{1,3})for(int x:new int[]{1,3,5}){
                    BlockPos p=origin.offset(x,1,z);
                    if(Construction.clear(v.level.getBlockState(p))&&!protects(p)){
                        depot.add(p,Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING,Direction.NORTH));depot.completed=false;v.event("Расширяют хранилище: заполняются сундуки");v.runtime.save();return;
                    }
                }
            }
        }
        if(v.state.buildings.stream().filter(b->!b.completed).count()>2)return;
        int beds=v.index.beds.size();
        if(beds<v.state.residents.size()+2&&v.state.completedShelters<128&&v.state.buildings.stream().noneMatch(b->b.kind.equals("house")&&!b.completed)){
            BlockPos site=findSite(5,5);
            if(site!=null){TownBuilding b=new TownBuilding("house",site);foundation(b,site,5,5);for(var s:Construction.shelter(site))b.add(s.pos(),s.state());b.validate(v.state);v.state.buildings.add(b);invalidate();v.state.completedShelters++;v.runtime.save();}
        }
        if(v.count(s->s.is(ItemTags.LOGS)||s.is(ItemTags.PLANKS))>=48&&v.count(Supplies::food)>=Math.max(6,v.workers.size()*3)&&now-v.state.lastUpgrade>1200){
            boolean improved=false;
            for(var house:v.state.buildings.stream().filter(b->!b.kind.equals("warehouse")&&b.tier<2&&b.completed).sorted(Comparator.comparingInt(b->b.tier)).toList())
                if(improve(house)){v.state.lastUpgrade=now;v.runtime.save();improved=true;break;}
            if(!improved)for(var house:v.state.buildings.stream().filter(b->b.kind.equals("native_house")&&b.tier<2&&b.completed).toList())
                if(reinforce(house)){house.tier++;house.completed=false;v.state.lastUpgrade=now;v.event("Каменное укрепление жилого дома");v.runtime.save();break;}
        }
    }
    static String label(TownBuilding b){return b.kind.equals("warehouse")?"склад":b.tier>0?"дом, улучшение "+b.tier:"жилой дом";}
    TownBuilding warehouse(BlockPos base){
        var b=new TownBuilding("warehouse",base);
        foundation(b,base,7,7);
        b.add(base.offset(3,0,-1),Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING,Direction.SOUTH));
        // A construction chest is placed on the eventual warehouse floor, then enclosed by the building.
        for(int z=0;z<7;z++)b.add(base.offset(3,0,z),Blocks.OAK_PLANKS.defaultBlockState());
        b.add(base.offset(2,0,4),Blocks.OAK_PLANKS.defaultBlockState());
        b.add(base.offset(2,1,4),Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING,Direction.NORTH));
        b.add(base.offset(4,0,4),Blocks.OAK_PLANKS.defaultBlockState());b.add(base.offset(4,1,4),Blocks.CRAFTING_TABLE.defaultBlockState());
        for(int x=0;x<7;x++)for(int z=0;z<7;z++)if(x!=3&&!(x==2&&z==4)&&!(x==4&&z==4))b.add(base.offset(x,0,z),Blocks.OAK_PLANKS.defaultBlockState());
        for(int y=1;y<=2;y++)for(int x=0;x<7;x++)for(int z=0;z<7;z++)if((x==0||x==6||z==0||z==6)&&!((x==2||x==3)&&z==0)){
            BlockState wall=(x==0||x==6)&&(z==0||z==6)?Blocks.OAK_LOG.defaultBlockState():
                    y==2&&(x==0||x==6)&&z>=2&&z<=4?Blocks.OAK_FENCE.defaultBlockState():Blocks.OAK_PLANKS.defaultBlockState();
            b.add(base.offset(x,y,z),wall);
        }
        b.add(base.offset(2,0,-1),Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING,Direction.SOUTH));
        b.add(base.offset(2,1,0),Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING,Direction.SOUTH).setValue(DoorBlock.HINGE,DoorHingeSide.LEFT));
        b.add(base.offset(3,1,0),Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING,Direction.SOUTH).setValue(DoorBlock.HINGE,DoorHingeSide.RIGHT));
        for(int z=0;z<7;z++)for(int x=0;x<7;x++){
            int height=x>=2&&x<=4?4:3;
            BlockState roof=x==3?Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE,SlabType.TOP)
                    :Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING,x<3?Direction.EAST:Direction.WEST);
            b.add(base.offset(x,height,z),roof);
        }
        for(int z:new int[]{0,6})for(int x=2;x<=4;x++)b.add(base.offset(x,3,z),Blocks.OAK_PLANKS.defaultBlockState());
        for(int x:new int[]{1,3,5})b.add(base.offset(x,1,5),Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING,Direction.NORTH));
        b.add(base.offset(5,1,3),Blocks.FURNACE.defaultBlockState().setValue(FurnaceBlock.FACING,Direction.NORTH));
        b.add(base.offset(1,1,2),Blocks.TORCH.defaultBlockState());b.add(base.offset(5,1,2),Blocks.TORCH.defaultBlockState());
        b.add(base.offset(1,2,-1),Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING,Direction.NORTH));
        return b;
    }
    private void foundation(TownBuilding b,BlockPos base,int width,int depth){
        for(int x:new int[]{0,width-1})for(int z:new int[]{0,depth-1}){
            BlockPos p=base.offset(x,-1,z);int surface=v.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,p.getX(),p.getZ());
            for(int y=surface;y<=p.getY();y++)if(Construction.clear(v.level.getBlockState(new BlockPos(p.getX(),y,p.getZ()))))b.add(new BlockPos(p.getX(),y,p.getZ()),Blocks.OAK_PLANKS.defaultBlockState());
        }
        int middle=width/2;
        for(int step=4;step>=1;step--){BlockPos p=base.offset(middle,1-step,-step);
            int surface=v.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,p.getX(),p.getZ());
            if(p.getY()>=surface&&Construction.clear(v.level.getBlockState(p)))b.add(p,Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING,Direction.SOUTH));
        }
    }
    private BlockPos findSite(int width,int depth){
        // Use the entire footprint, not a ring of a fixed radius. Only a few candidates each cycle.
        var bounds=v.state.bounds;if(bounds==null)return null;
        int columns=bounds.maxX-bounds.minX+1;
        BlockPos best=null;double bestScore=Double.POSITIVE_INFINITY;
        for(int n=0;n<48;n++){
            long random=++surveyCursor*0x9e3779b97f4a7c15L;
            int x=bounds.minX+(int)Math.floorMod(random,columns),z=bounds.minZ+(int)Math.floorMod(random>>>32,bounds.maxZ-bounds.minZ+1);
            int y=v.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z),low=y,high=y;
            for(int dx=-1;dx<=width;dx++)for(int dz=-4;dz<=depth;dz++){
                int height=v.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x+dx,z+dz);low=Math.min(low,height);high=Math.max(high,height);
            }
            // A full floor over a depression can seal a two-block pit around a worker. Until a paid
            // earthworks project has graded a slope, use ground with a continuous level approach.
            if(high!=low)continue;
            BlockPos base=new BlockPos(x,high,z);
            if(!v.level.hasChunkAt(base)||!siteWithinBounds(v.state,base,width,depth))continue;
            boolean good=true;
            for(int dx=-1;dx<=width&&good;dx++)for(int dz=-1;dz<=depth&&good;dz++){
                BlockPos p=base.offset(dx,0,dz);
                int surface=v.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,p.getX(),p.getZ());
                BlockPos ground=new BlockPos(p.getX(),surface-1,p.getZ());
                if(!VillageIndex.naturalSoil(v.level.getBlockState(ground))||!v.level.getBlockState(ground).isFaceSturdy(v.level,ground,Direction.UP))good=false;
                for(int dy=0;dy<4&&good;dy++)if(!Construction.clear(v.level.getBlockState(p.above(dy)))||protects(p.above(dy)))good=false;
            }
            if(good&&v.state.buildings.stream().noneMatch(b->BlockPos.of(b.origin).distSqr(base)<144)){
                double score=v.index.beds.stream().mapToDouble(home->{double dy=home.getY()-base.getY();return home.distSqr(base)+15*dy*dy;}).average().orElse(base.distSqr(v.state.center()));
                if(score<bestScore){best=base;bestScore=score;}
            }
        }
        if(best!=null){missingSiteCycles=0;return best;}
        if(++missingSiteCycles>=10&&v.level.getGameTime()-v.state.lastExpansion>2400){
            int direction=(surveyCursor/48)%4;
            var larger=new VillageState.Bounds(bounds.minX-(direction==0?24:0),bounds.minZ-(direction==1?24:0),bounds.maxX+(direction==2?24:0),bounds.maxZ+(direction==3?24:0));
            if(v.expand(larger)){v.state.lastExpansion=v.level.getGameTime();v.event("Осматривают соседнюю землю для новых зданий");v.runtime.save();}
            missingSiteCycles=0;
        }
        return null;
    }
    TownBuilding.Plan request(Resident resident){
        return request(resident,false);
    }
    TownBuilding.Plan request(Resident resident,boolean nonPlanks){
        if(!v.builder(resident.id))return null;
        List<TownBuilding> order=new ArrayList<>(v.state.buildings);
        order.sort(Comparator.comparingInt(b->b.completed?0:b.kind.equals("warehouse")?1:2));
        for(TownBuilding b:order){int considered=0;
            for(TownBuilding.Plan p:b.blocks){
                if(p.matches(v))continue;
                if(nonPlanks&&!b.completed&&p.block().is(BlockTags.PLANKS))continue;
                stage=(b.completed?"ремонт ":b.tier>0?"улучшение ":"строительство ")+label(b)+" "+b.built(v)+"/"+b.blocks.size();
                if(!v.ready(p.position(),resident.id))continue;
                if(Construction.clear(v.level.getBlockState(p.position()))||p.replaceOwned||Construction.sameMaterial(p.step(),v.level.getBlockState(p.position()))){
                    if(Construction.available(resident.bot(),p.step())!=null)return p;
                }
                if(++considered>12)break;
            }
            // This opportunistic pass runs before crafting. Let missing repair materials
            // reach the crafting/fetch phase instead of continually starting a lower upgrade.
            if(nonPlanks&&b.completed&&b.built(v)<b.blocks.size())return null;
        }
        return null;
    }
    Item wanted(Resident resident){
        if(!v.builder(resident.id))return Items.AIR;
        for(TownBuilding b:v.state.buildings)for(TownBuilding.Plan p:b.blocks)if(!p.matches(v)&&v.ready(p.position(),resident.id))return p.block().getBlock().asItem();
        return Items.AIR;
    }
    List<TownBuilding.Plan> wantedPlans(Resident r){
        if(!v.builder(r.id))return List.of();
        if(requestedTick==v.level.getGameTime())return requested;
        requestedTick=v.level.getGameTime();
        var order=new ArrayList<>(v.state.buildings);
        order.sort(Comparator.comparingInt(b->b.completed?0:b.kind.equals("warehouse")?1:2));
        var requested=new LinkedHashMap<Item,TownBuilding.Plan>();
        for(var building:order){
            int missing=0;
            for(var plan:building.blocks){
                if(plan.matches(v))continue;
                if(!plan.replaceOwned&&!Construction.clear(v.level.getBlockState(plan.position()))&&!Construction.sameMaterial(plan.step(),v.level.getBlockState(plan.position())))continue;
                requested.putIfAbsent(plan.block().getBlock().asItem(),plan);
                if(++missing>=12||requested.size()>=16)break;
            }
            if(requested.size()>=16)break;
        }
        // An unavailable material in the first project must not hide real supplies
        // for later repairs or beds. Execution still follows request()'s priority.
        this.requested=List.copyOf(requested.values());return this.requested;
    }
    private void refreshMaterials(){
        if(materialsTick==v.level.getGameTime())return;materialsTick=v.level.getGameTime();
        var items=new LinkedHashMap<Item,TownBuilding.Plan>();
        pendingWood=0;
        for(var b:v.state.buildings)for(var p:b.blocks)if(!p.matches(v)){
            items.putIfAbsent(p.block().getBlock().asItem(),p);if(p.block().is(BlockTags.PLANKS))pendingWood++;
        }
        materials=List.copyOf(items.values());
    }
    boolean needs(Resident r,ItemStack stack){return wantedPlans(r).stream().anyMatch(p->Construction.material(p.step()).test(stack));}
    int woodDemand(){refreshMaterials();return Math.max(96,pendingWood+96);}
    int timberBatch(){
        var project=v.state.buildings.stream().filter(b->!b.completed).findFirst().orElse(null);
        // Finish the current small batch instead of taking a nearly finished roof back into
        // a full 64-plank logging cycle. The remaining tree stays in the persistent ledger.
        return project!=null&&project.blocks.size()-project.built(v)<=32?24:32;
    }
    boolean pending(Item item){refreshMaterials();return materials.stream().anyMatch(p->p.block().getBlock().asItem()==item);}
    private boolean improve(TownBuilding house){
        // Finish housing before consuming the same timber and builders on optional upgrades.
        if(v.state.buildings.stream().anyMatch(b->b.kind.equals("house")&&!b.completed))return false;
        var door=house.blocks.stream().filter(p->p.block().getBlock() instanceof DoorBlock).findFirst().orElse(null);if(door==null)return false;
        if(house.tier==0){
            Direction outward=door.block().getValue(DoorBlock.FACING).getOpposite(),side=outward.getClockWise();
            BlockPos front=door.position().below().relative(outward);
            List<TownBuilding.Plan> added=new ArrayList<>();
            for(int x=-1;x<=1;x++)for(int z=0;z<2;z++){
                BlockPos p=front.relative(side,x).relative(outward,z);
                var actual=v.level.getBlockState(p);
                boolean terrain=VillageIndex.naturalSoil(actual)||actual.is(Blocks.DIRT_PATH);
                if(!v.state.contains(p)||!v.state.contains(p.above(2))||(!Construction.clear(actual)&&!terrain))return false;
                if(!v.level.getBlockState(p.below()).isFaceSturdy(v.level,p.below(),Direction.UP))return false;
                var addition=new TownBuilding.Plan(p,Blocks.OAK_PLANKS.defaultBlockState());addition.replaceOwned=terrain;added.add(addition);
            }
            for(int x:new int[]{-1,1}){
                BlockPos p=front.relative(side,x);if(!Construction.clear(v.level.getBlockState(p.above()))||!Construction.clear(v.level.getBlockState(p.above(2))))return false;
                added.add(new TownBuilding.Plan(p.above(),Blocks.OAK_FENCE.defaultBlockState()));added.add(new TownBuilding.Plan(p.above(2),Blocks.TORCH.defaultBlockState()));
            }
            BlockPos stair=front.relative(outward,2);
            var actual=v.level.getBlockState(stair);boolean terrain=VillageIndex.naturalSoil(actual)||actual.is(Blocks.DIRT_PATH);
            if(!v.state.contains(stair)||(!Construction.clear(actual)&&!terrain)||protects(stair))return false;
            var step=new TownBuilding.Plan(stair,Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING,outward.getOpposite()));step.replaceOwned=terrain;added.add(step);
            for(var part:added){house.blocks.removeIf(old->old.pos==part.pos);house.blocks.add(part);}
        }else{
            if(!reinforce(house))return false;
        }
        house.tier++;house.completed=false;v.event("Жители улучшают "+label(house));return true;
    }
    private boolean reinforce(TownBuilding house){
        if(v.state.buildings.stream().anyMatch(b->b.kind.equals("house")&&!b.completed))return false;
        int changed=0;int floor=house.blocks.stream().filter(p->p.block().is(BlockTags.PLANKS)).mapToInt(p->p.position().getY()).min().orElse(0);
        for(var p:house.blocks)if(p.position().getY()==floor&&p.block().is(BlockTags.PLANKS)&&reinforcementAccessible(p)){
            var replacement=new TownBuilding.Plan(p.position(),Blocks.COBBLESTONE.defaultBlockState());p.state=replacement.state;p.decodedReset();p.replaceOwned=true;
            if(++changed>=8)break;
        }
        return changed>0;
    }
    private boolean reinforcementAccessible(TownBuilding.Plan p){return Construction.clear(v.level.getBlockState(p.position().above()))
            &&v.stand(p.position(),net.minecraft.world.phys.Vec3.atBottomCenterOf(v.meetingPoint()),4.2,true)!=null;}
    static boolean siteWithinBounds(VillageState state,BlockPos base,int width,int depth){
        return state.contains(base.offset(-2,-3,-4))&&state.contains(base.offset(width+2,4,depth+2));
    }
}
