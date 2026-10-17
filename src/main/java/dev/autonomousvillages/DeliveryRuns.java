package dev.autonomousvillages;

import java.util.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/** A shared dispatch decision for the scheduler and a builder waiting for supplies. */
final class DeliveryRuns {
    private record Dispatch(Resident carrier,Resident donor,Resident recipient) { }

    /** A lease promises capacity for concrete stack components. No items live in this record. */
    static final class Agreement {
        Resident donor,recipient;
        ChestBlockEntity source,destination;
        Orders.Material material;
        List<ItemStack> cargo;
        int quantity,personalKit;
        long expires;
        boolean pickedUp;
    }
    static boolean accepts(Agreement a,ItemStack stack){
        return a!=null&&a.cargo.stream().anyMatch(t->ItemStack.isSameItemSameComponents(t,stack));
    }
    static boolean available(Resident r){
        return r!=null&&r.bot()!=null&&r.bot().state()==dev.navigationai.api.BotState.ACTIVE
                &&r.body().isAlive()&&!r.body().isBaby()&&!r.body().isNoAi()&&!r.body().isTrading()
                &&!r.body().isSleeping()&&!r.fleeing&&r.combat==null&&!r.v.expeditions.owns(r.id);
    }
    static int room(List<ItemStack> target,List<ItemStack> source,java.util.function.Predicate<ItemStack> accepts,int limit){
        var next=Supplies.copy(target);int n=0;
        for(var stack:source)if(!stack.isEmpty()&&accepts.test(stack)&&n<limit)
            n+=Supplies.insertPartial(next,stack.copyWithCount(Math.min(stack.getCount(),limit-n)));
        return n;
    }
    static boolean promised(Resident participant){
        return participant.v.workers.values().stream().anyMatch(r->r!=participant&&r.agreement!=null
                &&r.agreement.expires>participant.now()&&(r.agreement.recipient==participant
                ||!r.agreement.pickedUp&&r.agreement.donor==participant));
    }
    static boolean hold(Resident r){
        if(!promised(r)||!r.canExchange())return false;
        r.status="ждёт согласованную передачу груза; место выделено";return true;
    }
    static boolean reserve(Resident carrier,Orders.Order order,Resident donor,Resident recipient,int limit){
        if(!available(recipient)||promised(recipient)||donor!=null&&(!available(donor)||promised(donor)))return false;
        var material=order.kind();if(material==null)return false;
        var source=(donor==null?carrier:donor).shareable(material);
        int amount=room(recipient.bot().inventory().snapshot().slots(),source,material.accepts,Math.min(limit,order.outstanding()));
        if(donor!=null)amount=Math.min(amount,room(carrier.bot().inventory().snapshot().slots(),source,material.accepts,amount));
        if(amount<=0){order.waiting="у получателя нет места для груза";return false;}
        var a=new Agreement();a.donor=donor;a.recipient=recipient;a.material=material;a.cargo=Supplies.copy(source);
        a.quantity=amount;a.expires=carrier.now()+2400;a.pickedUp=donor==null;
        carrier.agreement=a;order.waiting="согласована передача "+amount+"; ожидается завершение текущего действия";return true;
    }
    static void release(Resident r){
        var a=r.agreement;if(a==null)return;
        var kit=r.v.state.cargoKits.get(r.id);
        if(kit!=null&&kit.containsKey(a.material)&&r.count(a.material)<=kit.get(a.material)){
            kit.remove(a.material);if(kit.isEmpty())r.v.state.cargoKits.remove(r.id);
        }
        if(a.source!=null)r.v.rejected.put(a.source.getBlockPos().asLong(),r.now()+200);
        if(a.destination!=null)r.v.rejected.put(a.destination.getBlockPos().asLong(),r.now()+200);
        r.agreement=null;
    }
    static String status(Village v){
        return v.workers.values().stream().filter(r->r.agreement!=null)
                .map(r->r.id+": "+r.job+" "+r.agreement.material+" "+r.agreement.quantity)
                .findFirst().orElse("нет активной перевозки; заявки ждут доступный груз, место или носильщика");
    }

