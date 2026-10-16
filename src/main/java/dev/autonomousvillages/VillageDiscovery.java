package dev.autonomousvillages;

import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.tags.PoiTypeTags;
import net.minecraft.world.entity.ai.village.poi.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.phys.*;

/** The generated structure is the primary boundary; connected POIs also cover player-built extensions. */
final class VillageDiscovery {
    record Survey(VillageState.Bounds bounds,List<BlockPos> homes,List<StructurePiece> pieces) { }
    static Survey survey(ServerLevel level,BlockPos origin) {
        Set<Structure> villageTypes=new HashSet<>();
        level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getOrThrow(StructureTags.VILLAGE).forEach(h->villageTypes.add(h.value()));
        StructureStart start=level.structureManager().startsForStructure(new ChunkPos(origin.getX()>>4,origin.getZ()>>4),villageTypes::contains).stream()
                .filter(StructureStart::isValid).filter(s->horizontalDistance(s.getBoundingBox(),origin)<=64*64)
                .min(Comparator.comparingLong(s->horizontalDistance(s.getBoundingBox(),origin))).orElse(null);
        VillageState.Bounds bounds=start==null?null:new VillageState.Bounds(start.getBoundingBox().minX()-16,start.getBoundingBox().minZ()-16,
                start.getBoundingBox().maxX()+16,start.getBoundingBox().maxZ()+16);
        var nearby=level.getPoiManager().getInRange(h->h.is(PoiTypes.HOME),origin,128,PoiManager.Occupancy.ANY)
                .map(PoiRecord::getPos).sorted(Comparator.comparingDouble(origin::distSqr)).toList();
        if(nearby.isEmpty()&&start==null)throw new IllegalArgumentException("Поселение не найдено: рядом нужны жители и кровати.");
        BlockPos anchor=nearby.isEmpty()?origin:nearby.getFirst();
        if(bounds==null)bounds=new VillageState.Bounds(anchor.getX()-24,anchor.getZ()-24,anchor.getX()+24,anchor.getZ()+24);
        List<BlockPos> homes=new ArrayList<>();Set<Long> visited=new HashSet<>();ArrayDeque<BlockPos> frontier=new ArrayDeque<>();frontier.add(anchor);
        // Range queries use already known POI sections. A connected chain has no fixed radius from the player.
        for(int nodes=0;!frontier.isEmpty()&&nodes<2048;nodes++){
            BlockPos p=frontier.removeFirst();if(!visited.add(p.asLong()))continue;
            var points=level.getPoiManager().getInRange(h->h.is(PoiTypes.HOME)||h.is(PoiTypes.MEETING)||h.is(PoiTypeTags.ACQUIRABLE_JOB_SITE),p,56,PoiManager.Occupancy.ANY).toList();
            for(PoiRecord poi:points){BlockPos q=poi.getPos();
                if(Math.abs((long)q.getX()-anchor.getX())>1800||Math.abs((long)q.getZ()-anchor.getZ())>1800)continue;
                if(poi.getPoiType().is(PoiTypes.HOME)&&!homes.contains(q))homes.add(q);
                bounds.include(q,24);if(!visited.contains(q.asLong()))frontier.add(q);
            }
        }
        final var found=bounds;
        level.getPoiManager().getInRange(h->h.is(PoiTypes.HOME),origin,Math.min(2048,Math.max(found.maxX-found.minX,found.maxZ-found.minZ)+64),PoiManager.Occupancy.ANY)
                .map(PoiRecord::getPos).filter(found::contains).forEach(p->{if(!homes.contains(p))homes.add(p);});
        return new Survey(bounds,homes,start==null?List.of():List.copyOf(start.getPieces()));
    }
    static long horizontalDistance(BoundingBox b,BlockPos p){long dx=Math.max(0,Math.max((long)b.minX()-p.getX(),(long)p.getX()-b.maxX()));
        long dz=Math.max(0,Math.max((long)b.minZ()-p.getZ(),(long)p.getZ()-b.maxZ()));return dx*dx+dz*dz;}
    static AABB area(VillageState state,int margin){
        if(state.bounds==null)return new AABB(Vec3.atCenterOf(state.center()),Vec3.atCenterOf(state.center())).inflate(state.radius,128,state.radius);
        var b=state.bounds;return new AABB(b.minX-margin,-64,b.minZ-margin,b.maxX+margin+1,320,b.maxZ+margin+1);
    }
    static void apply(VillageState state,Survey survey){
        state.bounds=survey.bounds;
        var b=state.bounds;state.x=(b.minX+b.maxX)/2;state.z=(b.minZ+b.maxZ)/2;
        if(!survey.homes.isEmpty())state.y=(int)survey.homes.stream().mapToInt(BlockPos::getY).average().orElse(state.y);
        state.radius=Math.max(24,(int)Math.ceil(Math.hypot(b.maxX-b.minX,b.maxZ-b.minZ)/2));
    }
}
