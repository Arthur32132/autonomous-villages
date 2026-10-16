package dev.autonomousvillages;

import dev.navigationai.NavigationAiMod;
import dev.navigationai.api.BotKind;
import java.nio.file.*;
import java.util.UUID;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.slf4j.*;

/** Runs only in a disposable copied client profile; never included in the release. */
public final class IntegratedServerProbe implements ClientModInitializer {
    private static final Logger LOG=LoggerFactory.getLogger("integrated_regression");
    private volatile int phase,serverTicks,bodyId=-1;
    private volatile boolean firstPassed,secondPassed,stopped,failed;
    private MinecraftServer firstServer;
    private UUID body;
    private long started=System.nanoTime(),finishAt;
    private int firstTitleTicks;
    private static void require(boolean b,String message){if(!b)throw new IllegalStateException(message);}
    @Override public void onInitializeClient(){
        if(Boolean.getBoolean("avillage.test.colony")){new SettlementSimulationProbe().onInitializeClient();return;}
        ServerLifecycleEvents.SERVER_STOPPED.register(s->{if(s==firstServer)stopped=true;});
        ServerTickEvents.END_SERVER_TICK.register(this::serverTick);
        ClientTickEvents.END_CLIENT_TICK.register(this::clientTick);
    }
    private void fail(Throwable error){
        if(failed)return;failed=true;LOG.error("CLIENT_TEST FAIL",error);
        write("{\"success\":false,\"error\":\"See client log\"}");
        Minecraft.getInstance().execute(()->Minecraft.getInstance().stop());
    }
    private void write(String data){
        try{Files.writeString(Path.of("client-result.json"),data);}catch(Exception e){LOG.error("Writing result",e);}
    }
    private void clientTick(Minecraft mc){
        if(failed)return;
        try{
            if(System.nanoTime()-started>300_000_000_000L)throw new IllegalStateException("Client regression timed out, phase="+phase);
            if(phase==0&&mc.gui.screen() instanceof TitleScreen&&++firstTitleTicks>20){
                phase=1;
                var settings=new LevelSettings("Integrated regression",GameType.CREATIVE,
                        new LevelSettings.DifficultySettings(Difficulty.PEACEFUL,false,false),true,WorldDataConfiguration.DEFAULT);
                mc.createWorldOpenFlows().createFreshLevel("IntegratedSmoke",settings,new WorldOptions(8675309L,true,false),
                        WorldPresets::createNormalWorldDimensions,new TitleScreen());
            }
            if(mc.level!=null&&mc.player!=null&&mc.gui.screen()!=null)mc.gui.setScreen(null);
            if(phase==1&&firstPassed&&mc.level!=null&&mc.level.getEntity(bodyId) instanceof Villager){
                LOG.info("CLIENT_TEST PASS fresh_world_native_villager_rendered");
                phase=2;mc.disconnectFromWorld(Component.literal("Regression reload"));
            }
            if(phase==2&&stopped&&mc.gui.screen() instanceof TitleScreen){
                phase=3;serverTicks=0;
                mc.createWorldOpenFlows().openWorld("IntegratedSmoke",()->fail(new IllegalStateException("World reopening cancelled")));
            }
            if(phase==3&&secondPassed&&mc.level!=null&&mc.level.getEntity(bodyId) instanceof Villager){
                phase=4;finishAt=System.nanoTime()+5_000_000_000L;
                net.minecraft.client.Screenshot.grab(mc.gameDirectory,"integrated-reload.png",mc.gameRenderer.mainRenderTarget(),1,message->LOG.info("CLIENT_TEST FRAME {}",message));
                write("{\"success\":true,\"freshWorld\":true,\"nativeVillagerRendered\":true,\"reloadBackpack\":17,\"activeVillageResumed\":true}");
                LOG.info("CLIENT_TEST READY_SCREENSHOT fresh_world_and_reload_passed");
            }
            if(phase==4&&System.nanoTime()>finishAt){LOG.info("CLIENT_TEST DONE");mc.stop();}
        }catch(Throwable error){fail(error);}
    }
    private void serverTick(MinecraftServer server){
        if(failed||server.getPlayerList().getPlayerCount()==0)return;
        try{
            if(phase==1){
                if(++serverTicks==10){
                    firstServer=server;
                    require(NavigationAiMod.runtime(server)!=null,"Navigation AI absent in integrated server");
                    var level=server.overworld();
                    BlockPos origin=server.getPlayerList().getPlayers().getFirst().blockPosition().offset(8,4,0);
                    for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++){
                        level.setBlock(origin.offset(x,-1,z),Blocks.GRASS_BLOCK.defaultBlockState(),3);
                        for(int y=0;y<4;y++)level.setBlock(origin.offset(x,y,z),Blocks.AIR.defaultBlockState(),3);
                    }
                    BlockPos foot=origin.offset(-2,0,-2);
                    var bed=Blocks.BED.white().defaultBlockState().setValue(BedBlock.FACING,Direction.SOUTH).setValue(BedBlock.PART,BedPart.FOOT);
                    level.setBlock(foot,bed,18);level.setBlock(foot.south(),bed.setValue(BedBlock.PART,BedPart.HEAD),18);
                    level.updateNeighborsAt(foot,Blocks.BED.white());level.updateNeighborsAt(foot.south(),Blocks.BED.white());
                    Villager v=EntityTypes.VILLAGER.create(level,EntitySpawnReason.COMMAND);
                    require(v!=null,"Villager creation");v.snapTo(origin.getX()+.5,origin.getY(),origin.getZ()+.5,0,0);
                    v.setNoAi(true);require(level.addFreshEntity(v),"Villager insertion");body=v.getUUID();bodyId=v.getId();
                }
                if(serverTicks==30){
                    var v=(Villager)server.overworld().getEntity(body);v.setNoAi(false);
                    String result=AutonomousVillagesMod.runtime(server).claim(server.overworld(),v.blockPosition(),"Integrated",24,new UUID(0,0));
                    LOG.info("CLIENT_TEST claim: {}",result);
                }
                if(serverTicks==100){
                    var runtime=AutonomousVillagesMod.runtime(server);
                    var village=runtime.villages.get("integrated");
                    require(village!=null,"Claimed village missing");
                    var worker=village.workers.get(body);require(worker!=null,"Worker missing");
                    require(worker.bot().kind()==BotKind.VILLAGER&&worker.bot().entity() instanceof Villager,"Native villager kind");
                    require(worker.bot().inventory().insert(new ItemStack(Items.DIAMOND,17)).isEmpty(),"Backpack insertion");
                    require(server.getPlayerList().getPlayerCount()==1,"Unexpected fake players");
                    runtime.save();firstPassed=true;LOG.info("CLIENT_TEST PASS integrated_runtime_claim_and_native_backpack");
                }
            }else if(phase==3&&++serverTicks==100){
                var runtime=AutonomousVillagesMod.runtime(server);var village=runtime.villages.get("integrated");
                require(village!=null&&village.state.active,"Active village did not restore");
                var worker=village.workers.get(body);require(worker!=null,"Restored worker missing");
                require(worker.bot().inventory().count(Items.DIAMOND)==17,"Backpack restore not exactly 17");
                require(server.getPlayerList().getPlayerCount()==1,"Unexpected fake players after reload");
                bodyId=worker.bot().entity().getId();secondPassed=true;
                LOG.info("CLIENT_TEST PASS integrated_reload_preserves_native_body_and_17_diamonds");
            }
        }catch(Throwable error){fail(error);}
    }
}
