package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.levelgen.Heightmap;

/** Small, priced earthworks and loaded-only routes. Reject cliffs, deep water and unknown terrain. */
final class Infrastructure {
    record Site(List<Long> cuts,List<TownBuilding.Plan> fill,int blocks){ }
    static Site site(Village v,BlockPos base,int width,int depth){
        var footprint=footprint(base,width,depth);
        if(v.state.buildings.stream().filter(b->b.kind.equals("road")).flatMap(b->b.routeCells.stream())
                .map(BlockPos::of).anyMatch(p->footprint.contains(NetworkRoutes.column(p))))return null;
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
    static TownBuilding route(Village v,BlockPos from,BlockPos to){
        return NetworkRoutes.plan(v,from,to,Set.of()).road();
    }
    static Set<Long> footprint(BlockPos base,int width,int depth){
        var excluded=new HashSet<Long>();
        for(int x=-1;x<=width;x++)for(int z=-1;z<=depth;z++)excluded.add(NetworkRoutes.column(base.offset(x,0,z)));
        return excluded;
    }
    static NetworkRoutes.Planned connection(Village v,BlockPos base,int width,int depth){
        var entry=base.offset(width/2,0,-2);
        // The exterior bank may be one block above the graded floor. Do not use a
        // lower feet cell that the blueprint's paid entry stairs will later occupy.
        if(!NetworkRoutes.safe(v,entry)&&NetworkRoutes.safe(v,entry.above()))entry=entry.above();
        return NetworkRoutes.plan(v,NetworkRoutes.root(v),entry,footprint(base,width,depth));
    }
    static boolean coolingDown(Village v,BlockPos target){
        var failure=v.state.accessFailures.get(target.asLong());
        return failure!=null&&!failure.resolved&&v.level.getGameTime()<failure.retryAt;
    }
    static void reject(Village v,BlockPos target,String reason){
        if(!v.state.contains(target))return;
        var failures=v.state.accessFailures;
        if(!failures.containsKey(target.asLong())&&failures.size()>=128)failures.remove(failures.keySet().iterator().next());
        var failure=failures.computeIfAbsent(target.asLong(),ignored->new VillageState.AccessFailure());
        failure.reason=reason.length()>240?reason.substring(0,240):reason;failure.attempts=Math.min(1000000,failure.attempts+1);
        failure.retryAt=v.level.getGameTime()+600;failure.resolved=false;
    }
    private static void connected(Village v,TownBuilding road){
        if(v.state.routeRoot==null||!NetworkRoutes.safe(v,BlockPos.of(v.state.routeRoot)))v.state.routeRoot=road.routeStart;
        resolve(v,road.origin);
    }
    private static void resolve(Village v,long position){
        var failure=v.state.accessFailures.get(position);
        if(failure!=null){failure.resolved=true;failure.retryAt=0;}
    }
    static boolean prepare(Village v,TownBuilding b,int width,int depth){
        var base=BlockPos.of(b.origin);var site=site(v,base,width,depth);
        if(site==null){reject(v,base,"площадка требует недопустимых земляных работ или занята");return false;}
        var result=connection(v,base,width,depth);
        if(!result.found()){reject(v,base,result.failure());return false;}
        var route=result.road();b.validate(v.state);
        // Even a natural path is persistent infrastructure: validate its clearance after changes.
        var existing=v.state.buildings.stream().filter(r->r.kind.equals("road")&&r.origin==route.origin).findFirst().orElse(null);
        if(existing==null&&v.state.buildings.size()>=255){reject(v,base,"достигнут лимит проектов");return false;}
        b.cuts.addAll(site.cuts);b.foundation.addAll(site.fill);
        if(existing==null){v.state.buildings.add(route);connected(v,route);}
        else {
            existing.blocks=route.blocks;existing.cuts=route.cuts;existing.routeCells=route.routeCells;existing.routeStart=route.routeStart;
            existing.completed=false;connected(v,route);
        }
        resolve(v,b.origin);
        b.approach=route.origin;v.town.invalidate();return true;
    }
    private static boolean cutDone(Village v,TownBuilding b,long pos){
        return Construction.clear(v.level.getBlockState(BlockPos.of(pos)))
                ||java.util.stream.Stream.concat(b.blocks.stream(),b.foundation.stream()).anyMatch(p->p.pos==pos&&p.matches(v));
    }
    static boolean approachReady(Village v,TownBuilding b){
        return b.approach==null||v.state.buildings.stream().anyMatch(r->r.kind.equals("road")&&r.origin==b.approach&&walkable(v,r));
    }
    static boolean optional(TownBuilding.Plan p){return p.block().is(Blocks.TORCH);}
    static boolean walkable(Village v,TownBuilding road){
        if(road.routeCheckedTick!=v.level.getGameTime()){
            road.routeReady=checkWalkable(v,road);road.routeCheckedTick=v.level.getGameTime();
        }
        return road.routeReady;
    }
    private static boolean checkWalkable(Village v,TownBuilding road){
        if(!rooted(v,road))return false;
        if(!road.cuts.stream().allMatch(p->cutDone(v,road,p)))return false;
        if(!road.blocks.stream().allMatch(p->p.matches(v)||optional(p)
                ||p.replaceOwned&&VillageIndex.naturalSoil(v.level.getBlockState(p.position()))&&NetworkRoutes.safe(v,p.position().above())))return false;
        if(road.routeCells.isEmpty())return false; // legacy routes must be surveyed by maintain() first
        BlockPos previous=null;
        for(long packed:road.routeCells){
            var cell=BlockPos.of(packed);
            if(!NetworkRoutes.safe(v,cell)||previous!=null&&!NetworkRoutes.connectedStep(v,previous,cell))return false;
            previous=cell;
        }
        return true;
    }
    static String accessReason(Village v,TownBuilding b){
        if(approachReady(v,b))return "доступен";
        var failure=b.approach==null?null:v.state.accessFailures.get(b.approach);
        return failure!=null&&!failure.resolved?failure.reason:"ждёт настил, ограждение или расчистку реального прохода";
    }
    private static boolean repairable(Village v,TownBuilding road){
        if(road.routeCells.isEmpty()||!rooted(v,road))return false;
        boolean pending=road.cuts.stream().anyMatch(p->!cutDone(v,road,p))
                ||road.blocks.stream().anyMatch(p->!optional(p)&&!p.matches(v));
        if(!pending)return false;
        for(long cut:road.cuts){var p=BlockPos.of(cut);if(!Construction.clear(v.level.getBlockState(p))&&!VillageIndex.naturalSoil(v.level.getBlockState(p)))return false;}
        for(var part:road.blocks)if(!part.matches(v)&&!optional(part)&&!Construction.clear(v.level.getBlockState(part.position()))
                &&!(part.replaceOwned&&VillageIndex.naturalSoil(v.level.getBlockState(part.position()))))return false;
        var repairedGuards=new HashSet<Long>();
        for(var part:road.blocks)if(part.block().getBlock() instanceof FenceBlock)repairedGuards.add(part.pos);
        BlockPos previous=null;
        for(long packed:road.routeCells){
            var p=BlockPos.of(packed);
            if(previous!=null&&NetworkRoutes.safe(v,previous)&&NetworkRoutes.safe(v,p)
                    &&!NetworkRoutes.connectedStep(v,previous,p,repairedGuards))return false;
            previous=p;
            if(NetworkRoutes.safe(v,p))continue;
            boolean floor=v.level.getBlockState(p.below()).isFaceSturdy(v.level,p.below(),Direction.UP)
                    ||road.blocks.stream().anyMatch(part->part.pos==p.below().asLong()&&!optional(part));
            boolean clear=true;
            for(var head:List.of(p,p.above()))if(!Construction.clear(v.level.getBlockState(head))&&!road.cuts.contains(head.asLong()))clear=false;
            if(!floor||!clear)return false;
        }
        return true;
    }
    private static boolean rooted(Village v,TownBuilding road){
        var root=NetworkRoutes.root(v);
        return root!=null&&Objects.equals(road.routeStart,root.asLong());
    }
    /** Repair from actual terrain, with the same bounded search as new sites. One route per cycle. */
    static void maintain(Village v,boolean expandNetwork){
        for(var road:v.state.buildings)if(road.kind.equals("road")&&road.completed&&!walkable(v,road))road.completed=false;
        for(var road:List.copyOf(v.state.buildings))if(road.kind.equals("road")
                &&(road.routeCells.isEmpty()||!walkable(v,road)&&!repairable(v,road))&&!coolingDown(v,BlockPos.of(road.origin))){
            var excluded=new HashSet<Long>();
            for(var building:v.state.buildings)if(building.approach!=null&&building.approach==road.origin){
                int size=Set.of("warehouse","field","sheep_pen").contains(building.kind)?7:5;
                excluded.addAll(footprint(BlockPos.of(building.origin),size,size));
            }
            var result=NetworkRoutes.plan(v,NetworkRoutes.root(v),BlockPos.of(road.origin),excluded);
            if(!result.found()){
                reject(v,BlockPos.of(road.origin),result.failure());
                var failure=v.state.accessFailures.get(road.origin);
                if(failure!=null&&failure.attempts>=3)relocateUnstarted(v,road,result.failure());
                v.runtime.save();return;
            }
            var replacement=result.road();
            // Drop obsolete intent only. Previously paid blocks and recovered materials stay physical.
            road.blocks=replacement.blocks;road.cuts=replacement.cuts;road.routeCells=replacement.routeCells;road.routeStart=replacement.routeStart;
            road.completed=false;connected(v,road);v.town.invalidate();v.runtime.save();return;
        }
        if(!expandNetwork||v.state.buildings.size()>=250)return;
        for(var point:NetworkRoutes.workPoints(v)){
            if(!v.state.contains(point)||coolingDown(v,point)
                    ||v.state.buildings.stream().anyMatch(b->b.kind.equals("road")&&b.origin==point.asLong()))continue;
            var result=NetworkRoutes.plan(v,NetworkRoutes.root(v),point,Set.of());
            if(!result.found())reject(v,point,result.failure());
            else {var road=result.road();v.state.buildings.add(road);connected(v,road);v.town.invalidate();}
            v.runtime.save();return; // at most one existing work point per service cycle
        }
    }
    private static void relocateUnstarted(Village v,TownBuilding road,String reason){
        for(var building:List.copyOf(v.state.buildings))if(building.approach!=null&&building.approach==road.origin
                &&!building.completed&&building.tier==0&&!building.kind.equals("native_house")
                &&building.blocks.stream().noneMatch(p->p.matches(v))&&building.foundation.stream().noneMatch(p->p.matches(v))
                &&building.cuts.stream().noneMatch(p->Construction.clear(v.level.getBlockState(BlockPos.of(p))))){
            // Cancel only intent. Kits/cargo remain in inventories and follow the ordinary order-recovery path.
            for(var resident:v.workers.values())if(resident.townPlan!=null&&building.blocks.contains(resident.townPlan)
                    ||"grade".equals(resident.job)&&resident.target!=null&&building.cuts.contains(resident.target.asLong()))resident.cancel();
            Orders.cancel(v.state.orders,building.origin,"");
            v.state.buildings.remove(building);reject(v,BlockPos.of(building.origin),"смена площадки: "+reason);
            if(building.kind.equals("house"))v.state.completedShelters=Math.max(0,v.state.completedShelters-1);
            if(building.kind.equals("field"))v.state.land.fields.removeIf(f->f.entry==BlockPos.of(building.origin).offset(3,0,-1).asLong());
            if(building.kind.equals("sheep_pen")&&Objects.equals(v.state.sheepFarm.base,building.origin))v.state.sheepFarm.base=null;
            v.event("Площадка "+BlockPos.of(building.origin).toShortString()+" отклонена: "+reason);
        }
        if(v.state.buildings.stream().noneMatch(b->b.approach!=null&&b.approach==road.origin))v.state.buildings.remove(road);
        v.town.invalidate();
    }
    static boolean prepared(Village v,TownBuilding b){
        return groundReady(v,b)&&approachReady(v,b)&&(!b.kind.equals("road")||walkable(v,b));
    }
    static boolean groundReady(Village v,TownBuilding b){
        return b.cuts.stream().allMatch(p->cutDone(v,b,p))
                &&b.foundation.stream().allMatch(p->p.matches(v));
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
        if(existing!=null)return walkable(v,existing);
        if(v.state.buildings.size()>=256||coolingDown(v,entrance))return false;
        var result=NetworkRoutes.plan(v,NetworkRoutes.root(v),entrance,Set.of());
        if(!result.found()){reject(v,entrance,result.failure());v.runtime.save();return false;}
        var road=result.road();connected(v,road);v.state.buildings.add(road);v.town.invalidate();v.runtime.save();return walkable(v,road);
    }
    static void stuck(Village v,BlockPos p){
        if(p==null||!v.state.contains(p))return;
        if(v.state.roadFailures.size()>=128&&!v.state.roadFailures.containsKey(p.asLong()))return;
        int count=v.state.roadFailures.merge(p.asLong(),1,(a,b)->Math.min(1000000,a+b));
        if(count<3||count%3!=0||v.state.buildings.size()>=256||coolingDown(v,p))return;
        var result=NetworkRoutes.plan(v,NetworkRoutes.root(v),p,Set.of());
        if(!result.found()){reject(v,p,result.failure());v.runtime.save();return;}
        var road=result.road();
        var existing=v.state.buildings.stream().filter(b->b.kind.equals("road")&&b.origin==p.asLong()).findFirst().orElse(null);
        if(existing==null)v.state.buildings.add(road);
        else {existing.blocks=road.blocks;existing.cuts=road.cuts;existing.routeCells=road.routeCells;existing.routeStart=road.routeStart;existing.completed=false;}
        connected(v,road);v.town.invalidate();v.runtime.save();
        v.event("Повторное застревание: перепланирован доступный маршрут");
    }
}
