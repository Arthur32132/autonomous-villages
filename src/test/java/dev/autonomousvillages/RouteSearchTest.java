package dev.autonomousvillages;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RouteSearchTest {
    record Edge(Integer to,double cost,int work,int length) implements RouteSearch.Edge<Integer> { }
    private RouteSearch.Result<Edge> search(Map<Integer,List<Edge>> graph,int budget,int work,int length){
        return RouteSearch.find(0,n->n==9,n->graph.getOrDefault(n,List.of()),n->0,budget,work,length);
    }
    @Test void canTurnAroundMoreThanOneObstacleInsteadOfChoosingTwoRectangles(){
        var graph=Map.of(0,List.of(new Edge(1,1,0,1)),1,List.of(new Edge(2,1,0,1)),
                2,List.of(new Edge(3,1,0,1)),3,List.of(new Edge(9,1,0,1)));
        var result=search(graph,20,64,192);
        assertTrue(result.found());assertEquals(List.of(1,2,3,9),result.edges().stream().map(Edge::to).toList());
    }
    @Test void longerExistingRoadBeatsShorterExpensiveNewCrossing(){
        var graph=Map.of(0,List.of(new Edge(9,24,3,2),new Edge(1,1,0,1)),
                1,List.of(new Edge(2,1,0,1)),2,List.of(new Edge(9,1,0,1)));
        var result=search(graph,20,64,192);
        assertTrue(result.found());assertEquals(3,result.cost());assertEquals(3,result.edges().size());
    }
    @Test void keepsCostlierPrefixThatLeavesEnoughEarthworksForTheFarBank(){
        var graph=Map.of(0,List.of(new Edge(1,1,4,1),new Edge(2,2,0,1)),
                2,List.of(new Edge(1,1,0,1)),1,List.of(new Edge(9,1,2,1)));
        var result=search(graph,20,4,192);
        assertTrue(result.found());assertEquals(4,result.cost());assertEquals(2,result.edges().getFirst().to());
    }
    @Test void lengthBudgetAlsoKeepsANondominatedAlternative(){
        var graph=Map.of(0,List.of(new Edge(1,1,0,3),new Edge(2,2,0,1)),
                2,List.of(new Edge(1,1,0,1)),1,List.of(new Edge(9,1,0,1)));
        var result=search(graph,20,64,3);
        assertTrue(result.found());assertEquals(4,result.cost());
    }
    @Test void aDisconnectedHomeExitIsNotAnAdditionalFreeStart(){
        var graph=Map.of(0,List.of(new Edge(1,1,0,1)),2,List.of(new Edge(9,1,0,1)));
        var result=search(graph,20,64,192);
        assertFalse(result.found());assertTrue(result.failure().contains("нет безопасного пути"));
    }
    @Test void exhaustionAndEarthworkRefusalAreExplicitAndBounded(){
        var graph=Map.of(0,List.of(new Edge(1,1,0,1)),1,List.of(new Edge(9,1,0,1)));
        assertTrue(search(graph,1,64,192).failure().contains("бюджет поиска"));
        assertFalse(search(Map.of(0,List.of(new Edge(9,1,65,1))),20,64,192).found());
        assertFalse(search(Map.of(0,List.of(new Edge(9,1,0,193))),20,64,192).found());
    }
}
