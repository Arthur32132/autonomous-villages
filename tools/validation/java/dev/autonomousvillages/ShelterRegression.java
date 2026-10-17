package dev.autonomousvillages;

import java.util.*;
import dev.navigationai.api.NavigationStatus;
import dev.navigationai.api.NavigationTask;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

final class ShelterRegression {
    private static int phase,counter;
    private static BlockPos nearBed,farBed,roomDoor,unknownBed,ironBed,ironDoor,unfinishedBed,unfinishedDoor,warehouseBed,warehouseDoor;
    private static Monster threat;
    private static NavigationTask retreatNav;
    private static boolean broken;
    private static final Map<BlockPos,BlockState> saved=new LinkedHashMap<>();
    private static List<TownBuilding> buildings=List.of();
    private static List<BlockPos> beds=List.of();
    private static long clock;

    static void begin(Village village,Resident resident,BlockPos origin){
        saved.clear();phase=1;counter=0;threat=null;broken=false;retreatNav=null;
        buildings=List.copyOf(village.state.buildings);beds=List.copyOf(village.index.beds);clock=village.level.getOverworldClockTime();
        ServerLevel level=village.level;
        resident.cancel();resident.body().setNoAi(false);resident.body().setHealth(20);VillageSelfTest.resetBag(resident.bot());
        VillageSelfTest.cmd("time set 3000");
        nearBed=origin.offset(0,0,6);farBed=origin.offset(0,0,-14);roomDoor=origin.offset(0,0,-10);
        unknownBed=origin.offset(8,0,8);ironBed=origin.offset(-8,0,0);ironDoor=origin.offset(-7,0,0);
        unfinishedBed=origin.offset(-12,0,0);unfinishedDoor=origin.offset(-11,0,0);
        warehouseBed=origin.offset(-16,0,0);warehouseDoor=origin.offset(-15,0,0);
        buildRoom(level,origin);
        bedAt(level,farBed);doorAt(level,roomDoor,Blocks.OAK_DOOR,true);
        put(level,nearBed,Blocks.BED.white().defaultBlockState().setValue(BedBlock.FACING,Direction.SOUTH).setValue(BedBlock.PART,BedPart.FOOT));
        put(level,nearBed.south(),Blocks.AIR.defaultBlockState());
        doorAt(level,nearBed.offset(1,0,0),Blocks.OAK_DOOR,false);
        bedAt(level,unknownBed);
        bedAt(level,ironBed);doorAt(level,ironDoor,Blocks.IRON_DOOR,false);
        bedAt(level,unfinishedBed);doorAt(level,unfinishedDoor,Blocks.OAK_DOOR,false);
        bedAt(level,warehouseBed);doorAt(level,warehouseDoor,Blocks.OAK_DOOR,false);

        var damaged=new TownBuilding("native_house",nearBed);damaged.completed=true;
        damaged.add(nearBed,bedFoot());
        damaged.add(nearBed.offset(1,0,0),level.getBlockState(nearBed.offset(1,0,0)));
        damaged.add(nearBed.offset(-1,0,0),Blocks.OAK_PLANKS.defaultBlockState());
        var intact=new TownBuilding("native_house",farBed);intact.completed=true;
        for(int offsetX=-2;offsetX<=2;offsetX++)for(int offsetZ=-16;offsetZ<=-10;offsetZ++)for(int offsetY=0;offsetY<=3;offsetY++)
            if(shell(offsetX,offsetY,offsetZ)&&!(offsetX==0&&offsetZ==-10&&offsetY<2))
                intact.add(origin.offset(offsetX,offsetY,offsetZ),Blocks.OAK_PLANKS.defaultBlockState());
        intact.add(farBed,bedFoot());
        intact.add(roomDoor,level.getBlockState(roomDoor));
        var iron=new TownBuilding("native_house",ironBed);iron.completed=true;
        iron.add(ironBed,bedFoot());iron.add(ironDoor,Blocks.IRON_DOOR.defaultBlockState());
        var unfinished=new TownBuilding("native_house",unfinishedBed);unfinished.completed=false;
        unfinished.add(unfinishedBed,bedFoot());unfinished.add(unfinishedDoor,Blocks.OAK_DOOR.defaultBlockState());
        var warehouse=new TownBuilding("warehouse",warehouseBed);warehouse.completed=true;
        warehouse.add(warehouseBed,bedFoot());warehouse.add(warehouseDoor,Blocks.OAK_DOOR.defaultBlockState());
        village.state.buildings.clear();
        village.state.buildings.add(damaged);village.state.buildings.add(intact);village.state.buildings.add(iron);
        village.state.buildings.add(unfinished);village.state.buildings.add(warehouse);
        village.index.beds=List.of(nearBed,farBed);village.town.invalidate();
        resident.body().snapTo(Vec3.atBottomCenterOf(origin.offset(0,0,-2)),0,0);resident.body().setDeltaMovement(Vec3.ZERO);

        Vec3 from=resident.body().position();
        VillageSelfTest.require(!village.town.knownShelter(nearBed),"a house with a missing bed half is not a shelter");
        bedAt(level,nearBed);
        VillageSelfTest.require(!village.town.knownShelter(nearBed),"the nearer house remains unsafe with both bed halves restored because its planned wall is missing");
        VillageSelfTest.require(village.town.knownShelter(farBed),"the complete intact house is a shelter");
        doorAt(level,roomDoor,Blocks.IRON_DOOR,true);
        VillageSelfTest.require(!village.town.knownShelter(farBed),"a planned wooden door replaced by a real iron door is not usable shelter access");
        doorAt(level,roomDoor,Blocks.OAK_DOOR,true);
        VillageSelfTest.require(village.town.knownShelter(farBed),"restoring the actual wooden entrance restores the intact shelter");
        VillageSelfTest.require(!village.town.knownShelter(unknownBed),"an open unregistered bed is not a shelter");
        VillageSelfTest.require(!village.town.knownShelter(ironBed),"an otherwise intact house with only an iron door is not a shelter");
        VillageSelfTest.require(!village.town.knownShelter(unfinishedBed),"an unfinished project is not a shelter");
        VillageSelfTest.require(!village.town.knownShelter(warehouseBed),"a warehouse is not a shelter");
        VillageSelfTest.pass("known_shelter_requires_usable_bed_halves_a_wooden_door_and_a_completed_non_warehouse_house");

        Vec3 blocker=Vec3.atBottomCenterOf(origin.offset(0,0,-3)),offCorridor=Vec3.atBottomCenterOf(origin.offset(6,0,-2));
        VillageSelfTest.require(Resident.shelteredStand(from,blocker,List.of(farBed),bed->village.town.knownShelter(bed),bed->village.stand(bed,from,2.5,true))==null,
                "an intact house 5 blocks from the enemy still yields no refuge when the approach crosses the enemy");
        var clear=Resident.shelteredStand(from,offCorridor,List.of(farBed),bed->village.town.knownShelter(bed),bed->village.stand(bed,from,2.5,true));
        VillageSelfTest.require(clear!=null&&farBed.equals(clear.bed()),"the same intact house becomes usable once the enemy leaves the approach");
        VillageSelfTest.pass("refuge_selection_tests_the_actual_stand_so_a_safe_bed_without_a_safe_approach_is_rejected");
    }

