package org.apache.storm.messaging.jxio;

import org.accelio.jxio.EventName;
import org.accelio.jxio.jxioConnection.JxioConnection;
import org.apache.storm.Config;
import org.apache.storm.grouping.Load;
import org.apache.storm.messaging.ConnectionWithStatus;
import org.apache.storm.messaging.IConnectionCallback;
import org.apache.storm.messaging.TaskMessage;
import org.apache.storm.metric.api.IStatefulObject;
import org.apache.storm.utils.StormBoundedExponentialBackoffRetry;
import org.apache.storm.utils.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.util.*;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class Client extends ConnectionWithStatus implements IStatefulObject {
    private static final long PENDING_MESSAGES_FLUSH_TIMEOUT_MS = 600000L;
    private static final long PENDING_MESSAGES_FLUSH_INTERVAL_MS = 1000L;
    private static final long NO_DELAY_MS = 0L;

    private static final Logger LOG = LoggerFactory.getLogger(Client.class);
    private JxioConnection jxClient;
    private OutputStream output;
    private URI uri;
    private Object writeLock = new Object();
    private InputStream input;
    private ScheduledThreadPoolExecutor scheduler;
    private Map stormConf;
    private HashMap<String, Integer> jxioConfigs = new HashMap<>();
    protected String dstAddressPrefixedName;
    private static final String PREFIX = "JXIO-Client-";
    private static final Timer timer = new Timer("JXIO-SessionAlive-Timer", true);
    private final Context context;
    private final StormBoundedExponentialBackoffRetry retryPolicy;

    private volatile boolean closing = false;

    /**
     * Periodically checks for connected channel in order to avoid loss
     * of messages
     */
    private final long SESSION_ALIVE_INTERVAL_MS = 30000L;

    /**
     * Number of messages that could not be sent to the remote destination.
     */
    private final AtomicInteger messagesLost = new AtomicInteger(0);

    /**
     * Total number of connection attempts.
     */
    private final AtomicInteger totalConnectionAttempts = new AtomicInteger(0);

    /**
     * Number of messages successfully sent to the remote destination.
     */
    private final AtomicInteger messagesSent = new AtomicInteger(0);

    /**
     * Number of messages buffered in memory.
     */
    private final AtomicLong pendingMessages = new AtomicLong(0);

    /**
     * Whether the SASL channel is ready.
     */
    private final AtomicBoolean saslChannelReady = new AtomicBoolean(false);

    /**
     * Number of connection attempts since the last disconnect.
     */
    private final AtomicInteger connectionAttempts = new AtomicInteger(0);

    Client(Map stormConf, ScheduledThreadPoolExecutor scheduler, String host, int port, Context context) {
        this.stormConf = stormConf;
        closing = false;
        this.scheduler = scheduler;
        this.context = context;
        // if SASL authentication is disabled, saslChannelReady is initialized as true; otherwise false
        saslChannelReady.set(!Utils.getBoolean(stormConf.get(Config.STORM_MESSAGING_NETTY_AUTHENTICATION), false));

        jxioConfigs.put("msgpool", Utils.getInt(stormConf.get(Config.STORM_MEESAGING_JXIO_MSGPOOL_BUFFER_SIZE)));
        jxioConfigs.put("is_msgpool_count", Utils.getInt(stormConf.get(Config.STORM_MESSAGING_JXIO_CLIENT_INPUT_BUFFER_COUNT)));
        jxioConfigs.put("os_msgpool_count", Utils.getInt(stormConf.get(Config.STORM_MESSAGING_JXIO_CLIENT_OUTPUT_BUFFER_COUNT)));

        int maxReconnectionAttempts = Utils.getInt(stormConf.get(Config.STORM_MESSAGING_NETTY_MAX_RETRIES));
        int minWaitMs = Utils.getInt(stormConf.get(Config.STORM_MESSAGING_NETTY_MIN_SLEEP_MS));
        int maxWaitMs = Utils.getInt(stormConf.get(Config.STORM_MESSAGING_NETTY_MAX_SLEEP_MS));
        retryPolicy = new StormBoundedExponentialBackoffRetry(minWaitMs, maxWaitMs, maxReconnectionAttempts);

        try {
            LOG.info("host: " + host + ", port: " + port);
            LOG.info("getLocalIp: " + getLocalServerIp());

            String[] hostname = host.split(".");
            if (hostname[1] == null) {
                hostname[0] = host;
                LOG.info("hostname convert to " + hostname);
            } else if (hostname[1].equals("eth")) {
                LOG.info("host is " + host + ", convert to " + hostname[0].concat(".ib"));
            } else {
                LOG.info("host is " + host + ", so remain this");
            }

            LOG.info("now {}:{}",hostname[0], port);
            uri = new URI(String.format("rdma://%s:%s", hostname[0], port));
            dstAddressPrefixedName = prefixedName(uri);
        } catch (URISyntaxException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        try {
            jxClient = new JxioConnection(uri, jxioConfigs);
            scheduleConnect(NO_DELAY_MS);
        } catch (ConnectException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    private String prefixedName(URI uri) {
        if (null != uri) {
            return PREFIX + uri.toString();
        }
        return "";
    }

    /**
     * This thread helps us to check for channel connection periodically.
     * This is performed just to know whether the destination address
     * is alive or attempts to refresh connections if not alive. This
     * solution is better than what we have now in case of a bad channel.
     */
    private void launchChannelAliveThread() {
        // netty TimerTask is already defined and hence a fully
        // qualified name
        timer.schedule(new java.util.TimerTask() {
            public void run() {
                try {
                    LOG.debug("running timer task, address {}", uri);
                    if (closing) {
                        this.cancel();
                        return;
                    }

                    if (!jxClient.isConnected())
                        scheduleConnect(NO_DELAY_MS);

                } catch (Exception exp) {
                    LOG.error("channel connection error {}", exp);
                }
            }
        }, 1000L, SESSION_ALIVE_INTERVAL_MS);
    }

    @Override
    public void registerRecv(IConnectionCallback cb) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Client connection should not receive any messages");
    }

    @Override
    public void sendLoadMetrics(Map<Integer, Double> taskToLoad) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Client connection should not receive any messages");

    }

    @Override
    public void send(int taskId, byte[] payload) {
        // TODO Auto-generated method stub
        TaskMessage msg = new TaskMessage(taskId, payload);
        List<TaskMessage> wrapper = new ArrayList<TaskMessage>(1);
        wrapper.add(msg);
        send(wrapper.iterator());
    }

    @Override
    public void send(Iterator<TaskMessage> msgs) {
        // TODO Auto-generated method stub

        if (closing) {
            int numMessages = iteratorSize(msgs);
            LOG.error("discarding {} messages because the Netty client to {} is being closed", numMessages,
                    dstAddressPrefixedName);
            return;
        }

        if (!hasMessages(msgs)) {
            return;
        }

        if (output == null) {
            dropMessages(msgs);
            return;
        }

        synchronized (writeLock) {
            while (msgs.hasNext()) {
                try {
                    output.write(msgs.next().serialize().array());
                    pendingMessages.incrementAndGet();
                    messagesSent.incrementAndGet();
                } catch (IOException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                    pendingMessages.decrementAndGet();
                    messagesLost.incrementAndGet();
                }
            }
        }

    }

    private boolean hasMessages(Iterator<TaskMessage> msgs) {
        return msgs != null && msgs.hasNext();
    }

    private void dropMessages(Iterator<TaskMessage> msgs) {
        // We consume the iterator by traversing and thus "emptying" it.
        int msgCount = iteratorSize(msgs);
        messagesLost.getAndAdd(msgCount);
    }

    private int iteratorSize(Iterator<TaskMessage> msgs) {
        int size = 0;
        if (msgs != null) {
            while (msgs.hasNext()) {
                size++;
                msgs.next();
            }
        }
        return size;
    }

    @Override
    public Map<Integer, Load> getLoad(Collection<Integer> tasks) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void close() {
        // TODO Auto-generated method stub
        if (!closing) {
            LOG.info("closing JXIO Client {}", dstAddressPrefixedName);
            context.removeClient(uri.getHost(), uri.getPort());
            //Set Closing to true to prevent any further reconnection attempts.
            closing = true;
            waitForPendingMessagesToBeSent();
            jxClient.disconnect();
        }
    }

    private void waitForPendingMessagesToBeSent() {
        LOG.info("waiting up to {} ms to send {} pending messages to {}",
                PENDING_MESSAGES_FLUSH_TIMEOUT_MS, pendingMessages.get(), dstAddressPrefixedName);
        long totalPendingMsgs = pendingMessages.get();
        long startMs = System.currentTimeMillis();
        while (pendingMessages.get() != 0) {
            try {
                long deltaMs = System.currentTimeMillis() - startMs;
                if (deltaMs > PENDING_MESSAGES_FLUSH_TIMEOUT_MS) {
                    LOG.error("failed to send all pending messages to {} within timeout, {} of {} messages were not " +
                            "sent", dstAddressPrefixedName, pendingMessages.get(), totalPendingMsgs);
                    break;
                }
                Thread.sleep(PENDING_MESSAGES_FLUSH_INTERVAL_MS);
            } catch (InterruptedException e) {
                break;
            }
        }

    }

    /**
     * Note:  Storm will check via this method whether a worker can be activated safely during the initial startup of a
     * topology.  The worker will only be activated once all of the its connections are ready.
     */
    @Override
    public Status status() {
        // TODO Auto-generated method stub
        if (closing) {
            return Status.Closed;
        } else if (jxClient.isConnected()) {
            return Status.Connecting;
        } else {
            if (saslChannelReady.get()) {
                return Status.Ready;
            } else {
                return Status.Connecting; // need to wait until sasl channel is also ready
            }
        }
    }


    private boolean reconnectingAllowed() {
        return !closing;
    }

    private void scheduleConnect(long delayMs) {
        scheduler.schedule(new Connect(), delayMs, TimeUnit.MILLISECONDS);
    }


    public Object getState() {
        LOG.debug("Getting metrics for client connection to {}", dstAddressPrefixedName);
        HashMap<String, Object> ret = new HashMap<String, Object>();
        ret.put("reconnects", totalConnectionAttempts.getAndSet(0));
        ret.put("sent", messagesSent.getAndSet(0));
        ret.put("pending", pendingMessages.get());
        ret.put("lostOnSend", messagesLost.getAndSet(0));
        ret.put("dest", uriToString(uri));
        String src = getLocalServerIp();
        if (src != null) {
            ret.put("src", src);
        }
        return ret;
    }

    private String getLocalServerIp() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements(); ) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && !inetAddress.isLinkLocalAddress() && inetAddress.isSiteLocalAddress()) {
                        return inetAddress.getHostAddress().toString();
                    }
                }
            }
        } catch (SocketException ex) {
        }
        return null;
    }

    private String uriToString(URI uri) {
        return (uri.getHost() + ":" + uri.getPort());
    }

    private class Connect implements Runnable {
        public Connect() {
        }

        private void reschedule() {
            jxClient.disconnect();
            try {
                jxClient = new JxioConnection(uri, jxioConfigs);
            } catch (ConnectException e) {
                e.printStackTrace();
            }
            scheduleConnect(5000L);
        }

        @Override
        public void run() {
            if (reconnectingAllowed()) {
                try {
                    input = jxClient.getInputStream();
                    output = jxClient.getOutputStream();
                } catch (ConnectException e) {
                    e.printStackTrace();
                }
                if (jxClient.osCon.connectErrorType == EventName.SESSION_CLOSED || jxClient.osCon.connectErrorType == EventName.SESSION_REJECT ||
                        jxClient.osCon.connectErrorType == EventName.SESSION_ERROR) {
                    reschedule();
                }
            } else {
                close();
                throw new RuntimeException("Giving up to scheduleConnect to " + dstAddressPrefixedName + " after " +
                        connectionAttempts + " failed attempts. " + messagesLost.get() + " messages were lost");
            }
        }
    }
}
