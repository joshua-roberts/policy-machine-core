package gov.nist.csd.pm.pdp.decider;

import gov.nist.csd.pm.exceptions.PMException;
import gov.nist.csd.pm.operations.OperationSet;
import gov.nist.csd.pm.pip.graph.Graph;
import gov.nist.csd.pm.pip.graph.dag.propagator.Propagator;
import gov.nist.csd.pm.pip.graph.dag.searcher.BreadthFirstSearcher;
import gov.nist.csd.pm.pip.graph.dag.searcher.DepthFirstSearcher;
import gov.nist.csd.pm.pip.graph.dag.searcher.Direction;
import gov.nist.csd.pm.pip.graph.dag.visitor.Visitor;
import gov.nist.csd.pm.pip.graph.model.nodes.Node;
import gov.nist.csd.pm.pip.graph.model.nodes.NodeType;
import gov.nist.csd.pm.pip.graph.mysql.MySQLConnection;
import gov.nist.csd.pm.pip.prohibitions.MemProhibitions;
import gov.nist.csd.pm.pip.prohibitions.Prohibitions;
import gov.nist.csd.pm.pip.prohibitions.model.Prohibition;
import gov.nist.csd.pm.pip.prohibitions.mysql.MySQLProhibitions;

import java.util.*;

import static gov.nist.csd.pm.pip.graph.model.nodes.NodeType.*;

/**
 * An implementation of the Decider interface that uses an in memory NGAC graph
 */
public class PReviewDecider implements Decider {

    private final MySQLConnection conn = new MySQLConnection();

    public static final String ANY_OPERATIONS = "any";
    public static final String ALL_OPERATIONS = "*";

    private Graph graph;
    private Prohibitions prohibitions;

    public PReviewDecider(Graph graph) {
        if (graph == null) {
            throw new IllegalArgumentException("NGAC graph cannot be null");
        }

        this.graph = graph;
        this.prohibitions = new MySQLProhibitions(conn);
    }

    public PReviewDecider(Graph graph, Prohibitions prohibitions) {
        if (graph == null) {
            throw new IllegalArgumentException("NGAC graph cannot be null");
        }

        if (prohibitions == null) {
            prohibitions = new MySQLProhibitions(conn);
        }

        this.graph = graph;
        this.prohibitions = prohibitions;
    }

    @Override
    public boolean check(String subject, String process, String target, String... perms) throws PMException {
        List<String> permsToCheck = Arrays.asList(perms);
        Set<String> permissions = list(subject, process, target);

        //if just checking for any operations, return true if the resulting permissions set is not empty.
        //if the resulting permissions set contains * or all operations, return true.
        //if neither of the above apply, return true iff the resulting permissions set contains all the provided
        // permissions to check for
        if (permsToCheck.contains(ANY_OPERATIONS)) {
            return !permissions.isEmpty();
        }
        else if (permissions.contains(ALL_OPERATIONS)) {
            return true;
        }
        else if (permissions.isEmpty()) {
            return false;
        }
        else {
            return permissions.containsAll(permsToCheck);
        }
    }

    @Override
    public Set<String> list(String subject, String process, String target) throws PMException {
        Set<String> perms = new HashSet<>();

        // traverse the user side of the graph to get the associations
        UserContext userCtx = processUserDAG(subject, process);
        if (userCtx.getBorderTargets().isEmpty()) {
            return perms;
        }

        // traverse the target side of the graph to get permissions per policy class
        TargetContext targetCtx = processTargetDAG(target, userCtx);

        // resolve the permissions
        return resolvePermissions(userCtx, targetCtx, target);
    }

    @Override
    public Set<String> filter(String subject, String process, Set<String> nodes, String... perms) {
        nodes.removeIf(n -> {
            try {
                return !check(subject, process, n, perms);
            }
            catch (PMException e) {
                return true;
            }
        });
        return nodes;
    }

    @Override
    public Set<String> getChildren(String subject, String process, String target, String... perms) throws PMException {
        Set<String> children = graph.getChildren(target);
        return filter(subject, process, children, perms);
    }

    @Override
    public synchronized Map<String, Set<String>> getCapabilityList(String subject, String process) throws PMException {
        Map<String, Set<String>> results = new HashMap<>();

        //get border nodes.  Can be OA or UA.  Return empty set if no OAs are reachable
        UserContext userCtx = processUserDAG(subject, process);
        if (userCtx.getBorderTargets().isEmpty()) {
            return results;
        }

        for(String borderTarget : userCtx.getBorderTargets().keySet()) {
            Set<String> objects = getAscendants(graph.getNode(borderTarget).getName());
            for (String object : objects) {
                // run dfs on the object
                TargetContext targetCtx = processTargetDAG(object, userCtx);

                HashSet<String> permissions = resolvePermissions(userCtx, targetCtx, object);
                results.put(object, permissions);
            }
        }

        return results;
    }

