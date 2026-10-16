package dev.autonomousvillages;

import dev.navigationai.api.*;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/** Dig a real route out of a natural trap. The library's live permission guard protects buildings. */
final class TerrainRecovery {
    private static final NavigationPolicy DIG=new NavigationPolicy(true,false,true,true,64_000,1024,32,200,2);
    static boolean start(Resident resident,BlockPos destination){
        var bot=resident.bot();var v=resident.v;long now=resident.now();
        if(destination==null||now-resident.lastRecovery<200||Supplies.find(bot,s->s.is(ItemTags.PICKAXES)&&Supplies.usable(s))<0)return false;
        Vec3 position=bot.entity().position(),goal=v.feetPosition(destination);BlockPos waypoint=null;
        int surface=v.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,bot.entity().blockPosition().getX(),bot.entity().blockPosition().getZ());
        if(goal.y>=position.y-3&&ascend(resident,destination))return true;
        if(surface-position.y>4&&goal.y>=position.y-3){
            double best=Double.POSITIVE_INFINITY;
            for(int radius:new int[]{8,16,24})for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)if(dx!=0||dz!=0){
                int x=bot.entity().blockPosition().getX()+dx*radius,z=bot.entity().blockPosition().getZ()+dz*radius;
                int y=v.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z);BlockPos p=new BlockPos(x,y,z);
                var ground=v.level.getBlockState(p.below());
                if(!v.safeFeet(p)||(!VillageIndex.naturalSoil(ground)&&!ground.is(Blocks.STONE)&&!ground.is(Blocks.DEEPSLATE)))continue;
                double score=Math.abs(y-position.y)*6+Math.sqrt(p.distSqr(bot.entity().blockPosition()))+.05*p.distSqr(destination)/Math.max(1,Math.sqrt(p.distSqr(destination)));
                if(score<best){best=score;waypoint=p;}
            }
        }
        if(waypoint==null){Vec3 delta=goal.subtract(position);if(delta.lengthSqr()<2)return false;waypoint=BlockPos.containing(position.add(delta.scale(Math.min(1,20/delta.length()))));}
        resident.lastRecovery=now;resident.assign("escape",waypoint);
        if(!"escape".equals(resident.job))return false;
        resident.navigation=bot.navigator().goTo(new NavigationGoal.PositionGoal(v.feetPosition(waypoint),.35),DIG);
        resident.status="прокладывает безопасный выход из трудного места";return true;
    }
    private static boolean ascend(Resident resident,BlockPos destination){
        var v=resident.v;BlockPos start=resident.body().blockPosition();
        if(!v.safeFeet(start))return false;
        ExcavationRequest best=null;Construction.Step bestSupport=null;double score=Double.POSITIVE_INFINITY;
        int supportSlot=Supplies.find(resident.bot(),s->s.is(Items.DIRT)||s.is(Items.COBBLESTONE));
        if(supportSlot<0)supportSlot=Supplies.find(resident.bot(),s->s.is(ItemTags.PLANKS));
        if(v.state.accessBlocks.size()>=2048)supportSlot=-1;
        for(var direction:net.minecraft.core.Direction.Plane.HORIZONTAL){
            var request=new ExcavationRequest(start,direction,ExcavationMode.STAIRCASE_UP,8);int length=0;Construction.Step support=null;
            for(int n=1;n<=8;n++){
                BlockPos feet=request.positionAt(n),floor=feet.below();
                if(!v.level.hasChunkAt(feet))break;
                if(!v.level.getBlockState(floor).isCollisionShapeFullBlock(v.level,floor)){
                    // A dug-out pit has no solid first riser. Pay for one real supported step
                    // before asking the excavator to cut and climb the rest of the staircase.
                    if(n!=1||supportSlot<0||!Construction.clear(v.level.getBlockState(floor))||!v.level.getFluidState(floor).isEmpty()
                            ||!v.canWork(floor)||v.town.protects(floor)||!v.level.getBlockState(floor.below()).isFaceSturdy(v.level,floor.below(),net.minecraft.core.Direction.UP))break;
                    var material=(BlockItem)resident.bot().inventory().snapshot().slots().get(supportSlot).getItem();
                    support=new Construction.Step(floor,material.getBlock().defaultBlockState(),material);
                    if(resident.body().getEyePosition().distanceToSqr(Vec3.atCenterOf(floor))>20.25||Construction.intersectsActor(v,resident.bot(),support))break;
                }
                boolean safe=true;
                for(BlockPos cell:request.clearanceAt(n)){
                    var state=v.level.getBlockState(cell);
                    if(v.town.protects(cell)||VillageIndex.hazard(state)||!state.getFluidState().isEmpty()
                            ||state.requiresCorrectToolForDrops()&&!VillageWorkshop.hasPickFor(resident.bot(),state)){safe=false;break;}
                }
                if(!safe)break;length=n;
                if(v.safeFeet(feet))break;
            }
            if(length==0)continue;
            double distance=request.positionAt(length).distSqr(destination)-length*50;
            if(distance<score){score=distance;best=new ExcavationRequest(start,direction,ExcavationMode.STAIRCASE_UP,length);bestSupport=support;}
        }
        if(best==null)return false;
        if(bestSupport!=null){
            if(!Construction.place(v,resident.bot(),bestSupport))return false;
            v.state.accessBlocks.add(bestSupport.pos().asLong());v.runtime.save();
        }
        resident.lastRecovery=resident.now();resident.assign("escape",best.positionAt(best.length()));
        if(!"escape".equals(resident.job))return false;
        resident.excavation=resident.bot().miner().excavate(best,new ExcavationPolicy(40,1800,4,true,false));
        resident.status="прокладывает лестницу к поверхности";return true;
    }
}