    private static boolean running(Village v){
        return v.workers.values().stream().anyMatch(r->Set.of("deliver","collect_order","collect_chest","deliver_chest","delivery_return").contains(Objects.toString(r.job,"")));
    }

    private static Dispatch plan(Village v,Orders.Order order){
        if(!order.open()||order.localOnly||order.returnCargo()||order.kind()==null||order.recipient.isEmpty()
                ||order.retryAfter>v.level.getGameTime()||!order.carrier.isEmpty())return null;
        Resident recipient;
        try{recipient=v.workers.get(UUID.fromString(order.recipient));}catch(IllegalArgumentException ignored){return null;}
        if(!available(recipient)||promised(recipient))return null;
        var material=order.kind();
        Resident porter=v.workers.values().stream().filter(r->r!=recipient&&v.porter(r.id)&&r.canHelp())
                .min(Comparator.comparing(r->r.id.toString())).orElse(null);
        if(porter!=null){
            Resident donor=v.holders(material).stream().filter(r->r!=recipient&&r!=porter&&available(r)&&!promised(r)
                            &&!v.expeditions.owns(r.id)&&v.claimableFromBag(order,r)>0
                            &&r.body().distanceToSqr(porter.body())<256*256
                            &&room(recipient.bot().inventory().snapshot().slots(),r.shareable(material),material.accepts,v.claimableFromBag(order,r))>0)
                    .min(Comparator.comparingDouble(r->r.body().distanceToSqr(porter.body()))).orElse(null);
            if(donor!=null)return new Dispatch(porter,donor,recipient);
        }
        Resident carrier=v.holders(material).stream().filter(r->r!=recipient&&r.canHelp()
                        &&v.claimableFromBag(order,r)>0&&r.body().distanceToSqr(recipient.body())<256*256
                        &&room(recipient.bot().inventory().snapshot().slots(),r.shareable(material),material.accepts,v.claimableFromBag(order,r))>0)
                .min(Comparator.<Resident>comparingInt(r->v.porter(r.id)?0:1)
                        .thenComparingDouble(r->r.body().distanceToSqr(recipient.body()))).orElse(null);
        return carrier==null?null:new Dispatch(carrier,null,recipient);
    }

    static boolean canServe(Village v,Orders.Order order){
        Orders.refreshStock(v);
        if(!order.open())return false;
        if(!order.carrier.isEmpty())return v.workers.values().stream().anyMatch(r->r.deliverOrder==order
                &&r.id.toString().equals(order.carrier)&&r.bot()!=null&&r.bot().state()==dev.navigationai.api.BotState.ACTIVE&&r.body().isAlive()
                &&Set.of("deliver","collect_order","collect_chest").contains(Objects.toString(r.job,"")));
        return !running(v)&&plan(v,order)!=null;
    }

