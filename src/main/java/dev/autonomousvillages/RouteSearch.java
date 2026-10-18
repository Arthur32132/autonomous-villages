package dev.autonomousvillages;

import java.util.*;
import java.util.function.*;

/** Bounded A*: keep resource/length alternatives instead of discarding a cheaper-to-build detour. */
final class RouteSearch {
    interface Edge<N> { N to(); double cost(); int work(); int length(); }
    record Result<E>(List<E> edges,double cost,String failure) {
        boolean found(){return failure.isEmpty();}
    }
    private static final class Label<N,E> {
        final N node;final double cost,estimate;final int work,length;
        final Label<N,E> previous;final E edge;
        boolean active=true;
        Label(N node,double cost,double estimate,int work,int length,Label<N,E> previous,E edge){
            this.node=node;this.cost=cost;this.estimate=estimate;this.work=work;this.length=length;this.previous=previous;this.edge=edge;
        }
        boolean dominates(Label<N,E> other){return cost<=other.cost&&work<=other.work&&length<=other.length;}
    }
    static <N,E extends Edge<N>> Result<E> find(N start,Predicate<N> goal,Function<N,List<E>> neighbors,
            ToDoubleFunction<N> heuristic,int maxExpanded,int maxWork,int maxLength){
        var open=new PriorityQueue<Label<N,E>>(Comparator.comparingDouble((Label<N,E> l)->l.estimate)
                .thenComparingDouble(l->l.cost).thenComparingInt(l->l.length));
        var labels=new HashMap<N,List<Label<N,E>>>();
        var first=new Label<N,E>(start,0,heuristic.applyAsDouble(start),0,0,null,null);
        open.add(first);labels.put(start,new ArrayList<>(List.of(first)));
        int expanded=0,generated=1;
        while(!open.isEmpty()){
            var at=open.remove();if(!at.active)continue;
            if(goal.test(at.node)){
                var path=new ArrayList<E>();for(var l=at;l.edge!=null;l=l.previous)path.add(l.edge);
                Collections.reverse(path);return new Result<>(List.copyOf(path),at.cost,"");
            }
            if(++expanded>maxExpanded)return new Result<>(List.of(),0,"исчерпан бюджет поиска обхода");
            for(var edge:neighbors.apply(at.node)){
                int work=at.work+edge.work(),length=at.length+edge.length();
                if(work>maxWork||length>maxLength)continue;
                double cost=at.cost+edge.cost();
                var next=new Label<N,E>(edge.to(),cost,cost+heuristic.applyAsDouble(edge.to()),work,length,at,edge);
                var alternatives=labels.computeIfAbsent(edge.to(),ignored->new ArrayList<>());
                if(alternatives.stream().anyMatch(l->l.dominates(next)))continue;
                alternatives.removeIf(l->{if(next.dominates(l)){l.active=false;return true;}return false;});
                // Also bound queued labels, including non-dominating resource alternatives.
                if(++generated>maxExpanded*12)return new Result<>(List.of(),0,"исчерпан бюджет вариантов маршрута");
                alternatives.add(next);open.add(next);
            }
        }
        return new Result<>(List.of(),0,"нет безопасного пути в пределах длины и земляных работ");
    }
}
