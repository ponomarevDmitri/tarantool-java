package org.tarantool.cluster;

import org.tarantool.CommunicationException;
import org.tarantool.TarantoolClientConfig;
import org.tarantool.TarantoolClientImpl;
import org.tarantool.TarantoolClusterClient;
import org.tarantool.TarantoolClusterClientConfig;
import org.tarantool.server.BinaryProtoUtils;
import org.tarantool.server.TarantoolNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class ClusterTopologyFromShardDiscovererImpl implements ClusterTopologyDiscoverer {

    private final TarantoolClusterClientConfig clientConfig;

    private static final String DISCOVER_TOPOLOGY_TARANTOOL_SIDE_FUNCTION_NAME = "get_shard_cfg";

    public ClusterTopologyFromShardDiscovererImpl(TarantoolClusterClientConfig clientConfig) {
        this.clientConfig = clientConfig;
    }

    @Override
    public List<TarantoolNode> discoverTarantoolNodes(TarantoolNode infoNode,
                                                            Integer infoHostConnectionTimeout) {

        List<?> list = new TarantoolClientImpl(infoNode.getSocketAddress(), clientConfig)
                .syncOps()
                .call(DISCOVER_TOPOLOGY_TARANTOOL_SIDE_FUNCTION_NAME);

        Map funcResult = (Map) ((List) list.get(0)).get(0);

        Map shardHash2DescriptionMap = (Map) getValue(funcResult, "sharding");

        List<TarantoolNode> result = new ArrayList<>();


        for (Object shardHash2Description : shardHash2DescriptionMap.entrySet()) {

            Map replicas = (Map) getValue(((Map.Entry) shardHash2Description).getValue(), "replicas");

            for (Object replica : replicas.entrySet()) {
                Object replicaUri = getValue(((Map.Entry) replica).getValue(), "uri");
                result.add(TarantoolNode.create(parseReplicaUri(replicaUri.toString())));
            }
        }

        return result;
    }

    private String parseReplicaUri(String uri) {
        String[] split = uri.split("@");
        if (split.length == 2) {
            return split[1];
        } else {
            return split[0];
        }
    }

    private Object getValue(Object map, String key) {
        if (!(map instanceof Map)) {
            throw new IllegalArgumentException("Argument 'map' is not instance of Map but " + map.getClass());
        }

        return ((Map) map).get(key);
    }
}