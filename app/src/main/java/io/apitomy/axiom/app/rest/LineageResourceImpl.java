package io.apitomy.axiom.app.rest;

import io.apitomy.axiom.api.LineageResource;
import io.apitomy.axiom.api.beans.LineageGraph;
import io.apitomy.axiom.app.LineageService;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;

import java.math.BigInteger;

/**
 * REST resource for the lineage graph of an entity (#430).
 */
@ApplicationScoped
@RunOnVirtualThread
public class LineageResourceImpl implements LineageResource {

    @Inject
    LineageService lineageService;

    /**
     * Returns the upstream and downstream lineage graph of an entity.
     *
     * @param entityType root entity type
     * @param id         root entity ID
     * @param direction  {@code upstream}, {@code downstream} or {@code both}
     * @param depth      maximum hops in each direction
     * @param maxNodes   maximum number of nodes
     * @return the lineage graph
     */
    @Override
    public LineageGraph getLineage(String entityType, String id, String direction, BigInteger depth,
            BigInteger maxNodes) {
        return lineageService.getLineage(entityType, id, direction,
                toInt(depth, LineageService.DEFAULT_DEPTH, "depth"),
                toInt(maxNodes, LineageService.DEFAULT_MAX_NODES, "maxNodes"));
    }

    private static int toInt(BigInteger value, int defaultValue, String name) {
        if (value == null) {
            return defaultValue;
        }
        if (value.bitLength() > 31) {
            throw new WebApplicationException("Invalid " + name + ": " + value, 400);
        }
        return value.intValue();
    }
}
