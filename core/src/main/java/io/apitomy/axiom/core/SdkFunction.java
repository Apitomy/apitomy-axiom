package io.apitomy.axiom.core;

import java.util.List;

public record SdkFunction(
        String name,
        String description,
        List<SdkParam> parameters,
        boolean sdkCallSupported) {

    public record SdkParam(
            String name,
            String type,
            String description,
            boolean required) {
    }
}