    static boolean tick(Village village,Resident resident,BlockPos origin){
        counter++;
        if(phase==1){
            if(counter==1){threat=spawn(village,resident,origin.offset(0,0,-3));VillageSelfTest.require(threat!=null,"blocked-approach hostile created");}
            resident.tick(false);
            if(resident.fleeing){
                VillageSelfTest.require(resident.shelter==null,"an intact house blocked by the enemy must not be forced; shelter="+resident.shelter);
                VillageSelfTest.require(resident.target!=null&&resident.target.distSqr(farBed)>36,"bounded escape stays clear of the enemy-blocked house; target="+resident.target);
                VillageSelfTest.require(resident.body().isAlive(),"resident survives the blocked-approach retreat");
                VillageSelfTest.pass("no_safe_home_falls_back_to_a_bounded_escape_instead_of_a_corridor_blocked_intact_house");
                endThreat(village,resident,origin);phase=2;counter=0;return false;
            }
            VillageSelfTest.require(counter<200,"no-safe-home retreat was not reached in time");
            return false;
        }
        if(phase==2){
            if(counter==1)threat=spawn(village,resident,origin.offset(6,0,-2));
            resident.tick(false);
            if(resident.fleeing&&"home".equals(resident.job)&&resident.shelter!=null){
                VillageSelfTest.require(farBed.equals(resident.shelter),"daytime retreat selects the intact known house; shelter="+resident.shelter);
                VillageSelfTest.require(resident.navigation!=null&&!resident.navigation.completion().isDone(),"retreat navigation is active before the enemy breaks line of sight");
                discardThreat(village);phase=3;counter=0;return false;
            }
            VillageSelfTest.require(counter<300,"daytime retreat never selected the intact house");
            return false;
        }
        if(phase==3){
            resident.tick(false);
            boolean walking=resident.navigation!=null&&!resident.navigation.completion().isDone();
            if(walking)VillageSelfTest.require(resident.fleeing&&farBed.equals(resident.shelter),
                    "retreat to the intact house survives losing sight of the enemy until the door closes");
            boolean lower=!village.level.getBlockState(roomDoor).getValue(DoorBlock.OPEN);
            boolean upper=!village.level.getBlockState(roomDoor.above()).getValue(DoorBlock.OPEN);
            if(lower&&upper&&resident.body().getBlockZ()<roomDoor.getZ()){
                VillageSelfTest.require(resident.body().isAlive(),"resident is inside and alive when the daytime door closes; pos="+resident.body().position());
                VillageSelfTest.pass("daytime_retreat_walks_in_and_physically_closes_the_house_door_after_the_enemy_breaks_line_of_sight");
                doorAt(village.level,roomDoor,Blocks.OAK_DOOR,true);resident.cancel();
                resident.body().snapTo(Vec3.atBottomCenterOf(origin.offset(0,0,-2)),0,0);resident.body().setDeltaMovement(Vec3.ZERO);
                phase=4;counter=0;return false;
            }
            VillageSelfTest.require(counter<400,"daytime door never closed after the crossing");
            return false;
        }
        if(phase==4){
            if(counter==1)threat=spawn(village,resident,origin.offset(6,0,-2));
            resident.tick(false);
            if(!broken&&resident.fleeing&&farBed.equals(resident.shelter)){
                retreatNav=resident.navigation;
                VillageSelfTest.require(retreatNav!=null&&!retreatNav.completion().isDone(),"shelter destruction starts during an actual pending retreat");
                discardThreat(village);
                village.level.setBlock(roomDoor,Blocks.AIR.defaultBlockState(),3);
                village.level.setBlock(roomDoor.above(),Blocks.AIR.defaultBlockState(),3);
                broken=true;return false;
            }
            if(broken&&!resident.fleeing&&resident.shelter==null&&resident.job==null){
                VillageSelfTest.require(retreatNav.completion().isDone()&&retreatNav.status()==NavigationStatus.CANCELLED,
                        "the old route is actually cancelled after shelter destruction without a visible enemy");
                VillageSelfTest.require(resident.body().isAlive(),"resident survived the shelter breaking during an active retreat");
                VillageSelfTest.pass("a_known_shelter_that_breaks_during_an_active_daytime_retreat_is_abandoned");
                cleanup(village,resident,origin);return true;
            }
            VillageSelfTest.require(counter<400,"active retreat never reacted to its broken shelter");
            return false;
        }
        return true;
    }

