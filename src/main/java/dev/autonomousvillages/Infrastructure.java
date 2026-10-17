package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.levelgen.Heightmap;

/** Small, priced earthworks and loaded-only routes. Reject cliffs, deep water and unknown terrain. */
final class Infrastructure {
    record Site(List<Long> cuts,List<TownBuilding.Plan> fill,int blocks){ }
    static Site site(Village v,BlockPos base,int width,int depth){
        var cuts=new ArrayList<Long>();var fill=new ArrayList<TownBuilding.Plan>();
        for(int x=-1;x<=width;x++)for(int z=-1;z<=depth;z++){
            var p=base.offset(x,0,z);if(!v.level.hasChunkAt(p)||!v.state.contains(p.above(4))||!v.state.policy.allows("build",p))return null;
            int height=v.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,p.getX(),p.getZ());
            if(Math.abs(height-base.getY())>1)return null;
            for(int y=Math.min(height,base.getY())-1;y<Math.max(height,base.getY());y++){
                var q=new BlockPos(p.getX(),y,p.getZ());var s=v.level.getBlockState(q);
                if(v.town.protects(q)||VillageIndex.hazard(s)||!s.getFluidState().isEmpty())return null;
                if(y>=base.getY()&& !VillageIndex.naturalSoil(s))return null;
                if(y>=base.getY())cuts.add(q.asLong());
                else if(y<base.getY()-1)continue;
                else if(Construction.clear(s)){
                    if(!v.level.getBlockState(q.below()).isFaceSturdy(v.level,q.below(),Direction.UP))return null;
                    fill.add(new TownBuilding.Plan(q,Blocks.COBBLESTONE.defaultBlockState()));
                }
            }
            if(!VillageIndex.naturalSoil(v.level.getBlockState(new BlockPos(p.getX(),height-1,p.getZ()))))return null;
            for(int y=0;y<=4;y++){
                var q=p.above(y);var s=v.level.getBlockState(q);
                if(v.town.protects(q)||!Construction.clear(s)&&!cuts.contains(q.asLong()))return null;
            }
        }
        if(cuts.size()+fill.size()>64)return null;
        return new Site(cuts,fill,cuts.size()+fill.size());
    }
    static TownBuilding route(Village v,BlockPos from,BlockPos to){return route(v,from,to,Set.of());}
    private static TownBuilding route(Village v,BlockPos from,BlockPos to,Set<Long> excluded){
        var first=route(v,from,to,false,excluded);return first!=null?first:route(v,from,to,true,excluded);
    }
    private static TownBuilding route(Village v,BlockPos from,BlockPos to,boolean zFirst,Set<Long> excluded){
        if(Math.abs(from.getX()-to.getX())+Math.abs(from.getZ()-to.getZ())>96)return null;
        var b=new TownBuilding("road",to);var cell=from;int gaps=0;
        for(int n=0;n<96&&!sameColumn(cell,to);n++){
            int dx=zFirst&&cell.getZ()!=to.getZ()?0:Integer.compare(to.getX(),cell.getX()),dz=dx==0?Integer.compare(to.getZ(),cell.getZ()):0;
            var column=cell.offset(dx,0,dz);if(excluded.contains(new BlockPos(column.getX(),0,column.getZ()).asLong())||!v.level.hasChunkAt(column)||!v.state.contains(column)||!v.state.policy.allows("build",column))return null;
            int surface=v.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,column.getX(),column.getZ());
            var ground=new BlockPos(column.getX(),surface-1,column.getZ());var s=v.level.getBlockState(ground);
            var feet=new BlockPos(column.getX(),surface,column.getZ());
            if(Math.abs(surface-cell.getY())>1||!s.getFluidState().isEmpty()){
                // Only a two-cell gap; deck stays at prior height and rests on bounded supports.
                if(++gaps>2)return null;feet=new BlockPos(column.getX(),cell.getY(),column.getZ());
                if(s.is(Blocks.WATER)){
                    // Two-cell beam on real bank abutments: deck above the water, never
                    // erase fluid to conjure a foundation. Reject a span without a far bank.
                    if(!Construction.clear(v.level.getBlockState(feet.below())))feet=feet.above();
                    if(Math.abs(feet.getY()-cell.getY())>1||!Construction.clear(v.level.getBlockState(feet.below())))return null;
                    if(gaps==1){
                        if(!Construction.clear(v.level.getBlockState(cell))||!v.level.getBlockState(cell.below()).isFaceSturdy(v.level,cell.below(),Direction.UP))return null;
                        b.add(cell,Blocks.COBBLESTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING,heading(dx,dz)));
                    }
                    b.add(feet.below(),Blocks.COBBLESTONE.defaultBlockState());
                }else{
                    for(int y=feet.getY()-3;y<feet.getY();y++){
                        var support=new BlockPos(feet.getX(),y,feet.getZ());var actual=v.level.getBlockState(support);
                        if(VillageIndex.hazard(actual)||v.town.protects(support)||!actual.getFluidState().isEmpty())return null;
                        if(Construction.clear(actual))b.add(support,Blocks.COBBLESTONE.defaultBlockState());
                        else if(!actual.isFaceSturdy(v.level,support,Direction.UP))return null;
                    }
                    var below=feet.below(4);if(!v.level.getBlockState(below).isFaceSturdy(v.level,below,Direction.UP))return null;
                }
                for(int side:new int[]{-1,1}){
                    var rail=feet.offset(dz!=0?side:0,0,dx!=0?side:0);
                    if(!Construction.clear(v.level.getBlockState(rail))||v.town.protects(rail))return null;
                    var support=rail.below();if(!Construction.clear(v.level.getBlockState(support)))return null;
                    b.add(support,Blocks.COBBLESTONE.defaultBlockState());b.add(rail,Blocks.OAK_FENCE.defaultBlockState());
                }
            }else{
                if(gaps>0){
                    if(v.town.protects(feet)||!Construction.clear(v.level.getBlockState(feet)))return null;
                    b.add(feet,Blocks.COBBLESTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING,heading(dx,dz).getOpposite()));
                }
                gaps=0;if(VillageIndex.hazard(s))return null;
                if(surface>cell.getY()&&!(s.getBlock() instanceof StairBlock)){
                    // Paid stairs replace the uphill ground, after its real block is recovered.
                    if(v.town.protects(ground)||!VillageIndex.naturalSoil(s))return null;
                    var stair=new TownBuilding.Plan(ground,Blocks.COBBLESTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING,dx>0?Direction.EAST:dx<0?Direction.WEST:dz>0?Direction.SOUTH:Direction.NORTH));
                    stair.replaceOwned=true;b.blocks.add(stair);
                }
            }
            if(!Construction.clear(v.level.getBlockState(feet))||!Construction.clear(v.level.getBlockState(feet.above())))return null;
            if(n%8==0){
                var light=feet.offset(dz!=0?1:0,0,dx!=0?1:0);
                if(Construction.clear(v.level.getBlockState(light))&&v.level.getBlockState(light.below()).isFaceSturdy(v.level,light.below(),Direction.UP)&&!v.town.protects(light))b.add(light,Blocks.TORCH.defaultBlockState());
            }
            cell=feet;
        }
        if(gaps>0||!sameColumn(cell,to)||Math.abs(cell.getY()-to.getY())>1)return null;
        var unique=new LinkedHashMap<Long,TownBuilding.Plan>();for(var plan:b.blocks){
            if(!v.state.contains(plan.position())||!v.state.policy.allows("build",plan.position())||!v.level.hasChunkAt(plan.position()))return null;
            var previous=unique.putIfAbsent(plan.pos,plan);if(previous!=null&&!previous.state.equals(plan.state))return null;
        }
        b.blocks=new ArrayList<>(unique.values());b.validate(v.state);return b;
    }
    private static Direction heading(int dx,int dz){return dx>0?Direction.EAST:dx<0?Direction.WEST:dz>0?Direction.SOUTH:Direction.NORTH;}
    private static boolean sameColumn(BlockPos a,BlockPos b){return a.getX()==b.getX()&&a.getZ()==b.getZ();}
    /** A storage block is a delivery target, not a walkable road origin. Use real house exits. */
    private static TownBuilding villageRoute(Village v,BlockPos to,Set<Long> excluded){
        var starts=new LinkedHashSet<BlockPos>();var meeting=v.meetingPoint();
        for(int dy:new int[]{0,-1,1}){var p=meeting.offset(0,dy,0);if(v.safeFeet(p))starts.add(p);}
        for(var door:v.town.doors()){
            var state=v.level.getBlockState(door);
            if(!(state.getBlock() instanceof DoorBlock))continue;
            var facing=state.getValue(DoorBlock.FACING);
            for(var side:List.of(facing,facing.getOpposite()))for(int dy:new int[]{0,-1,1}){
                var p=door.relative(side).offset(0,dy,0);
                if(v.state.contains(p)&&v.level.hasChunkAt(p)&&v.safeFeet(p))starts.add(p);
            }
        }
        for(var from:starts.stream().sorted(Comparator.comparingDouble(p->p.distSqr(to))).limit(32).toList()){
            var road=route(v,from,to,excluded);if(road!=null)return road;
        }
        return null;
    }
    static boolean prepare(Village v,TownBuilding b,int width,int depth){
        var base=BlockPos.of(b.origin);var site=site(v,base,width,depth);if(site==null)return false;
        var entry=base.offset(width/2,0,-2);
        var excluded=new HashSet<Long>();for(int x=-1;x<=width;x++)for(int z=-1;z<=depth;z++)excluded.add(new BlockPos(base.getX()+x,0,base.getZ()+z).asLong());
        var route=villageRoute(v,entry,excluded);if(route==null)return false;
        b.validate(v.state);
        if(!route.blocks.isEmpty()&&v.state.buildings.size()>=255)return false;
        b.cuts.addAll(site.cuts);b.foundation.addAll(site.fill);
        if(!route.blocks.isEmpty()){v.state.buildings.add(route);b.approach=route.origin;}
        return true;
    }
    private static boolean cutDone(Village v,TownBuilding b,long pos){
        return Construction.clear(v.level.getBlockState(BlockPos.of(pos)))
                ||java.util.stream.Stream.concat(b.blocks.stream(),b.foundation.stream()).anyMatch(p->p.pos==pos&&p.matches(v));
    }
    static boolean approachReady(Village v,TownBuilding b){
        return b.approach==null||v.state.buildings.stream().anyMatch(r->r.kind.equals("road")&&r.origin==b.approach&&walkable(v,r));
    }
    /** Lighting and replacing a walkable one-block soil step improve an existing path.
     * Missing bridge decks/supports still block access. Keep every improvement on the paid plan. */
    private static boolean walkable(Village v,TownBuilding road){
        return road.blocks.stream().allMatch(p->p.matches(v)||p.block().is(Blocks.TORCH)
                ||p.replaceOwned&&VillageIndex.naturalSoil(v.level.getBlockState(p.position()))&&v.safeFeet(p.position().above()));
    }
    static boolean prepared(Village v,TownBuilding b){
        return b.cuts.stream().allMatch(p->cutDone(v,b,p))
                &&b.foundation.stream().allMatch(p->p.matches(v))
                &&approachReady(v,b);
    }
    static boolean work(Resident r){
        if(!r.v.builder(r.id))return false;
        for(var b:r.v.state.buildings){
            if(!approachReady(r.v,b)||b.completed||(ProductionBuildings.KINDS.contains(b.kind)||b.tier>0)&&!SettlementEconomy.surplus(r.v))continue;
            for(long packed:b.cuts){var p=BlockPos.of(packed);if(r.v.state.policy.allows("build",p)&&!Construction.clear(r.v.level.getBlockState(p))&&r.v.ready(p,r.id)){
                if(!VillageIndex.naturalSoil(r.v.level.getBlockState(p)))continue;
                r.assign("grade",p);r.status="снимает грунт для оплаченной площадки; блоки сохраняются";return true;
            }}
            for(var p:b.foundation)if(r.v.state.policy.allows("build",p.position())&&!p.matches(r.v)&&Construction.clear(r.v.level.getBlockState(p.position()))&&r.v.ready(p.position(),r.id)){
                var step=Construction.available(r.bot(),p.step());if(step==null)continue;r.build=step;r.assign("build",p.position());return true;
            }
        }
        return false;
    }
    static boolean mineAccess(Village v,BlockPos entrance){
        var existing=v.state.buildings.stream().filter(b->b.kind.equals("road")&&b.origin==entrance.asLong()).findFirst().orElse(null);
        if(existing!=null)return existing.built(v)==existing.blocks.size();
        if(v.state.buildings.size()>=256)return false;
        var road=villageRoute(v,entrance,Set.of());if(road==null)return false;
        road.completed=road.blocks.isEmpty();v.state.buildings.add(road);v.town.invalidate();v.runtime.save();return road.completed;
    }
    static void stuck(Village v,BlockPos p){
        if(p==null||!v.state.contains(p)||!v.state.policy.allows("build",p))return;
        if(v.state.roadFailures.size()>=128&&!v.state.roadFailures.containsKey(p.asLong()))return;
        int count=v.state.roadFailures.merge(p.asLong(),1,(a,b)->Math.min(1000000,a+b));
        if(count!=3||v.state.buildings.size()>=256)return;
        var road=villageRoute(v,p,Set.of());
        if(road==null){v.event("Переход "+p.toShortString()+": нужен обход, слишком глубокий разрыв или опасный склон");return;}
        if(!road.blocks.isEmpty()){v.state.buildings.add(road);v.town.invalidate();v.runtime.save();v.event("Повторное застревание превратилось в дорожную работу");}
    }
}
