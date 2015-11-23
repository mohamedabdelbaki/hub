package com.flightstats.hub.spoke;

import com.flightstats.hub.app.HubServices;
import com.flightstats.hub.cluster.CuratorCluster;
import com.google.common.util.concurrent.AbstractIdleService;
import com.google.inject.Inject;
import com.google.inject.name.Named;

public class SpokeClusterRegister {

    @Inject
    @Named("SpokeCuratorCluster")
    private CuratorCluster spokeCuratorCluster;

    public SpokeClusterRegister() {
        HubServices.register(new CuratorSpokeClusterHook(), HubServices.TYPE.FINAL_POST_START, HubServices.TYPE.PRE_STOP);
    }

    private class CuratorSpokeClusterHook extends AbstractIdleService {
        @Override
        protected void startUp() throws Exception {
            spokeCuratorCluster.addCacheListener();
            spokeCuratorCluster.register();
        }

        @Override
        protected void shutDown() throws Exception {
            spokeCuratorCluster.delete();
        }
    }
}