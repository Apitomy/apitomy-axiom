package io.apitomy.axiom.app.rest;

import io.apitomy.axiom.api.LineageResource;
import io.apitomy.axiom.api.beans.LineageGraph;
import io.apitomy.axiom.app.LineageService;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

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
    public LineageGraph getLineage(String entityType, String id, String direction, Integer depth,
            Integer maxNodes) {
        return lineageService.getLineage(entityType, id, direction,
                depth != null ? depth : LineageService.DEFAULT_DEPTH,
                maxNodes != null ? maxNodes : LineageService.DEFAULT_MAX_NODES);
    }

    /**
     * Maps a failed conversion of the integer query parameters (for example {@code ?depth=abc}) to
     * {@code 400} with an {@code Error} body. JAX-RS reports such failures as {@code 404}. This mapper is
     * declared on the resource class, so it applies to this resource only; 404s for unknown roots are
     * thrown as plain {@code WebApplicationException}s and are not affected.
     *
     * @param e the conversion failure
     * @return a 400 response
     */
    @ServerExceptionMapper
    public Response mapParamConversion(NotFoundException e) {
        return LineageService.error(400, "Invalid depth or maxNodes: must be an integer").getResponse();
    }
}
