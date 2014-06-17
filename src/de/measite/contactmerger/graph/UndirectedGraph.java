package de.measite.contactmerger.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import android.util.LongSparseArray;
import android.util.SparseArray;

/**
 * An undirected graph with a given label node and label type. This
 * implementation is sparse.
 *
 * @param <NodeType> The node type.
 * @param <EdgeType> The edge type.
 */
public class UndirectedGraph<NodeType, EdgeType> {

    public static class Edge<NodeType, EdgeType>
        implements Comparable<Edge<NodeType, EdgeType>>
    {

        public EdgeType e;
        public NodeType a;
        public NodeType b;

        public Edge() {}

        public Edge(EdgeType e, NodeType a, NodeType b) {
            this.e = e;
            this.a = a;
            this.b = b;
        }

        @Override
        @SuppressWarnings("unchecked")
        public int compareTo(Edge<NodeType, EdgeType> another) {
            int result = 0;
            if (e instanceof Comparable<?>) {
                result = ((Comparable<EdgeType>)e).compareTo(another.e);
            }
            if (result != 0) return result;
            if (a instanceof Comparable<?>) {
                result = ((Comparable<NodeType>)a).compareTo(another.a);
                if (result != 0) return result;
                result = ((Comparable<NodeType>)b).compareTo(another.b);
                if (result != 0) return result;
            }
            return 0;
        }

        @Override
        public String toString() {
            return "Edge [e=" + e + ", a=" + a + ", b=" + b + "]";
        }

    }

    private transient Lock lock = new ReentrantLock();
    private LongSparseArray<EdgeType> edges = new LongSparseArray<EdgeType>();
    private SparseArray<NodeType> idToNode = new SparseArray<NodeType>();
    private HashMap<NodeType, Integer> nodeToId = new HashMap<NodeType, Integer>();
    private int id = 0;

    private final static long mergeFromTo(int from, int to) {
        if (from > to) {
            return mergeFromTo(to, from);
        } else {
            return (((long)from) << 32) + to;
        }
    }

    private final static int[] splitFromTo(long value) {
        return new int[]{
            (int)((value >> 32) & 0xffffffff),
            (int)(value & 0xffffffff),
        };
    }

    public void addNode(NodeType node) {
        if (nodeToId.containsKey(node)) return;
        try {
            lock.lock();
            int id = this.id++;
            nodeToId.put(node, id);
            idToNode.put(id, node);
        } finally {
            try { 
                lock.unlock();
            } catch (Exception e) { /* not locked etc */ }
        }
    }

    public void setEdge(NodeType node1, NodeType node2, EdgeType value) {
        addNode(node1);
        addNode(node2);
        long edgeId = mergeFromTo(nodeToId.get(node1), nodeToId.get(node2));
        try {
            lock.lock();
            edges.put(edgeId, value);
        } finally {
            try { 
                lock.unlock();
            } catch (Exception e) { /* not locked etc */ }
        }
    }

    public EdgeType getEdge(NodeType node1, NodeType node2) {
        addNode(node1);
        addNode(node2);
        long edgeId = mergeFromTo(nodeToId.get(node1), nodeToId.get(node2));
        try {
            lock.lock();
            return edges.get(edgeId);
        } finally {
            try { 
                lock.unlock();
            } catch (Exception e) { /* not locked */ }
        }
    }

    public List<Edge<NodeType, EdgeType>> edgeSet() {
        ArrayList<Edge<NodeType, EdgeType>> set = new ArrayList<Edge<NodeType, EdgeType>>(edges.size() + 1);
        for(int i = 0; i < edges.size(); i++) {
            int[] fromto = splitFromTo(edges.keyAt(i));
            EdgeType value = edges.valueAt(i);
            set.add(new Edge<NodeType,EdgeType>(
                value, idToNode.get(fromto[0]), idToNode.get(fromto[1])));
        }
        return set;
    }

}
