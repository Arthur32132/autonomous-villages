package dev.autonomousvillages;

import com.mojang.brigadier.arguments.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.TicketType;
import org.slf4j.*;

public final class AutonomousVillagesMod implements ModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("autonomous_villages");
    /** Read at mod start; every switch on unless the server owner turned it off. */
    public static ModConfig CONFIG = new ModConfig();
    public static final TicketType TICKET = Registry.register(BuiltInRegistries.TICKET_TYPE,
            Identifier.fromNamespaceAndPath("autonomous_villages", "settlement"),
            new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION | TicketType.FLAG_KEEP_DIMENSION_ACTIVE));
    private static VillageRuntime runtime;
    public static boolean hasActiveVillages(){return runtime!=null&&runtime.villages.values().stream().anyMatch(v->v.state.active);}
    public static VillageRuntime runtime(MinecraftServer server) {
        if (runtime == null || runtime.server != server) throw new IllegalStateException("Деревни ещё загружаются.");
        return runtime;
    }
    @Override public void onInitialize() {
        CONFIG = ModConfig.load(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir());
        dev.navigationai.api.BlockBreakPermissions.register((id,pos)->{
            if(runtime==null)return true;
            return runtime.mayBreak(id,pos);
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> runtime = new VillageRuntime(server));
        ServerEntityEvents.ENTITY_LOAD.register((entity,level)->{if(runtime!=null&&entity instanceof net.minecraft.world.entity.npc.villager.Villager resident&&level==runtime.server.overworld())runtime.residentLoaded(resident);});
        ServerTickEvents.END_SERVER_TICK.register(server -> { if (runtime != null) runtime.tick(); });
        net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register((player,level,hand,entity,hit)->
                runtime!=null&&!level.isClientSide()&&player instanceof net.minecraft.server.level.ServerPlayer p&&entity instanceof net.minecraft.world.entity.npc.villager.Villager resident
                        ?PlayerGoods.useOnResident(runtime,p,hand,resident):net.minecraft.world.InteractionResult.PASS);
        net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((player,level,hand)->{
            if(runtime!=null&&!level.isClientSide()&&player instanceof net.minecraft.server.level.ServerPlayer p)PlayerGoods.refresh(runtime,p,hand);
            return net.minecraft.world.InteractionResult.PASS;
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity,level)->{
            if(runtime!=null&&entity instanceof net.minecraft.world.entity.npc.villager.Villager resident&&resident.isAlive()&&level==runtime.server.overworld())
                for(var village:runtime.villages.values())if(village.state.residents.contains(resident.getUUID()))village.state.residentPositions.put(resident.getUUID(),resident.blockPosition().asLong());
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (runtime != null) for (Village village : runtime.villages.values()) {
                if (village.state.residents.contains(entity.getUUID())) {
                    String name=village.presentation.personName(entity.getUUID());
                    String role=entity.isBaby()?"малыш":village.role(entity.getUUID());
                    String who=name+", "+role;
                    if(CONFIG.graves)village.state.graves.add(new VillageState.Grave(name,role,village.level.getGameTime()/24000+1));
                    village.state.residents.remove(entity.getUUID());
                    var worker=village.workers.remove(entity.getUUID());
                    LOG.info("DEATH {} {}: {}",village.state.name,who,deathContext(village,entity,worker));
                    if(worker!=null&&worker.job!=null)worker.job.stop();
                    village.reservations.release(entity.getUUID());
                    village.residentLost(entity.getUUID());
                    var killer=source.getEntity();
                    village.chronicle.died(who,killer!=null?killer.getType().getDescription().getString():source.getMsgId());
                    runtime.save();
                }
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> { if (runtime != null) { runtime.close(); runtime = null; } });
        CommandRegistrationCallback.EVENT.register((dispatcher, access, env) -> {
            var root = Commands.literal("avillage").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes(c -> help(c.getSource()))
                    .then(Commands.literal("help").executes(c -> help(c.getSource())))
                    .then(Commands.literal("claim").then(Commands.argument("name", StringArgumentType.string())
                        .executes(c -> claim(c.getSource(), StringArgumentType.getString(c, "name"), 48))))
                    .then(Commands.literal("status").executes(c -> run(c.getSource(), () -> runtime(c.getSource().getServer()).status(null)))
                        .then(Commands.argument("name", StringArgumentType.string()).executes(c -> run(c.getSource(), () -> runtime(c.getSource().getServer()).status(StringArgumentType.getString(c, "name"))))))
                    .then(Commands.literal("storage").then(Commands.argument("name", StringArgumentType.string())
                        .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(c -> run(c.getSource(), () -> runtime(c.getSource().getServer())
                            .storage(StringArgumentType.getString(c, "name"), BlockPosArgument.getLoadedBlockPos(c, "pos"), c.getSource()))))));
            for (String action : new String[]{"pause", "resume", "release"}) root.then(Commands.literal(action)
                    .then(Commands.argument("name", StringArgumentType.string()).executes(c -> run(c.getSource(), () -> runtime(c.getSource().getServer())
                        .change(StringArgumentType.getString(c, "name"), action)))));
            VillageControls.register(root);
            dispatcher.register(root);
        });
    }
    private static int claim(CommandSourceStack s, String name, int radius) {
        return run(s, () -> runtime(s.getServer()).claim(s.getLevel(), net.minecraft.core.BlockPos.containing(s.getPosition()), name, radius,
                s.getEntity() == null ? new java.util.UUID(0,0) : s.getEntity().getUUID()));
    }
    private interface Action { String execute() throws Exception; }
    private static int run(CommandSourceStack s, Action action) {
        try { String message = action.execute(); s.sendSuccess(() -> Component.literal(message), false); return 1; }
        catch (Exception error) { s.sendFailure(Component.literal(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage())); return 0; }
    }
    private static int help(CommandSourceStack s) {
        s.sendSuccess(() -> Component.literal("/avillage claim <имя> — взять деревню под управление; status [имя]; plan <имя> — что видит планировщик; "
                + "chronicle <имя> [стр] — летопись; storage <имя> <x y z> — сделать сундук общим; private <имя> <x y z>; "
                + "protect <имя> <wood|mine|build|farm|all> <от> <до>; unprotect <имя> <номер>; expansion <имя> <north|south|east|west|none|all>; "
                + "pause|resume|release <имя>."), false); return 1;
    }

    /** Where and how a resident died, for the logs: light, doors nearby, what it was doing. */
    static String deathContext(Village village,net.minecraft.world.entity.LivingEntity body,Worker worker){
        var level=village.level;var at=body.blockPosition();
        var doors=new StringBuilder();
        for(var q:net.minecraft.core.BlockPos.betweenClosed(at.offset(-6,-2,-6),at.offset(6,2,6))){
            var s=level.getBlockState(q);
            if(s.getBlock() instanceof net.minecraft.world.level.block.DoorBlock&&s.getValue(net.minecraft.world.level.block.DoorBlock.HALF)==net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER)
                doors.append(q.toShortString()).append(s.getValue(net.minecraft.world.level.block.DoorBlock.OPEN)?" open":" closed").append(String.format(" %.1f; ",Math.sqrt(q.distSqr(at))));
        }
        return "pos="+at.toShortString()+" blockLight="+level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK,at)+" sky="+level.canSeeSky(at)
                +" sleeping="+body.isSleeping()+" job="+(worker==null||worker.job==null?"none":worker.job.kind())+" status="+(worker==null?"":worker.status)
                +" doors=["+doors+"] clock="+Math.floorMod(level.getOverworldClockTime(),24000);
    }
}
