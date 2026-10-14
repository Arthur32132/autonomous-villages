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
        ServerLifecycleEvents.SERVER_STARTED.register(server -> runtime = new VillageRuntime(server));
        ServerTickEvents.END_SERVER_TICK.register(server -> { if (runtime != null) runtime.tick(); });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (runtime != null) for (Village village : runtime.villages.values()) {
                if (village.state.residents.remove(entity.getUUID())) {
                    village.workers.remove(entity.getUUID()); village.reservations.release(entity.getUUID());
                }
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> { if (runtime != null) { runtime.close(); runtime = null; } });
        CommandRegistrationCallback.EVENT.register((dispatcher, access, env) -> {
            var root = Commands.literal("avillage").requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes(c -> help(c.getSource()))
                    .then(Commands.literal("help").executes(c -> help(c.getSource())))
                    .then(Commands.literal("claim").then(Commands.argument("name", StringArgumentType.string())
                        .executes(c -> claim(c.getSource(), StringArgumentType.getString(c, "name"), 48))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(24, 64))
                            .executes(c -> claim(c.getSource(), StringArgumentType.getString(c, "name"), IntegerArgumentType.getInteger(c, "radius"))))))
                    .then(Commands.literal("status").executes(c -> run(c.getSource(), () -> runtime(c.getSource().getServer()).status(null)))
                        .then(Commands.argument("name", StringArgumentType.string()).executes(c -> run(c.getSource(), () -> runtime(c.getSource().getServer()).status(StringArgumentType.getString(c, "name"))))))
                    .then(Commands.literal("storage").then(Commands.argument("name", StringArgumentType.string())
                        .then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(c -> run(c.getSource(), () -> runtime(c.getSource().getServer())
                            .storage(StringArgumentType.getString(c, "name"), BlockPosArgument.getLoadedBlockPos(c, "pos"), c.getSource()))))));
            for (String action : new String[]{"pause", "resume", "release"}) root.then(Commands.literal(action)
                    .then(Commands.argument("name", StringArgumentType.string()).executes(c -> run(c.getSource(), () -> runtime(c.getSource().getServer())
                        .change(StringArgumentType.getString(c, "name"), action)))));
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
        s.sendSuccess(() -> Component.literal("/avillage claim <имя> [радиус 24..64] — выбрать деревню рядом и запустить; "
                + "status [имя]; pause <имя>; resume <имя>; release <имя>; storage <имя> <x y z> — разрешить общий сундук."), false); return 1;
    }
}
