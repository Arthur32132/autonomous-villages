package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.*;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Loaded terrain graph rooted at one real village access point. No disconnected exit is a free start. */
final class NetworkRoutes {
    static final int MAX_LENGTH=192,MAX_WORK=64,MAX_EXPANDED=2048;
    record Link(BlockPos to,double cost,int work,int length,List<BlockPos> cells,
                List<TownBuilding.Plan> plans,List<Long> cuts) implements RouteSearch.Edge<BlockPos> { }
    record Planned(TownBuilding road,double cost,String failure) {boolean found(){return road!=null;}}
    private final Village v;
    private final Set<Long> excluded;
    private final Map<BlockPos,Boolean> walkable=new HashMap<>();
    private final Set<Long> knownRoad=new HashSet<>();
    private final TownBuilding replacing;
    private NetworkRoutes(Village v,Set<Long> excluded,Long target){
        this.v=v;this.excluded=excluded;
        replacing=target==null?null:v.state.buildings.stream().filter(b->b.kind.equals("road")&&b.origin==target).findFirst().orElse(null);
        for(var b:v.state.buildings)if(b.kind.equals("road"))knownRoad.addAll(b.routeCells);
    }
    static Planned plan(Village v,BlockPos start,BlockPos target,Set<Long> excluded){
        if(start==null)return new Planned(null,0,"нет безопасного выхода от склада или жилого дома");
        var graph=new NetworkRoutes(v,excluded,target.asLong());
        if(!graph.walk(start))return new Planned(null,0,"общий выход непроходим, вне загруженной территории или занят площадкой");
        if(!graph.walk(target))return new Planned(null,0,"вход непроходим: нет опоры, недостаточно места, жидкость или опасный блок");
        var result=RouteSearch.find(start,target::equals,graph::neighbors,
                p->(double)(Math.abs(p.getX()-target.getX())+Math.abs(p.getZ()-target.getZ())),MAX_EXPANDED,MAX_WORK,MAX_LENGTH);
        if(!result.found())return new Planned(null,0,result.failure());
        var road=new TownBuilding("road",target);road.routeStart=start.asLong();road.routeCells.add(start.asLong());
        var plans=new LinkedHashMap<Long,TownBuilding.Plan>();var cuts=new LinkedHashSet<Long>();
        for(var edge:result.edges()){
            for(var cell:edge.cells())road.routeCells.add(cell.asLong());
            cuts.addAll(edge.cuts());
            for(var part:edge.plans()){
                var old=plans.putIfAbsent(part.pos,part);
                if(old!=null&&!old.state.equals(part.state))return new Planned(null,0,"конфликт настила и ограждений на повороте");
            }
        }
        // A reused bridge includes its safety contract, not merely its walkable top surface.
        var used=new HashSet<>(road.routeCells);
        for(var existing:v.state.buildings)if(existing.kind.equals("road")&&existing.routeCells.stream().anyMatch(used::contains)){
            for(var part:existing.blocks)if(!Infrastructure.optional(part)&&road.routeCells.stream().map(BlockPos::of)
                    .anyMatch(cell->Math.abs(cell.getY()-part.position().getY())<=3
                        &&Math.abs(cell.getX()-part.position().getX())+Math.abs(cell.getZ()-part.position().getZ())<=1)){
                var old=plans.putIfAbsent(part.pos,part);
                if(old!=null&&!old.state.equals(part.state))return new Planned(null,0,"несовместимые детали общего перехода");
            }
        }
        // A later edge may not obstruct an earlier cell or cut away its support.
        var cells=new HashSet<>(road.routeCells);
        for(var part:plans.values())if(cells.contains(part.pos)||cells.contains(part.position().below().asLong()))
            return new Planned(null,0,"ограждение перекрывает выбранный проход");
        for(long cut:cuts)if(cells.contains(BlockPos.of(cut).above().asLong()))
            return new Planned(null,0,"срезка убирает опору маршрута");
        if(cuts.size()+plans.values().stream().filter(p->!p.matches(v)).count()>MAX_WORK)
            return new Planned(null,0,"общий переход с необходимым ремонтом превышает бюджет работ");
        road.blocks.addAll(plans.values());road.cuts.addAll(cuts);
        // Lighting is a separate, optional phase, after every structural piece.
        for(int n=0;n<road.routeCells.size();n+=8){
            var light=BlockPos.of(road.routeCells.get(n)).east();
            if(graph.mutable(light)&&!cells.contains(light.asLong())&&!plans.containsKey(light.asLong())
                    &&Construction.clear(v.level.getBlockState(light))&&v.level.getBlockState(light.below()).isFaceSturdy(v.level,light.below(),Direction.UP))
                road.add(light,Blocks.TORCH.defaultBlockState());
        }
        road.validate(v.state);return new Planned(road,result.cost(),"");
    }
    static BlockPos root(Village v){
        if(v.state.routeRoot!=null&&safe(v,BlockPos.of(v.state.routeRoot)))return BlockPos.of(v.state.routeRoot);
        // Real chest approaches first, then real home exits. Never start on a chest block.
        var graph=new NetworkRoutes(v,Set.of(),null);
        var anchors=new ArrayList<BlockPos>();
        v.state.storage.stream().sorted().map(BlockPos::of)
                .filter(p->v.level.hasChunkAt(p)&&v.level.getBlockState(p).getBlock() instanceof ChestBlock).forEach(anchors::add);
        anchors.addAll(v.town.doors().stream().sorted(Comparator.comparingLong(BlockPos::asLong)).toList());
        anchors.add(v.meetingPoint());
        for(var anchor:anchors)for(var direction:Direction.Plane.HORIZONTAL)for(int dy:new int[]{0,-1,1}){
            var p=anchor.relative(direction).above(dy);if(graph.walk(p))return p;
        }
        return graph.walk(v.meetingPoint())?v.meetingPoint():null;
    }
    static Set<BlockPos> workPoints(Village v){
        var points=new LinkedHashSet<BlockPos>();
        points.addAll(v.town.doors());
        for(var field:v.state.land.fields)points.add(BlockPos.of(field.entry));
        for(var building:v.state.buildings)if(building.completed&&ProductionBuildings.KINDS.contains(building.kind))
            points.add(BlockPos.of(building.origin).offset(2,1,0));
        if(v.state.mineEntrance!=null)points.add(BlockPos.of(v.state.mineEntrance));
        for(var mine:v.state.mines)if(!mine.blocked&&!mine.exhausted)points.add(BlockPos.of(mine.entrance));
        var root=root(v);
        for(long packed:v.state.storage){
            var chest=BlockPos.of(packed);
            if(!v.level.hasChunkAt(chest)||!(v.level.getBlockState(chest).getBlock() instanceof ChestBlock))continue;
            var stands=new ArrayList<BlockPos>();
            for(var side:Direction.Plane.HORIZONTAL)for(int dy:new int[]{0,-1,1}){
                var stand=chest.relative(side).above(dy);if(safe(v,stand))stands.add(stand);
            }
            stands.stream().min(Comparator.comparingDouble(p->root==null?p.distSqr(chest):p.distSqr(root))).ifPresent(points::add);
        }
        return points;
    }
    private boolean loaded(BlockPos p){return v.state.contains(p)&&v.state.contains(p.above(2))&&v.level.hasChunkAt(p);}
    private boolean allowed(BlockPos p){return loaded(p)&&!excluded.contains(column(p));}
    static long column(BlockPos p){return new BlockPos(p.getX(),0,p.getZ()).asLong();}
    private boolean protectedByOther(BlockPos p){
        if(!v.town.protects(p))return false;
        if(replacing==null)return true;
        for(var b:v.state.buildings)if(b!=replacing){
            if(b.cuts.contains(p.asLong()))return true;
            for(var part:java.util.stream.Stream.concat(b.blocks.stream(),b.foundation.stream()).toList()){
                if(part.pos==p.asLong()||part.block().getBlock() instanceof DoorBlock&&part.position().above().equals(p)
                        ||part.block().getBlock() instanceof BedBlock&&part.position().relative(part.block().getValue(BedBlock.FACING)).equals(p))return true;
            }
        }
        return false;
    }
    private boolean mutable(BlockPos p){return allowed(p)&&v.state.policy.allows("build",p)&&!protectedByOther(p)
            &&!VillageIndex.hazard(v.level.getBlockState(p))&&v.level.getBlockState(p).getFluidState().isEmpty();}
    private boolean walk(BlockPos p){return walkable.computeIfAbsent(p,at->allowed(at)&&safe(v,at));}
    static boolean safe(Village v,BlockPos p){
        if(!v.state.contains(p)||!v.state.contains(p.above(2))||!v.level.hasChunkAt(p))return false;
        if(v.safeFeet(p))return true;
        var feet=v.level.getBlockState(p);var head=v.level.getBlockState(p.above());var below=v.level.getBlockState(p.below());
        if(!openable(feet)||!below.isFaceSturdy(v.level,p.below(),Direction.UP)||VillageIndex.hazard(below)||!below.getFluidState().isEmpty())return false;
        if(feet.getBlock() instanceof DoorBlock){
            return feet.getValue(DoorBlock.HALF)==DoubleBlockHalf.LOWER&&head.getBlock()==feet.getBlock()
                    &&head.getValue(DoorBlock.HALF)==DoubleBlockHalf.UPPER&&head.getValue(DoorBlock.FACING)==feet.getValue(DoorBlock.FACING)
                    &&head.getFluidState().isEmpty()&&feet.getFluidState().isEmpty();
        }
        return clear(v,p.above())&&feet.getFluidState().isEmpty();
    }
    private static boolean openable(BlockState state){return state.is(BlockTags.WOODEN_DOORS)||state.getBlock() instanceof FenceGateBlock;}
    private static boolean clear(Village v,BlockPos p){var s=v.level.getBlockState(p);return s.getCollisionShape(v.level,p).isEmpty()&&!VillageIndex.hazard(s)&&s.getFluidState().isEmpty();}
    static boolean connectedStep(Village v,BlockPos from,BlockPos to){
        return connectedStep(v,from,to,Set.of());
    }
    /** Planned fences are considered only when deciding whether the existing paid plan can repair a route. */
    static boolean connectedStep(Village v,BlockPos from,BlockPos to,Set<Long> repairedGuards){
        int dx=to.getX()-from.getX(),dz=to.getZ()-from.getZ();
        if(Math.abs(dx)+Math.abs(dz)!=1||Math.abs(to.getY()-from.getY())>1)return false;
        Direction direction=dx>0?Direction.EAST:dx<0?Direction.WEST:dz>0?Direction.SOUTH:Direction.NORTH;
        if(!guarded(v,from,direction,repairedGuards)||!guarded(v,to,direction,repairedGuards))return false;
        for(var p:List.of(from,to)){
            var s=v.level.getBlockState(p);
            if(s.getBlock() instanceof DoorBlock&&s.getValue(DoorBlock.FACING).getAxis()!=direction.getAxis())return false;
            if(s.getBlock() instanceof FenceGateBlock&&s.getValue(FenceGateBlock.FACING).getAxis()!=direction.getAxis())return false;
        }
        double rise=v.feetPosition(to).y-v.feetPosition(from).y;
        if(Math.abs(rise)>1.05)return false;
        // Check headroom over the low end of a step as well as both standing cells.
        var low=rise>0?from:to;
        return Math.abs(rise)<.1||clear(v,low.above(2));
    }
    private static boolean guarded(Village v,BlockPos feet,Direction direction){
        return guarded(v,feet,direction,Set.of());
    }
    private static boolean guarded(Village v,BlockPos feet,Direction direction,Set<Long> repairedGuards){
        for(var side:List.of(direction.getClockWise(),direction.getCounterClockWise())){
            var p=feet.relative(side);
            if(!v.level.hasChunkAt(p))return false;
            var state=v.level.getBlockState(p);var below=v.level.getBlockState(p.below());
            if(VillageIndex.hazard(state)||VillageIndex.hazard(below))return false;
            if(repairedGuards.contains(p.asLong()))continue;
            if(!state.getCollisionShape(v.level,p).isEmpty())continue;
            if(below.getCollisionShape(v.level,p.below()).isEmpty()
                    &&v.level.getBlockState(p.below(2)).getCollisionShape(v.level,p.below(2)).isEmpty())return false;
        }
        return true;
    }
    private List<Link> neighbors(BlockPos at){
        var result=new ArrayList<Link>();
        for(var direction:Direction.Plane.HORIZONTAL){
            for(int dy:new int[]{0,1,-1}){
                var to=at.relative(direction).above(dy);
                if(walk(to)&&connectedStep(v,at,to)){
                    var ground=v.level.getBlockState(to.below());
                    double cost=knownRoad.contains(to.asLong())||ground.is(Blocks.DIRT_PATH)||ground.getBlock() instanceof StairBlock?1:2;
                    result.add(new Link(to,cost+Math.abs(dy)*.5,0,1,List.of(to),List.of(),List.of()));
                }
            }
            // New construction only joins two already safe banks. No speculative node can
            // become an unearned new start, and there is no third consecutive gap cell.
            for(int span=1;span<=2;span++){
                var to=at.relative(direction,span+1);
                if(!walk(to)||!clear(v,at.above())||!clear(v,to.above())
                        ||!guarded(v,at,direction)||!guarded(v,to,direction)
                        ||Math.abs(v.feetPosition(at).y-at.getY())>.05||Math.abs(v.feetPosition(to).y-to.getY())>.05)continue;
                var cells=new ArrayList<BlockPos>();var plans=new ArrayList<TownBuilding.Plan>();var cuts=new ArrayList<Long>();boolean good=true;
                for(int i=1;i<=span&&good;i++){
                    var cell=at.relative(direction,i);cells.add(cell);
                    if(!allowed(cell)){good=false;break;}
                    // A one/two-cell soil notch is recovered by the ordinary paid grading job.
                    for(int y=0;y<=1;y++)if(!clear(v,cell.above(y))){
                        var cut=cell.above(y);
                        if(!mutable(cut)||!VillageIndex.naturalSoil(v.level.getBlockState(cut))){good=false;break;}
                        cuts.add(cut.asLong());
                    }
                    if(!good)break;
                    var deck=cell.below();var ground=v.level.getBlockState(deck);
                    if(!ground.isFaceSturdy(v.level,deck,Direction.UP)){
                        if(!mutable(deck)||!Construction.clear(ground)){good=false;break;}
                        // Beam spans at most two cells between real, level abutments.
                        plans.add(new TownBuilding.Plan(deck,Blocks.COBBLESTONE.defaultBlockState()));
                        for(var side:List.of(direction.getClockWise(),direction.getCounterClockWise())){
                            var rail=cell.relative(side);var support=rail.below();
                            if(!mutable(rail)||!mutable(support)||!Construction.clear(v.level.getBlockState(rail))||!Construction.clear(v.level.getBlockState(support))){good=false;break;}
                            plans.add(new TownBuilding.Plan(support,Blocks.COBBLESTONE.defaultBlockState()));
                            plans.add(new TownBuilding.Plan(rail,Blocks.OAK_FENCE.defaultBlockState()));
                        }
                    }else if(VillageIndex.hazard(ground)||!ground.getFluidState().isEmpty()||!guarded(v,cell,direction))good=false;
                }
                if(good&&(!plans.isEmpty()||!cuts.isEmpty())){
                    cells.add(to);int work=plans.size()+cuts.size();
                    result.add(new Link(to,2*(span+1)+8*work,work,span+1,cells,plans,cuts));
                }
            }
        }
        return result;
    }
}