    static void tick(Village v){
        if(v.level.getGameTime()%100!=0)return;
        for(var r:v.workers.values())if(r.agreement!=null)valid(r);
        if(running(v))return;
        Orders.refreshStock(v);
        for(var order:v.state.orders){
            var dispatch=plan(v,order);
            if(dispatch!=null){
                if(dispatch.donor!=null)dispatch.carrier.collectOrder(order,dispatch.donor,dispatch.recipient.id);
                else dispatch.carrier.deliver(order,dispatch.recipient.id);
                if(dispatch.carrier.agreement!=null){v.event("Согласована доставка исполнителю");return;}
            }
            if(order.open()&&!order.localOnly&&!order.returnCargo()&&order.kind()!=null&&order.carrier.isEmpty()&&order.retryAfter<=v.level.getGameTime()){
                Resident recipient=recipient(v,order);
                if(!available(recipient)||promised(recipient)){order.waiting="получатель недоступен; ожидается исполнитель";continue;}
                var porter=porter(v,recipient);
                if(porter!=null&&fromChest(v,porter,recipient,order,null,order.kind(),order.outstanding()))return;
                order.waiting="нет доступного излишка, места или носильщика; самостоятельное получение со склада разрешено";
            }
        }
        restock(v);
    }
    private static Resident recipient(Village v,Orders.Order o){
        try{return v.workers.get(UUID.fromString(o.recipient));}catch(IllegalArgumentException ignored){return null;}
    }
    private static Resident porter(Village v,Resident recipient){
        return v.workers.values().stream().filter(r->r!=recipient&&r.canHelp()&&!promised(r))
                .min(Comparator.<Resident>comparingInt(r->v.porter(r.id)?0:1).thenComparing(r->r.id.toString())).orElse(null);
    }
    static int target(String purpose,Orders.Material material){
        return switch(purpose){
            case "seeds" -> material==Orders.Material.SEEDS?16:0;
            case "smithy" -> switch(material){case RAW_IRON->8;case FUEL->8;case COBBLESTONE->16;default->0;};
            case "lumber_yard" -> material==Orders.Material.LOGS?16:0;
            case "watch_post" -> material==Orders.Material.FOOD?6:0;
            case "granary" -> material==Orders.Material.FOOD?12:0;
            default -> 0;
        };
    }
    private static String purpose(Village v,ChestBlockEntity c){
        return ManagedLand.seedChest(v,c.getBlockPos().asLong())?"seeds":ProductionBuildings.storagePurpose(v,c.getBlockPos().asLong());
    }
    static void restock(Village v){
        var porter=porter(v,null);if(porter==null)return;
        for(var destination:v.chests()){
            String purpose=purpose(v,destination);
            for(var material:Orders.Material.values()){
                int shortage=target(purpose,material)-Supplies.count(Supplies.copy(destination),material.accepts);
                if(shortage>0&&fromChest(v,porter,null,null,destination,material,shortage))return;
            }
        }
    }
    static boolean fromChest(Village v,Resident carrier,Resident recipient,Orders.Order order,ChestBlockEntity destination,Orders.Material material,int wanted){
        if(running(v)||!carrier.canHelp()||recipient!=null&&(!available(recipient)||promised(recipient)))return false;
        for(var source:v.chests()){
            if(source==destination||ManagedLand.seedChest(v,source.getBlockPos().asLong())
                    ||v.rejected.getOrDefault(source.getBlockPos().asLong(),0L)>v.level.getGameTime()
                    ||destination!=null&&v.rejected.getOrDefault(destination.getBlockPos().asLong(),0L)>v.level.getGameTime())continue;
            var cargo=Supplies.copy(source);
            int surplus=order!=null?v.claimableFromChests(order):Math.min(v.ledger(material).free(),
                    Math.max(0,Supplies.count(cargo,material.accepts)-target(purpose(v,source),material)));
            int amount=Math.min(wanted,surplus);
            if(destination!=null){
                cargo.removeIf(s->!ManagedLand.acceptsDeposit(v,destination.getBlockPos().asLong(),s));
                amount=Math.min(amount,room(Supplies.copy(destination),cargo,material.accepts,amount));
            }else amount=Math.min(amount,room(recipient.bot().inventory().snapshot().slots(),cargo,material.accepts,amount));
            amount=Math.min(amount,room(carrier.bot().inventory().snapshot().slots(),cargo,material.accepts,amount));
            if(amount<=0)continue;
            int kit=carrier.kit(material);
            carrier.cancel();carrier.assign("collect_chest",source.getBlockPos());
            if(!"collect_chest".equals(carrier.job))continue;
            var a=new Agreement();a.source=source;a.destination=destination;a.recipient=recipient;a.material=material;
            a.cargo=Supplies.copy(cargo);a.quantity=amount;a.personalKit=kit;a.expires=v.level.getGameTime()+2400;
            carrier.agreement=a;carrier.deliverOrder=order;carrier.deliverRecipient=recipient==null?null:recipient.id;
            if(order!=null){order.carrier=carrier.id.toString();order.waiting="место выделено; носильщик идёт на склад";}
            carrier.status="согласованное снабжение: забирает груз из общего сундука";return true;
        }
        return false;
    }
    static boolean valid(Resident r){
        var a=r.agreement;if(a==null)return false;
        var o=r.deliverOrder;
        if(r.now()>=a.expires||o!=null&&(!r.v.state.orders.contains(o)||!o.open()
                ||a.recipient==null||!a.recipient.id.toString().equals(o.recipient))){
            if(o!=null)o.waiting="назначение изменено; физический груз доступен для нового заказа";
            r.cancel();return false;
        }
        if(a.recipient!=null&&!available(a.recipient)){
            if(o!=null)o.waiting="получатель недоступен; груз сохранён, ожидается переназначение";
            r.cancel();return false;
        }
        return true;
    }
    private static boolean chestAvailable(Resident r,ChestBlockEntity c){
        return c!=null&&r.v.chests().contains(c);
    }
    /** Called only at physical arrival by the ordinary navigation/work state machine. */
    static void chestArrived(Resident r){
        if(!valid(r))return;
        var a=r.agreement;var v=r.v;var b=r.bot();var o=r.deliverOrder;
        var point=a.pickedUp?a.destination:a.source;
        if(point==null||!r.reaches(point.getBlockPos()))return;
        if(!a.pickedUp){
            if(!chestAvailable(r,a.source)){r.fail("сундук недоступен; груз не изъят");return;}
            int allowed=o==null?Math.min(v.ledger(a.material).free(),
                    Math.max(0,Supplies.count(Supplies.copy(a.source),a.material.accepts)-target(purpose(v,a.source),a.material))):v.claimableFromChests(o);
            if(a.destination!=null&&!chestAvailable(r,a.destination)){r.fail("рабочий сундук недоступен до получения груза");return;}
            var target=a.destination==null?a.recipient.bot().inventory().snapshot().slots():Supplies.copy(a.destination);
            allowed=Math.min(allowed,room(target,a.cargo,a.material.accepts,a.quantity));
            int moved=Supplies.transfer(b,a.source,false,s->a.material.accepts.test(s)
                    &&a.cargo.stream().anyMatch(t->ItemStack.isSameItemSameComponents(s,t)),Math.min(a.quantity,allowed));
            if(moved==0){r.fail("излишек на складе изменился; ожидается новое снабжение");return;}
            a.quantity=moved;a.pickedUp=true;
            v.state.cargoKits.computeIfAbsent(r.id,k->new EnumMap<>(Orders.Material.class)).put(a.material,a.personalKit);
            if(o!=null){
                r.carryCollectedOrder(moved,a.personalKit);
                v.runtime.save();return;
            }
            r.job="deliver_chest";r.target=a.destination.getBlockPos();r.navigation=null;r.since=r.now();
            r.status="несёт реальный груз в рабочий сундук";v.runtime.save();return;
        }
        if(!chestAvailable(r,a.destination)){r.fail("рабочий сундук недоступен; груз сохранён у носильщика");return;}
        int moved=Supplies.transfer(b,a.destination,true,s->a.material.accepts.test(s)
                &&ManagedLand.acceptsDeposit(v,a.destination.getBlockPos().asLong(),s)
                &&a.cargo.stream().anyMatch(t->ItemStack.isSameItemSameComponents(s,t)),Math.min(a.quantity,Math.max(0,r.count(a.material)-a.personalKit)));
        v.event("Снабжение рабочего сундука: "+moved+" "+a.material);
        r.finish();v.runtime.save();
    }
}
