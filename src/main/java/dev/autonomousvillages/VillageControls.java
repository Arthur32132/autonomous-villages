package dev.autonomousvillages;

import java.util.*;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/** Human controls use operator commands and never silently reinterpret a player's edits. */
final class VillageControls {
    static void register(com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> root){
        root.then(Commands.literal("plan").then(Commands.argument("name",StringArgumentType.string())
                .executes(c->run(c.getSource(),()->explain(village(c.getSource(),StringArgumentType.getString(c,"name")))))));
        root.then(Commands.literal("chronicle").then(Commands.argument("name",StringArgumentType.string())
                .executes(c->run(c.getSource(),()->village(c.getSource(),StringArgumentType.getString(c,"name")).chronicle.render(1)))
                .then(Commands.argument("page",com.mojang.brigadier.arguments.IntegerArgumentType.integer(1,6))
                .executes(c->run(c.getSource(),()->village(c.getSource(),StringArgumentType.getString(c,"name")).chronicle.render(com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c,"page")))))));
        root.then(Commands.literal("protect").then(Commands.argument("name",StringArgumentType.string())
                .then(Commands.argument("activity",StringArgumentType.word()).suggests((c,b)->{for(String a:List.of("wood","mine","build","farm","all"))b.suggest(a);return b.buildFuture();})
                .then(Commands.argument("from",BlockPosArgument.blockPos()).then(Commands.argument("to",BlockPosArgument.blockPos()).executes(c->run(c.getSource(),()->{
                    var v=village(c.getSource(),StringArgumentType.getString(c,"name"));String activity=StringArgumentType.getString(c,"activity");
                    if(!Set.of("wood","mine","build","farm","all").contains(activity)||v.state.policy.zones.size()>=64)throw new IllegalArgumentException("Допустимо wood/mine/build/farm/all; максимум 64 участка");
                    var a=BlockPosArgument.getBlockPos(c,"from");var b=BlockPosArgument.getBlockPos(c,"to");
                    var z=new VillagePolicy.Zone();z.activity=activity;z.minX=Math.min(a.getX(),b.getX());z.maxX=Math.max(a.getX(),b.getX());z.minZ=Math.min(a.getZ(),b.getZ());z.maxZ=Math.max(a.getZ(),b.getZ());
                    var proposed=new VillagePolicy();proposed.zones.add(z);proposed.validate();v.state.policy.zones.add(z);v.workers.values().forEach(Worker::cancel);v.runtime.saveNow();return "Запрет №"+(v.state.policy.zones.size()-1)+": "+activity;
                })))))));
        root.then(Commands.literal("unprotect").then(Commands.argument("name",StringArgumentType.string()).then(Commands.argument("index",com.mojang.brigadier.arguments.IntegerArgumentType.integer(0,63))
                .executes(c->run(c.getSource(),()->{var v=village(c.getSource(),StringArgumentType.getString(c,"name"));int i=com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c,"index");
                    if(i>=v.state.policy.zones.size())throw new IllegalArgumentException("Нет такого участка");v.state.policy.zones.remove(i);v.runtime.saveNow();return "Запрет снят";})))));
        root.then(Commands.literal("private").then(Commands.argument("name",StringArgumentType.string()).then(Commands.argument("pos",BlockPosArgument.blockPos()).executes(c->run(c.getSource(),()->{
            var v=village(c.getSource(),StringArgumentType.getString(c,"name"));var p=BlockPosArgument.getLoadedBlockPos(c,"pos");
            if(!v.state.contains(p)||!(v.level.getBlockEntity(p) instanceof ChestBlockEntity))throw new IllegalArgumentException("Нужен сундук в деревне");
            var pending=new LinkedHashSet<>(v.state.policy.privateChests);pending.add(p.asLong());
            var actual=v.level.getBlockState(p);
            if(actual.getValue(ChestBlock.TYPE)!=net.minecraft.world.level.block.state.properties.ChestType.SINGLE)
                {var mate=p.relative(ChestBlock.getConnectedDirection(actual));if(v.state.contains(mate))pending.add(mate.asLong());}
            if(pending.size()>128)throw new IllegalArgumentException("Лимит личных сундуков");
            v.state.policy.privateChests=pending;v.state.storage.removeAll(pending);v.workers.values().forEach(Worker::cancel);v.runtime.saveNow();return "Сундук отмечен личным; доступ через storage возвращается только явно";
        })))));
        root.then(Commands.literal("expansion").then(Commands.argument("name",StringArgumentType.string()).then(Commands.argument("direction",StringArgumentType.word())
                .executes(c->run(c.getSource(),()->{var v=village(c.getSource(),StringArgumentType.getString(c,"name"));String dir=StringArgumentType.getString(c,"direction");
                    if(!Set.of("north","south","east","west","none","all").contains(dir))throw new IllegalArgumentException("north/south/east/west/none/all");
                    v.state.policy.expansion.clear();if(dir.equals("all"))v.state.policy.expansion.addAll(List.of("north","south","east","west"));else if(!dir.equals("none"))v.state.policy.expansion.add(dir);
                    v.runtime.saveNow();return "Разрешённые направления: "+v.state.policy.expansion;})))));
    }
    private static Village village(CommandSourceStack source,String name){
        var v=AutonomousVillagesMod.runtime(source.getServer()).get(name);
        if(source.getLevel()!=v.level)throw new IllegalArgumentException("Команды деревни доступны в её измерении");return v;
    }
    private interface Action {String run()throws Exception;}
    private static int run(CommandSourceStack source,Action action){try{String text=action.run();source.sendSuccess(()->Component.literal(text),false);return 1;}catch(Exception e){source.sendFailure(Component.literal(Objects.toString(e.getMessage(),e.getClass().getSimpleName())));return 0;}}
    /** What the planner sees: store totals, the active project, darkness and growth, plus each worker's job. */
    static String explain(Village v){
        var lines=new ArrayList<String>(v.planner.describe());
        for(var w:v.workers.values())lines.add("• "+v.role(w.id)+": "+w.status+(w.lastFailure.isEmpty()?"":" (последняя неудача: "+w.lastFailure+")"));
        for(int i=0;i<v.state.policy.zones.size();i++){var z=v.state.policy.zones.get(i);lines.add("Запрет №"+i+" "+z.activity+": "+z.minX+","+z.minZ+"–"+z.maxX+","+z.maxZ);}
        lines.add("Расширение: "+v.state.policy.expansion+"; личных сундуков: "+v.state.policy.privateChests.size());
        return String.join("\n",lines);
    }
}
