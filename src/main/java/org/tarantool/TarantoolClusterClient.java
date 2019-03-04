package org.tarantool;

import org.tarantool.cluster.ClusterTopologyDiscoverer;
import org.tarantool.cluster.ClusterTopologyFromShardDiscovererImpl;
import org.tarantool.server.*;

import java.io.*;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static org.tarantool.TarantoolClientImpl.StateHelper.CLOSED;

/**
 * Basic implementation of a client that may work with the cluster
 * of tarantool instances in fault-tolerant way.
 *
 * Failed operations will be retried once connection is re-established
 * unless the configured expiration time is over.
 */
public class TarantoolClusterClient extends TarantoolClientImpl {
    /* Need some execution context to retry writes. */
    private Executor executor;

    /* Collection of operations to be retried. */
    private ConcurrentHashMap<Long, ExpirableOp<?>> retries = new ConcurrentHashMap<Long, ExpirableOp<?>>();

    @Deprecated
    private final Collection<TarantoolInstanceInfo> slaveHosts;

    private TarantoolInstanceInfo infoHost;
    private Integer infoHostConnectionTimeout;
    private ClusterTopologyDiscoverer topologyDiscoverer;


    private List<TarantoolInstanceInfo> newServerList = null;
    private TarantoolInstanceConnection oldConnection;
    private ConcurrentHashMap<Long, CompletableFuture<?>> futuresSentToOldConnection = new ConcurrentHashMap<>();
    private ReentrantLock initLock = new ReentrantLock();

    private Selector readSelector;

    /**
     * @param config Configuration.
     */
    public TarantoolClusterClient(TarantoolClusterClientConfig config) {
//        this(config, new RoundRobinSocketProviderImpl(config.slaveHosts).setTimeout(config.operationExpiryTimeMillis));
        this(config, new RoundRobinNodeCommunicationProvider(config.slaveHosts,
                config.username, config.password, config.operationExpiryTimeMillis));
    }

    /**
     * @param provider Socket channel provider.
     * @param config Configuration.
     */
    public TarantoolClusterClient(TarantoolClusterClientConfig config, NodeCommunicationProvider provider) {
        init(provider, config);

        this.executor = config.executor == null ?
            Executors.newSingleThreadExecutor() : config.executor;

        if (config.infoHost != null) {
            this.infoHost = TarantoolInstanceInfo.create(config.infoHost, config.username, config.password);

            this.infoHostConnectionTimeout = config.infoHostConnectionTimeout;
            this.topologyDiscoverer = new ClusterTopologyFromShardDiscovererImpl(config);

            slaveHosts = topologyDiscoverer.discoverTarantoolInstances(this.infoHost, infoHostConnectionTimeout);
        } else {
            if (config.slaveHosts == null || config.slaveHosts.length == 0) {
                throw new IllegalArgumentException("Either slaveHosts or infoHost must be specified.");
            }

            slaveHosts = Arrays.stream(config.slaveHosts)
                    .map(s -> TarantoolInstanceInfo.create(s, config.username, config.password))
                    .collect(Collectors.toList());
        }

    }

    /**
     * @param infoNode a node from which a topology of the cluster is discovered.
     * @throws CommunicationException in case of communication with {@code infoNode} exception
     * @throws IllegalArgumentException in case when the info node returned invalid address
     */
    private Collection<TarantoolInstanceInfo> refreshServerList(TarantoolInstanceInfo infoNode) {
        List<TarantoolInstanceInfo> newServerList = topologyDiscoverer
                .discoverTarantoolInstances(infoNode, infoHostConnectionTimeout);


        initLock.lock();
        try {

            RoundRobinNodeCommunicationProvider cp = (RoundRobinNodeCommunicationProvider) this.communicationProvider;

            int sameNodeIndex = newServerList.indexOf(currConnection.getNodeInfo());
            if (sameNodeIndex != -1) {
                //current node is in the list
                Collections.swap(newServerList, 0, sameNodeIndex);
                cp.updateNodes(newServerList);
            } else {


                cp.updateNodes(newServerList);

                //todo move to "connect" method
                futuresSentToOldConnection.values()
                        .forEach(f -> {
                            f.completeExceptionally(new CommunicationException("Connection is dead"));
                        });
                futuresSentToOldConnection.putAll(futures);
                futures.clear();

                oldConnection = currConnection;


                this.newServerList = newServerList;

                //do reconnect to update list
                die("The server list have been changed.", null);
            }
        } finally {
            initLock.unlock();
        }


        return newServerList;
    }

    @Override
    protected void connect(NodeCommunicationProvider communicationProvider) throws Exception {
        //region drop all registered selectors from channel
        if (currConnection != null) {
            SelectionKey registeredKey = currConnection.getChannel().keyFor(readSelector);
            if (registeredKey != null) {
                registeredKey.cancel();
            }
        }
        //endregion

        super.connect(communicationProvider);

        //region reregister selector
        if (readSelector != null) {
            try {
                readSelector.close();
            } catch (IOException ignored) {
            }
        }
        readSelector = Selector.open();

        if (oldConnection != null) {
            SelectionKey register = oldConnection.getChannel().
                    register(readSelector, SelectionKey.OP_READ, currConnection);
        }

        currConnection.getChannel().register(readSelector, SelectionKey.OP_READ, currConnection);
        //endregion
    }