    private static BlockState bedFoot(){
        return Blocks.BED.white().defaultBlockState().setValue(BedBlock.FACING,Direction.SOUTH).setValue(BedBlock.PART,BedPart.FOOT);
    }
    private static boolean shell(int offsetX,int offsetY,int offsetZ){
        return offsetX==-2||offsetX==2||offsetZ==-16||offsetZ==-10||offsetY==3;
    }
    private static Monster spawn(Village village,Resident resident,BlockPos at){
        VillageSelfTest.cmd("difficulty normal");
        var monster=EntityTypes.HUSK.create(village.level,EntitySpawnReason.COMMAND);
        if(monster==null)return null;
        monster.setNoAi(true);monster.setPersistenceRequired();
        monster.snapTo(Vec3.atBottomCenterOf(at),0,0);monster.setTarget(resident.body());
        village.level.addFreshEntity(monster);
        return monster;
    }
    private static void discardThreat(Village village){
        if(threat!=null){threat.discard();threat=null;}
        VillageSelfTest.cmd("difficulty peaceful");
    }
    private static void endThreat(Village village,Resident resident,BlockPos origin){
        discardThreat(village);resident.cancel();
        resident.body().snapTo(Vec3.atBottomCenterOf(origin.offset(0,0,-2)),0,0);resident.body().setDeltaMovement(Vec3.ZERO);
    }
    private static void cleanup(Village village,Resident resident,BlockPos origin){
        discardThreat(village);resident.cancel();
        saved.forEach((position,state)->village.level.setBlock(position,state,3));
        village.state.buildings.clear();village.state.buildings.addAll(buildings);village.index.beds=beds;village.town.invalidate();
        VillageSelfTest.cmd("time set "+clock);VillageSelfTest.resetBag(resident.bot());
        resident.body().snapTo(Vec3.atBottomCenterOf(origin.offset(14,0,16)),0,0);resident.body().setDeltaMovement(Vec3.ZERO);
        resident.body().setHealth(20);
    }
    private static void buildRoom(ServerLevel level,BlockPos origin){
        for(int offsetX=-2;offsetX<=2;offsetX++)for(int offsetZ=-16;offsetZ<=-10;offsetZ++)for(int offsetY=0;offsetY<=3;offsetY++)
            put(level,origin.offset(offsetX,offsetY,offsetZ),shell(offsetX,offsetY,offsetZ)?Blocks.OAK_PLANKS.defaultBlockState():Blocks.AIR.defaultBlockState());
    }
    private static void bedAt(ServerLevel level,BlockPos foot){
        BlockState state=bedFoot();
        put(level,foot,state);put(level,foot.south(),state.setValue(BedBlock.PART,BedPart.HEAD));
    }
    private static void doorAt(ServerLevel level,BlockPos lower,Block door,boolean open){
        var state=door.defaultBlockState().setValue(DoorBlock.FACING,Direction.SOUTH).setValue(DoorBlock.OPEN,open);
        put(level,lower,state);put(level,lower.above(),state.setValue(DoorBlock.HALF,DoubleBlockHalf.UPPER));
    }
    private static void put(ServerLevel level,BlockPos position,BlockState state){
        saved.putIfAbsent(position,level.getBlockState(position));level.setBlock(position,state,3);
    }
}
