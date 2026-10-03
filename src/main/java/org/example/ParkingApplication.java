package org.example;

import org.example.resource.PageResource;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.filter.RolesAllowedDynamicFeature;

public class ParkingApplication extends ResourceConfig {
    public ParkingApplication() {
        register(PageResource.class);
        register(RolesAllowedDynamicFeature.class);
        packages(
                "org.example.auth",
                "org.example.admin",
                "org.example.booking",
                "org.example.resource",
                "org.example.filter"
        );
    }
}