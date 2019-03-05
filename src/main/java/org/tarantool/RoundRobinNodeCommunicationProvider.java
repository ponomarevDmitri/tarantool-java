package org.tarantool;

import org.tarantool.server.*;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;
import java.util.stream.*;

public class RoundRobinNodeCommunicationProvider implements NodeCommunicationProvider {

    /** Timeout to establish socket connection with an individual server. */
    private final int timeout; // 0 is infinite.

    /** Limit of retries. */
    private int retriesLimit = -1; // No-limit.


    private final String clusterUsername;
    private final String clusterPassword;

    private TarantoolInstanceConnection currentConnection;

    private TarantoolInstanceInfo[] nodes;
    private int pos = 0;

    public RoundRobinNodeCommunicationProvider(String[] slaveHosts, String username, String password, int timeout) {
        this.timeout = timeout;
        if (slaveHosts == null || slaveHosts.length < 1) {
            throw new IllegalArgumentException("slave hosts is null ot empty");
        }

        clusterUsername = username;
        clusterPassword = password;

        setNodes(slaveHosts);
    }

    private void setNodes(String[] instanceAddresses) {
        nodes = new TarantoolInstanceInfo[instanceAddresses.length];
        for (int i = 0; i < instanceAddresses.length; i++) {
            String slaveHostAddress = instanceAddresses[i];
            nodes[i] = TarantoolInstanceInfo.create(slaveHostAddress, clusterUsername, clusterPassword);
        }

        pos = 0;
    }

    public void updateNodes(List<TarantoolInstanceInfo> instanceAddresses) {
        if (instanceAddresses == null) {
            throw new IllegalArgumentException("instanceAddresses can not be null");
        }

        this.nodes = (TarantoolInstanceInfo[]) instanceAddresses.toArray();
        pos = 0;
    }


    /**
     * @return Non-empty list of round-robined nodes
     */
    public TarantoolInstanceInfo[] getNodes() {
        return nodes;
    }

    /** {@inheritDoc} */
    public TarantoolInstanceConnection connectNextNode() {
        int attempts = getAddressCount();
        long deadline = System.currentTimeMillis() + timeout * attempts;
        while (!Thread.currentThread().isInterrupted()) {
            TarantoolInstanceConnection connection = null;
            try {
                TarantoolInstanceInfo tarantoolInstanceInfo = getNextNode();
                connection = TarantoolInstanceConnection.connect(tarantoolInstanceInfo);
                return connection;
            } catch (IOException e) {
                if (connection != null) {
                    try {
                        connection.close();
                    } catch (IOException ignored) {
                        // No-op.
                    }
                }
                long now = System.currentTimeMillis();
                if (deadline <= now) {
                    throw new CommunicationException("Connection time out.", e);
                }
                if (--attempts == 0) {
                    // Tried all addresses without any lack, but still have time.
                    attempts = getAddressCount();
                    try {
                        Thread.sleep((deadline - now) / attempts);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }
        throw new CommunicationException("Thread interrupted.", new InterruptedException());
    }

    /**
     * @return Socket address to use for the next reconnection attempt.
     */
    protected TarantoolInstanceInfo getNextNode() {
        TarantoolInstanceInfo res = nodes[pos];
        pos = (pos + 1) % nodes.length;
        return res;
    }


    /**
     * @return Number of configured addresses.
     */
    protected int getAddressCount() {
        return nodes.length;
    }


    @Override
    public TarantoolInstanceConnection connect() {
        currentConnection = connectNextNode();
        return currentConnection;
    }

    public void writeBuffer(ByteBuffer byteBuffer) throws IOException {
        SocketChannel channel2Write = getChannel();
        BinaryProtoUtils.writeFully(channel2Write, byteBuffer);
    }

    public TarantoolBinaryPackage readPackage() throws IOException {
        SocketChannel channel2Read = getChannel();
        return BinaryProtoUtils.readPacket(channel2Read);
    }

    private SocketChannel getChannel() {
        if (currentConnection == null) {
            throw new IllegalStateException("Not initialized");
        }
        return currentConnection.getChannel();
    }

    @Override
    public String getDescription() {
        if (currentConnection != null) {
            return currentConnection.getNodeInfo().getSocketAddress().toString();
        } else {
            return "Unconnected. Available nodes [" + Arrays.stream(nodes)
                    .map(instanceInfo -> instanceInfo.getSocketAddress().toString())
                    .collect(Collectors.joining(", "));
        }
    }
}