    @Override
    protected TarantoolBinaryPackage readFromInstance() throws IOException, InterruptedException {

        readSelector.select();

        SelectionKey selectedKey = readSelector.selectedKeys().iterator().next();

        TarantoolInstanceConnection connection = (TarantoolInstanceConnection) selectedKey.attachment();
        ReadableByteChannel readChannel = connection
                .getReadChannel();

        //todo обработать случай, когда прочитали последний пакет из oldConnection инстанса
        return BinaryProtoUtils.readPacket(readChannel);
    }

    private void closeOldInstanceIfNeeded() {
        if (futuresSentToOldConnection.isEmpty()) {
            try {
                oldConnection.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    protected boolean isDead(CompletableFuture<?> q) {
        if ((state.getState() & CLOSED) != 0) {
            q.completeExceptionally(new CommunicationException("Connection is dead", thumbstone));
            return true;
        }
        Exception err = thumbstone;
        if (err != null) {
            return checkFail(q, err);
        }
        return false;
    }

    @Override
    protected CompletableFuture<?> doExec(Code code, Object[] args) {
        validateArgs(args);
        long sid = syncId.incrementAndGet();
        CompletableFuture<?> q = makeFuture(sid, code, args);

        if (isDead(q)) {
            return q;
        }
        futures.put(sid, q);
        if (isDead(q)) {
            futures.remove(sid);
            return q;
        }
        try {
            write(code, sid, null, args);
        } catch (Exception e) {
            futures.remove(sid);
            fail(q, e);
        }
        return q;
    }

    @Override
    protected void fail(CompletableFuture<?> q, Exception e) {
        checkFail(q, e);
    }

    protected boolean checkFail(CompletableFuture<?> q, Exception e) {
        assert q instanceof ExpirableOp<?>;
        if (!isTransientError(e) || ((ExpirableOp<?>)q).hasExpired(System.currentTimeMillis())) {
            q.completeExceptionally(e);
            return true;
        } else {
            assert retries != null;
            retries.put(((ExpirableOp<?>) q).getId(), (ExpirableOp<?>)q);
            return false;
        }
    }

    @Override
    protected void close(Exception e) {
        super.close(e);

        if (retries == null) {
            // May happen within constructor.
            return;
        }

        for (ExpirableOp<?> op : retries.values()) {
            op.completeExceptionally(e);
        }
    }

    protected boolean isTransientError(Exception e) {
        if (e instanceof CommunicationException) {
            return true;
        }
        if (e instanceof TarantoolException) {
            return ((TarantoolException)e).isTransient();
        }
        return false;
    }

    protected CompletableFuture<?> makeFuture(long id, Code code, Object...args) {
        int expireTime = ((TarantoolClusterClientConfig) config).operationExpiryTimeMillis;
        return new ExpirableOp(id, expireTime, code, args);
    }

    /**
     * Reconnect is over, schedule retries.
     */
    @Override
    protected void onReconnect() {
        if (retries == null || executor == null) {
            // First call is before the constructor finished. Skip it.
            return;
        }
        Collection<ExpirableOp<?>> futsToRetry = new ArrayList<ExpirableOp<?>>(retries.values());
        retries.clear();
        long now = System.currentTimeMillis();
        for (final ExpirableOp<?> fut : futsToRetry) {
            if (!fut.hasExpired(now)) {
                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        futures.put(fut.getId(), fut);
                        try {
                            //todo invoke sendToInstance method? (maybe not)
                            write(fut.getCode(), fut.getId(), null, fut.getArgs());
                        } catch (Exception e) {
                            futures.remove(fut.getId());
                            fail(fut, e);
                        }
                    }
                });
            }
        }
    }

    @Override
    protected void connectAndStartThreads() throws Exception {
        initLock.lock();
        try {
            super.connectAndStartThreads();
        } finally {
            initLock.unlock();
        }
    }

    /**
     * Holds operation code and arguments for retry.
     */
    private class ExpirableOp<V> extends CompletableFuture<V> {
        /** Moment in time when operation is not considered for retry. */
        final private long deadline;

        /**
         * A task identifier used in {@link TarantoolClientImpl#futures}.
         */
        final private long id;

        /**
         * Tarantool binary protocol operation code.
         */
        final private Code code;

        /** Arguments of operation. */
        final private Object[] args;

        /**
         *
         * @param id Sync.
         * @param expireTime Expiration time (relative) in ms.
         * @param code Tarantool operation code.
         * @param args Operation arguments.
         */
        ExpirableOp(long id, int expireTime, Code code, Object...args) {
            this.id = id;
            this.deadline = System.currentTimeMillis() + expireTime;
            this.code = code;
            this.args = args;
        }

        boolean hasExpired(long now) {
            return now > deadline;
        }

        public long getId() {
            return id;
        }

        public Code getCode() {
            return code;
        }

        public Object[] getArgs() {
            return args;
        }
    }
}
