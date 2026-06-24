package io.apitomy.axiom.app.rest;

import io.apitomy.axiom.api.beans.Trace;
import io.apitomy.axiom.core.entities.TraceEntity;

import java.util.Date;

/**
 * Shared mapper for converting {@link TraceEntity} to the API {@link Trace} bean.
 * Used by entity-scoped trace endpoints across multiple resource implementations.
 */
final class TraceMapper {

    private TraceMapper() {
    }

    /**
     * Converts a {@link TraceEntity} to an API {@link Trace} bean.
     */
    static Trace toTraceBean(TraceEntity entity) {
        Trace bean = new Trace();
        bean.setTraceId(entity.traceId);
        bean.setTraceType(entity.traceType);
        bean.setStatus(entity.status);
        bean.setSummary(entity.summary);
        bean.setEventId(entity.eventId);
        bean.setProjectId(entity.projectId);
        bean.setReportId(entity.reportId);
        bean.setStartedOn(Date.from(entity.startedOn));
        if (entity.completedOn != null) {
            bean.setCompletedOn(Date.from(entity.completedOn));
        }
        return bean;
    }
}
