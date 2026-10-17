package dev.autonomousvillages;

import java.util.*;

/** A shared dispatch decision for the scheduler and a builder waiting for supplies. */
final class DeliveryRuns {
    private record Dispatch(Resident carrier,Resident donor,Resident recipient) { }

    private static boolean running(Village v){
        return v.workers.values().stream().anyMatch(r->Set.of("deliver","collect_order","delivery_return").contains(Objects.toString(r.job,"")));
    }

    private static Dispatch plan(Village v,Orders.Order order){
        if(!order.open()||order.localOnly||order.returnCargo()||order.kind()==null||order.recipient.isEmpty()
                ||order.retryAfter>v.level.getGameTime()||!order.carrier.isEmpty())return null;
        Resident recipient;
        try{recipient=v.workers.get(UUID.fromString(order.recipient));}catch(IllegalArgumentException ignored){return null;}
        if(recipient==null||!recipient.canExchange()||recipient.canFetch(order))return null;
        var material=order.kind();
        Resident porter=v.workers.values().stream().filter(r->r!=recipient&&v.porter(r.id)&&r.canHelp())
                .min(Comparator.comparing(r->r.id.toString())).orElse(null);
        if(porter!=null){
            Resident donor=v.holders(material).stream().filter(r->r!=recipient&&r!=porter&&r.canExchange()
                            &&!v.expeditions.owns(r.id)&&v.claimableFromBag(order,r)>0
                            &&r.body().distanceToSqr(porter.body())<256*256)
                    .min(Comparator.comparingDouble(r->r.body().distanceToSqr(porter.body()))).orElse(null);
            if(donor!=null)return new Dispatch(porter,donor,recipient);
        }
        Resident carrier=v.holders(material).stream().filter(r->r!=recipient&&r.canHelp()
                        &&v.claimableFromBag(order,r)>0&&r.body().distanceToSqr(recipient.body())<256*256)
                .min(Comparator.<Resident>comparingInt(r->v.porter(r.id)?0:1)
                        .thenComparingDouble(r->r.body().distanceToSqr(recipient.body()))).orElse(null);
        return carrier==null?null:new Dispatch(carrier,null,recipient);
    }

    static boolean canServe(Village v,Orders.Order order){
        Orders.refreshStock(v);
        if(!order.open())return false;
        if(!order.carrier.isEmpty())return v.workers.values().stream().anyMatch(r->r.deliverOrder==order
                &&r.id.toString().equals(order.carrier)&&r.bot()!=null&&r.bot().state()==dev.navigationai.api.BotState.ACTIVE&&r.body().isAlive()
                &&("deliver".equals(r.job)||"collect_order".equals(r.job)));
        return !running(v)&&plan(v,order)!=null;
    }

    static void tick(Village v){
        if(v.level.getGameTime()%100!=0||v.state.orders.isEmpty()||running(v))return;
        Orders.refreshStock(v);
        for(var order:v.state.orders){
            var dispatch=plan(v,order);if(dispatch==null)continue;
            if(dispatch.donor!=null)dispatch.carrier.collectOrder(order,dispatch.donor,dispatch.recipient.id);
            else dispatch.carrier.deliver(order,dispatch.recipient.id);
            if(!"deliver".equals(dispatch.carrier.job)&&!"collect_order".equals(dispatch.carrier.job))continue;
            v.event("Отправили материалы по заказу нужному исполнителю");return;
        }
    }
}
