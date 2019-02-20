package gov.nist.csd.pm.graph;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import gov.nist.csd.pm.exceptions.PMException;
import gov.nist.csd.pm.graph.model.nodes.Node;
import gov.nist.csd.pm.graph.model.relationships.Assignment;
import gov.nist.csd.pm.graph.model.relationships.Association;

import java.util.*;

import static gov.nist.csd.pm.graph.model.nodes.NodeType.UA;

public class GraphSerializer {

    private GraphSerializer() {
    }

    /**
     * Given a Graph interface, serialize the graph to a json string. The format of the json will be:
     *
     * @param graph
     * @return
     * @throws PMException
     */
    public static String toJson(Graph graph) throws PMException {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();

        Collection<Node> nodes = graph.getNodes();


        HashSet<Assignment> jsonAssignments = new HashSet<>();
        HashSet<Association> jsonAssociations = new HashSet<>();
        for (Node node : nodes) {
            Set<Long> parents = graph.getParents(node.getID());

            for (Long parent : parents) {
                jsonAssignments.add(new Assignment(node.getID(), parent));
            }

            Map<Long, Set<String>> associations = graph.getSourceAssociations(node.getID());
            for (long targetID : associations.keySet()) {
                Set<String> ops = associations.get(targetID);
                Node targetNode = graph.getNode(targetID);

                jsonAssociations.add(new Association(node.getID(), targetNode.getID(), ops));
            }
        }

        return gson.toJson(new JsonGraph(nodes, jsonAssignments, jsonAssociations));
    }

    public static Graph fromJson(String json) throws PMException {
        Graph graph = new MemGraph();
        JsonGraph jsonGraph = new Gson().fromJson(json, JsonGraph.class);

        Collection<Node> nodes = jsonGraph.getNodes();
        HashMap<Long, Node> nodesMap = new HashMap<>();
        for (Node node : nodes) {
            long newNodeID = graph.createNode(node);
            nodesMap.put(node.getID(), node.id(newNodeID));
        }

        Set<Assignment> assignments = jsonGraph.getAssignments();
        for (Assignment assignment : assignments) {
            Node childCtx = nodesMap.get(assignment.getSourceID());
            Node parentCtx = nodesMap.get(assignment.getTargetID());
            graph.assign(childCtx, parentCtx);
        }

        Set<Association> associations = jsonGraph.getAssociations();
        for (Association association : associations) {
            long uaID = association.getSourceID();
            long targetID = association.getTargetID();
            Node targetNode = nodesMap.get(targetID);
            graph.associate(
                    new Node(nodesMap.get(uaID).getID(), UA),
                    new Node(targetNode.getID(), targetNode.getType()),
                    association.getOperations()
            );
        }

        return graph;
    }

    static class JsonGraph {
        Collection<Node> nodes;
        Set<Assignment>  assignments;
        Set<Association> associations;

        JsonGraph(Collection<Node> nodes, Set<Assignment> assignments, Set<Association> associations) {
            this.nodes = nodes;
            this.assignments = assignments;
            this.associations = associations;
        }

        Collection<Node> getNodes() {
            return nodes;
        }

        Set<Assignment> getAssignments() {
            return assignments;
        }

        Set<Association> getAssociations() {
            return associations;
        }
    }
}