    @Override
    public Map<String, Set<String>> generateACL(String target, String process) throws PMException {
        Map<String, Set<String>> acl = new HashMap<>();
        Set<Node> search = graph.search(U, null);
        for (Node user : search) {
            Set<String> list = list(user.getName(), process, target);
            acl.put(user.getName(), list);
        }

        return acl;
    }

    private HashSet<String> resolvePermissions(UserContext userContext, TargetContext targetCtx, String target) {
        Map<String, Set<String>> pcMap = targetCtx.getPcSet();

        HashSet<String> inter = new HashSet<>();
        boolean first = true;
        for (String pc : pcMap.keySet()) {
            Set<String> ops = pcMap.get(pc);
            if(first) {
                inter.addAll(ops);
                first = false;
            } else {
                if (inter.contains(ALL_OPERATIONS)) {
                    // clear all of the existing permissions because the intersection already had *
                    // all permissions can be added
                    inter.clear();
                    inter.addAll(ops);
                } else {
                    // if the ops for the pc are empty then the user has no permissions on the target
                    if (ops.isEmpty()) {
                        inter.clear();
                        break;
                    } else if (!ops.contains(ALL_OPERATIONS)) {
                        inter.retainAll(ops);
                    }
                }
            }
        }

        // remove any prohibited operations
        Set<String> denied = resolveProhibitions(userContext, targetCtx, target);
        inter.removeAll(denied);

        // if the permission set includes *, ignore all other permissions
        if (inter.contains(ALL_OPERATIONS)) {
            inter.clear();
            inter.add(ALL_OPERATIONS);
        }

        return inter;
    }

    private Set<String> resolveProhibitions(UserContext userCtx, TargetContext targetCtx, String target) {
        Set<String> denied = new HashSet<>();

        Set<Prohibition> prohibitions = userCtx.getProhibitions();
        Set<String> reachedTargets = targetCtx.getReachedTargets();

        for(Prohibition p : prohibitions) {
            boolean inter = p.isIntersection();
            Map<String, Boolean> containers = p.getContainers();

            boolean addOps = false;
            for (String contName : containers.keySet()) {
                if (target.equals(contName)) {
                    addOps = false;
                    if (inter) {
                        // if the target is a container and the prohibition evaluates the intersection
                        // the whole prohibition is not satisfied
                        break;
                    } else {
                        // continue checking the remaining conditions
                        continue;
                    }
                }

                boolean isComplement = containers.get(contName);
                if (!isComplement && reachedTargets.contains(contName) || isComplement && !reachedTargets.contains(contName)) {
                    addOps = true;

                    // if the prohibition is not intersection, one satisfied container condition means
                    // the prohibition is satisfied
                    if (!inter) {
                        break;
                    }
                } else {
                    // since the intersection requires the target to satisfy each node condition in the prohibition
                    // if one is not satisfied then the whole is not satisfied
                    addOps = false;

                    // if the prohibition is the intersection, one unsatisfied container condition means the whole
                    // prohibition is not satisfied
                    if (inter) {
                        break;
                    }
                }
            }

            if (addOps) {
                denied.addAll(p.getOperations());
            }
        }
        return denied;
    }

    /**
     * Perform a depth first search on the object side of the graph.  Start at the target node and recursively visit nodes
     * until a policy class is reached.  On each node visited, collect any operation the user has on the target. At the
     * end of each dfs iteration the visitedNodes map will contain the operations the user is permitted on the target under
     * each policy class.
     *
     * @param target      the name of the current target node.
     */
    private TargetContext processTargetDAG(String target, UserContext userCtx) throws PMException {
        Map<String, Set<String>> borderTargets = userCtx.getBorderTargets();

        Map<String, Map<String, Set<String>>> visitedNodes = new HashMap<>();
        Set<String> reachedTargets = new HashSet<>();

        Visitor visitor = node -> {
            // mark the node as reached, to be used for resolving prohibitions
            reachedTargets.add(node.getName());

            Map<String, Set<String>> nodeCtx = visitedNodes.getOrDefault(node.getName(), new HashMap<>());
            if (nodeCtx.isEmpty()) {
                visitedNodes.put(node.getName(), nodeCtx);
            }

            if (node.getType().equals(NodeType.PC)) {
                nodeCtx.put(node.getName(), new HashSet<>());
            } else {
                if (borderTargets.containsKey(node.getName())) {
                    Set<String> uaOps = borderTargets.get(node.getName());
                    for (String pc : nodeCtx.keySet()) {
                        Set<String> pcOps = nodeCtx.getOrDefault(pc, new HashSet<>());
                        pcOps.addAll(uaOps);
                        nodeCtx.put(pc, pcOps);
                    }
                }
            }
        };

        Propagator propagator = (parent, child) -> {
            Map<String, Set<String>> parentCtx = visitedNodes.get(parent.getName());
            Map<String, Set<String>> nodeCtx = visitedNodes.getOrDefault(child.getName(), new HashMap<>());
            for (String name : parentCtx.keySet()) {
                Set<String> ops = nodeCtx.getOrDefault(name, new HashSet<>());
                ops.addAll(parentCtx.get(name));
                nodeCtx.put(name, ops);
            }
            visitedNodes.put(child.getName(), nodeCtx);
        };

        DepthFirstSearcher searcher = new DepthFirstSearcher(graph);
        searcher.traverse(graph.getNode(target), propagator, visitor, Direction.PARENTS);

        return new TargetContext(visitedNodes.get(target), reachedTargets);
    }

