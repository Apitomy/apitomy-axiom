package io.apitomy.axiom.app.rest;

import io.apitomy.axiom.core.SdkFunction;
import io.apitomy.axiom.core.SdkFunctionRegistry;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

@Path("/api/v1/sdk")
@Produces(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class SdkResource {

    @GET
    @Path("/functions")
    public List<SdkFunction> listSdkFunctions(
            @QueryParam("sdkCallOnly") Boolean sdkCallOnly) {
        if (Boolean.TRUE.equals(sdkCallOnly)) {
            return SdkFunctionRegistry.sdkCallFunctions();
        }
        return SdkFunctionRegistry.all();
    }
}
