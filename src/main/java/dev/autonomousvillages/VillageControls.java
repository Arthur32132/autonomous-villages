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
        var orderName=Commands.argument("name",StringArgumentType.string());
        orderName.executes(c->run(c.getSource(),()->explain(village(c.getSource(),StringArgumentType.getString(c,"name")))));
        orderName.then(Commands.argument("page",com.mojang.brigadier.arguments.IntegerArgumentType.integer(1,64))
                .executes(c->run(c.getSource(),()->explain(village(c.getSource(),StringArgumentType.getString(c,"name")),com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c,"page")))));
        root.then(Commands.literal("orders").then(orderName));
        root.then(Commands.literal("protect").then(Commands.argument("name",StringArgumentType.string())
                .then(Commands.argument("activity",StringArgumentType.word()).suggests((c,b)->{for(String a:List.of("wood","mine","build","farm","all"))b.suggest(a);return b.buildFuture();})
                .then(Commands.argument("from",BlockPosArgument.blockPos()).then(Commands.argument("to",BlockPosArgument.blockPos()).executes(c->run(c.getSource(),()->{
                    var v=village(c.getSource(),StringArgumentType.getString(c,"name"));String activity=StringArgumentType.getString(c,"activity");
                    if(!Set.of("wood","mine","build","farm","all").contains(activity)||v.state.policy.zones.size()>=64)throw new IllegalArgumentException("Допустимо wood/mine/build/farm/all; максимум 64 участка");
                    var a=BlockPosArgument.getBlockPos(c,"from");var b=BlockPosArgument.getBlockPos(c,"to");
                    var z=new VillagePolicy.Zone();z.activity=activity;z.minX=Math.min(a.getX(),b.getX());z.maxX=Math.max(a.getX(),b.getX());z.minZ=Math.min(a.getZ(),b.getZ());z.maxZ=Math.max(a.getZ(),b.getZ());
                    var proposed=new VillagePolicy();proposed.zones.add(z);proposed.validate();v.state.policy.zones.add(z);v.workers.values().forEach(Resident::cancel);v.runtime.save();return "Запрет №"+(v.state.policy.zones.size()-1)+": "+activity;
                })))))));
        root.then(Commands.literal("unprotect").then(Commands.argument("name",StringArgumentType.string()).then(Commands.argument("index",com.mojang.brigadier.arguments.IntegerArgumentType.integer(0,63))
                .executes(c->run(c.getSource(),()->{var v=village(c.getSource(),StringArgumentType.getString(c,"name"));int i=com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c,"index");
                    if(i>=v.state.policy.zones.size())throw new IllegalArgumentException("Нет такого участка");v.state.policy.zones.remove(i);v.runtime.save();return "Запрет снят";})))));
        root.then(Commands.literal("private").then(Commands.argument("name",StringArgumentType.string()).then(Commands.argument("pos",BlockPosArgument.blockPos()).executes(c->run(c.getSource(),()->{
            var v=village(c.getSource(),StringArgumentType.getString(c,"name"));var p=BlockPosArgument.getLoadedBlockPos(c,"pos");
            if(!v.state.contains(p)||!(v.level.getBlockEntity(p) instanceof ChestBlockEntity))throw new IllegalArgumentException("Нужен сундук в деревне");
            var pending=new LinkedHashSet<>(v.state.policy.privateChests);pending.add(p.asLong());
            var actual=v.level.getBlockState(p);
            if(actual.getValue(ChestBlock.TYPE)!=net.minecraft.world.level.block.state.properties.ChestType.SINGLE)
                {var mate=p.relative(ChestBlock.getConnectedDirection(actual));if(v.state.contains(mate))pending.add(mate.asLong());}
            if(pending.size()>128)throw new IllegalArgumentException("Лимит личных сундуков");
            v.state.policy.privateChests=pending;v.state.storage.removeAll(pending);v.workers.values().forEach(Resident::cancel);v.runtime.save();return "Сундук отмечен личным; доступ через storage возвращается только явно";
        })))));
        root.then(Commands.literal("expansion").then(Commands.argument("name",StringArgumentType.string()).then(Commands.argument("direction",StringArgumentType.word())
                .executes(c->run(c.getSource(),()->{var v=village(c.getSource(),StringArgumentType.getString(c,"name"));String dir=StringArgumentType.getString(c,"direction");
                    if(!Set.of("north","south","east","west","none","all").contains(dir))throw new IllegalArgumentException("north/south/east/west/none/all");
                    v.state.policy.expansion.clear();if(dir.equals("all"))v.state.policy.expansion.addAll(List.of("north","south","east","west"));else if(!dir.equals("none"))v.state.policy.expansion.add(dir);
                    v.runtime.save();return "Разрешённые направления: "+v.state.policy.expansion;})))));
        root.then(Commands.literal("accept").then(Commands.argument("name",StringArgumentType.string()).then(Commands.argument("pos",BlockPosArgument.blockPos()).executes(c->run(c.getSource(),()->{
            var v=village(c.getSource(),StringArgumentType.getString(c,"name"));var p=BlockPosArgument.getLoadedBlockPos(c,"pos");
            var b=v.state.buildings.stream().filter(h->Set.of("house","native_house").contains(h.kind)&&h.blocks.stream().anyMatch(part->part.position().distSqr(p)<=4)).findFirst().orElseThrow(()->new IllegalArgumentException("Укажите изменённый участок известного жилого дома"));
            v.workers.values().forEach(Resident::cancel);
            // Match both physical halves to their single architectural intent, including a removed head.
            var affected=b.blocks.stream().filter(part->part.pos==p.asLong()
                    ||part.block().getBlock() instanceof DoorBlock&&part.position().above().equals(p)
                    ||part.block().getBlock() instanceof BedBlock&&part.position().relative(part.block().getValue(BedBlock.FACING)).equals(p)).toList();
            var positions=new LinkedHashSet<BlockPos>();positions.add(p);
            for(var part:affected)positions.add(part.position());
            b.blocks.removeAll(affected);
            for(var cell:positions){var actual=v.level.getBlockState(cell);if(TownPlanner.structural(actual)&&!TownPlanner.upperHalf(actual)){
                if((actual.getBlock() instanceof DoorBlock||actual.getBlock() instanceof BedBlock)&&!new TownBuilding.Plan(cell,actual).matches(v))continue;
                b.add(cell,actual);
            }}
            b.completed=b.built(v)==b.blocks.size();v.town.invalidate();v.town.syncOrders(v.level.getGameTime());v.runtime.save();return "Изменение принято в плане дома: "+p.toShortString();
        })))));
    }
    private static Village village(CommandSourceStack source,String name){
        var v=AutonomousVillagesMod.runtime(source.getServer()).get(name);
        if(source.getLevel()!=v.level)throw new IllegalArgumentException("Команды деревни доступны в её измерении");return v;
    }
    private interface Action {String run()throws Exception;}
    private static int run(CommandSourceStack source,Action action){try{String text=action.run();source.sendSuccess(()->Component.literal(text),false);return 1;}catch(Exception e){source.sendFailure(Component.literal(Objects.toString(e.getMessage(),e.getClass().getSimpleName())));return 0;}}
    static String explain(Village v){return explain(v,1);}
    static String explain(Village v,int page){
        var lines=new ArrayList<String>();var budget=SettlementEconomy.budget(v);
        lines.add(v.state.name+": еда (хлебные порции) "+SettlementEconomy.food(v)+"/"+budget.food()+", семенной фонд "+budget.seed()+", пшеница стаду "+budget.breedingWheat()+", рост "+(SettlementEconomy.canGrow(v)?"обеспечен":"ждёт две свободные кровати, орошаемое поле и запас еды"));
        for(String need:SettlementNeeds.lines(v.needs).stream().skip((long)(page-1)*8).limit(8).toList())lines.add("потребность "+need);
        if(v.needs.needs().isEmpty())lines.add("потребности: граф ещё не построен");
        else if(v.needs.needs().stream().allMatch(n->v.needs.state(n).state()==SettlementNeeds.State.DONE))lines.add("потребности: все условия выполнены");
        else if(v.needs.open().isEmpty())lines.add("потребности: все работы ожидают; причины указаны в графе");
        for(var o:v.state.orders.stream().filter(Orders.Order::open).skip((long)(page-1)*8).limit(8).toList()){
            var ledger=o.kind()==null?null:v.ledger(o.kind());
            String who=o.recipient.isEmpty()?"не назначен":o.recipient;String carrier=o.carrier.isEmpty()?"не назначен":o.carrier;
            int stock=o.kind()==null?0:v.count(o.kind().accepts);
            lines.add(o.purpose+": осталось "+o.outstanding()+", исполнитель "+who+", перевозчик "+carrier+", свободно "+(ledger==null?0:ledger.free())+", резерв "+(ledger==null?0:ledger.reserved())+", в пути "+o.inTransit+"; "+reason(v,o,stock)+(o.waiting==null||o.waiting.isEmpty()?"":"; доставка: "+o.waiting));
        }
        for(var b:v.state.buildings.stream().filter(b->b.built(v)<b.blocks.size()||!Infrastructure.prepared(v,b)).skip((long)(page-1)*8).limit(8).toList()){
            var executor=v.workers.values().stream().filter(r->r.townPlan!=null&&b.blocks.contains(r.townPlan)).map(r->r.id.toString()).findFirst().orElse("ожидает строителя");
            var associated=v.state.orders.stream().filter(o->o.open()&&o.work==b.origin).toList();
            String carriers=associated.stream().map(o->o.carrier).filter(c->!c.isEmpty()).distinct().collect(java.util.stream.Collectors.joining(", "));
            lines.add(TownPlanner.label(b)+" "+b.built(v)+"/"+b.blocks.size()+": "+executor+", перевозчик "+(carriers.isEmpty()?"не назначен":carriers)+", заявки под проект "+associated.stream().mapToInt(Orders.Order::outstanding).sum()+", срезка "+b.cuts.size()+", подсыпка "+b.foundation.size()+", подход "+Infrastructure.prepared(v,b)
                    +"; доступ: "+Infrastructure.accessReason(v,b)
                    +(b.housingFor==null?"":"; расширение жилого участка "+BlockPos.of(b.housingFor).toShortString())
                    +projectMaterials(v,b));
        }
        lines.add("Логистика: "+DeliveryRuns.status(v));
        for(var entry:v.state.accessFailures.entrySet().stream().skip((long)(page-1)*8).limit(8).toList()){
            var failure=entry.getValue();
            lines.add("Подключение "+BlockPos.of(entry.getKey()).toShortString()+": "+(failure.resolved?"перепланировано; прежний отказ: ":"ожидает обхода: ")
                    +failure.reason+"; попыток "+failure.attempts+", повтор через "+Math.max(0,failure.retryAt-v.level.getGameTime())+" тактов");
        }
        lines.add("Выпуск мастерских (предметы; для поста — ответы на тревогу): "+v.state.workshopOutput);
        for(int i=0;i<v.state.policy.zones.size();i++){var z=v.state.policy.zones.get(i);lines.add("Запрет №"+i+" "+z.activity+": "+z.minX+","+z.minZ+"–"+z.maxX+","+z.maxZ);}
        lines.add("Страница "+page+"; остальные проекты: /avillage orders "+v.state.name+" "+(page+1));
        lines.add("Расширение: "+v.state.policy.expansion+"; личных сундуков: "+v.state.policy.privateChests.size());return String.join("\n",lines);
    }
    private static String projectMaterials(Village v,TownBuilding b){
        var missing=new LinkedHashMap<net.minecraft.world.item.Item,TownBuilding.Plan>();var amounts=new LinkedHashMap<net.minecraft.world.item.Item,Integer>();
        for(var p:java.util.stream.Stream.concat(b.foundation.stream(),b.blocks.stream()).toList())if(!p.matches(v)){var item=p.step().item();missing.putIfAbsent(item,p);amounts.merge(item,Construction.cost(p.step()),Integer::sum);}
        var lines=new ArrayList<String>();
        for(var entry:missing.entrySet()){var plan=entry.getValue();var material=java.util.Arrays.stream(Orders.Material.values()).filter(m->m.accepts.test(new net.minecraft.world.item.ItemStack(entry.getKey()))).findFirst().orElse(null);
            int free=material==null?v.count(Construction.material(plan.step())):v.ledger(material).free();
            int reserved=material==null?0:v.ledger(material).reserved();
            lines.add(entry.getKey()+" нужно "+amounts.get(entry.getKey())+", свободно "+free+", общий резерв "+reserved+"; "+alternative(v,plan));
        }
        return lines.isEmpty()?"":"; "+String.join(" | ",lines);
    }
    private static String alternative(Village v,TownBuilding.Plan p){
        var s=p.block();
        if(!v.state.policy.allows("build",p.position()))return "строительство запрещено на участке; снимите запрет или выберите другую площадку";
        if(Construction.masonry(s)||Construction.stoneStairs(s)||Construction.stoneSlab(s)||s.is(net.minecraft.tags.BlockTags.WALLS))return "допустим булыжник и изделия из него";
        if(s.is(net.minecraft.tags.BlockTags.PLANKS)||s.is(net.minecraft.tags.BlockTags.LOGS)||s.is(net.minecraft.tags.BlockTags.WOODEN_STAIRS)||s.is(net.minecraft.tags.BlockTags.WOODEN_SLABS)||s.is(net.minecraft.tags.BlockTags.WOODEN_FENCES)||s.is(net.minecraft.tags.BlockTags.WOODEN_DOORS))return "подходит любая разрешённая порода дерева";
        if(s.getBlock() instanceof BedBlock)return "3 шерсти одного цвета и 3 доски; подходит кровать любого цвета";
        if(s.is(Blocks.GLASS_PANE))return v.index.sand.isEmpty()?"не обнаружен доступный песок; можно предоставить песок/стекло либо принять изменённую стену командой accept":"песок → печь с топливом → 6 стекла на панели";
        if(s.is(Blocks.TORCH))return "уголь или древесный уголь и палка; нужна доступная печь для углежжения";
        if(s.is(Blocks.CHEST)||s.is(Blocks.CRAFTING_TABLE))return "нужны доски; подходят разные породы";
        if(s.is(Blocks.FURNACE))return "8 булыжников и рабочий верстак";
        return "нужен реальный рецепт и доступ к исполнителю";
    }
    private static String reason(Village v,Orders.Order o,int stock){
        if(o.transit)return "реальный груз доставляется";
        if(stock>=o.outstanding())return "материал есть; требуется доступ к сундуку или физическая передача из рюкзака";
        return switch(o.kind()){
            case WOOL->v.farm.state().flock.size()<2?"нужна племенная пара овец; овец можно привести с пшеницей":"ждёт шерсть: трава, отрастание и ножницы; для кровати 3 шерсти одного цвета";
            case IRON->"не хватает слитков: руда → доступная печь с топливом → доставка; для режущего инструмента нужен железный приоритет";
            case SHEARS->"ножницы требуют 2 слитка; исполнитель — смотритель овец";
            case PLANKS,LOGS->"ждёт разрешённый лесной участок и восстановление посадок; доступны разные породы дерева";
            default->"не хватает "+Math.max(0,o.outstanding()-stock)+"; нужен реальный рецепт и доставка";
        };
    }
}