    /**
     * Find the target nodes that are reachable by the subject via an association. This is done by a breadth first search
     * starting at the subject node and walking up the user side of the graph until all user attributes the subject is assigned
     * to have been visited.  For each user attribute visited, get the associations it is the source of and store the
     * target of that association as well as the operations in a map. If a target node is reached multiple times, add any
     * new operations to the already existing ones.
     *
     * @return a Map of target nodes that the subject can reach via associations and the operations the user has on each.
     */
    private UserContext processUserDAG(String subject, String process) throws PMException {
        BreadthFirstSearcher searcher = new BreadthFirstSearcher(graph);

        Node start = graph.getNode(subject);

        final Map<String, Set<String>> borderTargets = new HashMap<>();
        // initialize with the prohibitions or the provided process
        final Set<Prohibition> reachedProhibitions = new HashSet<>(prohibitions.getProhibitionsFor(process));

        // if the start node is an UA, get it's associations
        if (start.getType() == UA) {
            Map<String, OperationSet> assocs = graph.getSourceAssociations(start.getName());
            collectAssociations(assocs, borderTargets);
        }

        Visitor visitor = node -> {
            List<Prohibition> subjectProhibitions = prohibitions.getProhibitionsFor(node.getName());
            reachedProhibitions.addAll(subjectProhibitions);

            //get the parents of the subject to start bfs on user side
            Set<String> parents = graph.getParents(node.getName());
            while (!parents.isEmpty()) {
                String parentNode = parents.iterator().next();
                Node parentN = graph.getNode(parentNode);
                if (parentN.getType() == UA) {

                    //get the associations the current parent node is the source of
                    Map<String, OperationSet> assocs = graph.getSourceAssociations(parentNode);

                    //collect the target and operation information for each association
                    collectAssociations(assocs, borderTargets);
                }
                //add all of the current parent node's parents to the queue
                parents.addAll(graph.getParents(parentNode));

                //remove the current parent from the queue
                parents.remove(parentNode);
            }
        };

        // nothing is being propagated
        Propagator propagator = (parentNode, childNode) -> {};

        // start the bfs
        searcher.traverse(start, propagator, visitor, Direction.PARENTS);

        return new UserContext(borderTargets, reachedProhibitions);
    }

    private void collectAssociations(Map<String, OperationSet> assocs, Map<String, Set<String>> borderTargets) {
        for (String target : assocs.keySet()) {
            Set<String> ops = assocs.get(target);
            Set<String> exOps = borderTargets.get(target);
            //if the target is not in the map already, put it
            //else add the found operations to the existing ones.
            if (exOps == null) {
                borderTargets.put(target, ops);
            } else {
                ops.addAll(exOps);
                borderTargets.put(target, ops);
            }
        }
    }

    private Set<String> getAscendants(String vNode) throws PMException {
        Set<String> ascendants = new HashSet<>();
        ascendants.add(vNode);

        Set<String> children = graph.getChildren(vNode);
        if (children.isEmpty()) {
            return ascendants;
        }

        ascendants.addAll(children);
        for (String child : children) {
            ascendants.addAll(getAscendants(child));
        }

        return ascendants;
    }

    private static class UserContext {
        private Map<String, Set<String>> borderTargets;
        private Set<Prohibition> prohibitions;

        UserContext(Map<String, Set<String>> borderTargets, Set<Prohibition> prohibitions) {
            this.borderTargets = borderTargets;
            this.prohibitions = prohibitions;
        }

        Map<String, Set<String>> getBorderTargets() {
            return borderTargets;
        }

        Set<Prohibition> getProhibitions() {
            return prohibitions;
        }
    }

    private static class TargetContext {
        Map<String, Set<String>> pcSet;
        Set<String> reachedTargets;

        TargetContext(Map<String, Set<String>> pcSet, Set<String> reachedTargets) {
            this.pcSet = pcSet;
            this.reachedTargets = reachedTargets;
        }

        Map<String, Set<String>> getPcSet() {
            return pcSet;
        }

        Set<String> getReachedTargets() {
            return reachedTargets;
        }
    }
}
