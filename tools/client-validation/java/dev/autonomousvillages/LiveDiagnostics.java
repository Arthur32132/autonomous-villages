package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.BlockPos;
import dev.navigationai.runtime.VillagerAccess;

/** Read-only diagnostics for the isolated observer; no mutation of production AI or terrain. */
final class LiveDiagnostics {
    static List<String> mineOres(Village v){
        var found=new LinkedHashSet<String>();
        for(var mine:v.state.mines){
            var request=new dev.navigationai.api.ExcavationRequest(BlockPos.of(mine.entrance),net.minecraft.core.Direction.from2DDataValue(mine.direction),dev.navigationai.api.ExcavationMode.STAIRCASE_DOWN,Math.max(1,mine.length));
            for(int step=0;step<=mine.length;step++)for(BlockPos cell:request.clearanceAt(step))for(var side:net.minecraft.core.Direction.values()){
                BlockPos p=cell.relative(side);if(!v.level.hasChunkAt(p))continue;
                var state=v.level.getBlockState(p);if(state.getBlock().toString().contains("_ore"))found.add(p.toShortString()+" "+state);
            }
        }
        return List.copyOf(found);
    }
    private static Object field(Object object,String name)throws ReflectiveOperationException{var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    static List<Map<String,Object>> workers(Village v){
        var result=new ArrayList<Map<String,Object>>();
        for(var r:v.workers.values())if(r.bot()!=null){
            var row=new LinkedHashMap<String,Object>();result.add(row);var entity=r.body();
            row.put("uuid",r.id.toString());row.put("job",String.valueOf(r.job));row.put("health",entity.getHealth());row.put("frozen",entity.getTicksFrozen());
            row.put("role",v.role(r.id));row.put("status",r.status);row.put("sleeping",entity.isSleeping());
            row.put("nativeFood",Supplies.copy(entity.getInventory()).stream().filter(s->!s.isEmpty()).map(Object::toString).toList());
            row.put("air",entity.getAirSupply());row.put("submerged",entity.isEyeInFluid(net.minecraft.tags.FluidTags.WATER));
            row.put("pos",entity.position().toString());row.put("grounded",entity.onGround());row.put("velocity",entity.getDeltaMovement().toString());row.put("nav",r.bot().navigator().status().toString());
            row.put("target",String.valueOf(r.target));row.put("failure",r.lastFailure);row.put("want",v.town.wantedPlans(r).stream().map(p->p.block().toString()).toList());
            row.put("tools",r.bot().inventory().snapshot().slots().stream().filter(Supplies::tool).map(s->s+" durability="+(s.getMaxDamage()-s.getDamageValue())).toList());
            row.put("bag",r.bot().inventory().snapshot().slots().stream().filter(s->!s.isEmpty()).map(Object::toString).toList());
            row.put("collectingUntil",r.collectingUntil);
            row.put("excavation",r.excavation==null?"none":r.excavation.status()+" segments="+r.excavation.completedSegments()+" blocks="+r.excavation.brokenBlocks());
            row.put("nearbySheep",v.level.getEntitiesOfClass(net.minecraft.world.entity.animal.sheep.Sheep.class,entity.getBoundingBox().inflate(64))
                    .stream().map(s->s.position()+" color="+s.getColor()+" ready="+s.readyForShearing()).toList());
            try{
                var nav=r.bot().navigator();int i=(int)field(nav,"pathIndex");var path=(List<?>)field(nav,"path");
                int low=Math.min(path.size(),Math.max(0,i-1)),high=Math.max(low,Math.min(path.size(),i+4));
                row.put("pathIndex",i);row.put("pathSize",path.size());row.put("nextPath",path.subList(low,high).toString());
                var controller=((VillagerAccess)entity).navigationAiController();row.put("jump",field(controller,"jumpInput"));row.put("forward",field(controller,"forwardInput"));
                var blocks=new LinkedHashMap<String,String>();
                for(BlockPos p:BlockPos.betweenClosed(entity.blockPosition().offset(-1,-1,-1),entity.blockPosition().offset(1,3,1)))
                    if(!v.level.getBlockState(p).isAir())blocks.put(p.toShortString(),v.level.getBlockState(p).toString());
                row.put("nearBlocks",blocks);
            }catch(ReflectiveOperationException|RuntimeException error){row.put("debugUnavailable",error.toString());}
        }
        return result;
    }
}
