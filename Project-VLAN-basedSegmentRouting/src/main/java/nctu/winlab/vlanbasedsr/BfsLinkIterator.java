package nctu.winlab.vlanbasedsr;

import org.onosproject.net.DeviceId;
import org.onosproject.net.Link;
import org.onosproject.net.topology.DefaultTopologyVertex;
import org.onosproject.net.topology.TopologyEdge;
import org.onosproject.net.topology.TopologyGraph;
import org.onosproject.net.topology.TopologyVertex;

import java.lang.Iterable;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;

public class BfsLinkIterator implements Iterable<Link> {
    private Queue<TopologyVertex> bfsQueue = new LinkedList<TopologyVertex>();
    private Set<TopologyVertex> walked = new HashSet<TopologyVertex>();
    private LinkedList<Link> links;

    private TopologyGraph graph;

    public BfsLinkIterator(TopologyGraph graph, DeviceId deviceid) {
        this(graph, new DefaultTopologyVertex(deviceid));
    }

    public BfsLinkIterator(TopologyGraph graph, TopologyVertex dest) {
        this.graph = graph;
        this.bfsQueue.add(dest);
        this.walked.add(dest);

        this.links = bfs();
    }

    @Override
    public Iterator<Link> iterator() {
        return this.links.iterator();
    }

    private LinkedList<Link> bfs() {
        LinkedList<Link> links = new LinkedList<Link>();

        while (!this.bfsQueue.isEmpty()) {
            TopologyVertex cur = this.bfsQueue.poll();

            for (TopologyEdge e: this.graph.getEdgesTo(cur)) {
                if (this.walked.contains(e.src())) {
                    continue;
                }

                this.bfsQueue.add(e.src());
                this.walked.add(e.src());
                links.add(e.link());
            }
        }

        return links;
    }
}

