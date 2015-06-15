package com.flightstats.hub.group;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flightstats.hub.cluster.CuratorLeader;
import com.flightstats.hub.cluster.LastContentKey;
import com.flightstats.hub.cluster.Leader;
import com.flightstats.hub.metrics.MetricsTimer;
import com.flightstats.hub.model.ContentKey;
import com.flightstats.hub.rest.RestClient;
import com.flightstats.hub.util.RuntimeInterruptedException;
import com.github.rholder.retry.RetryException;
import com.github.rholder.retry.Retryer;
import com.github.rholder.retry.RetryerBuilder;
import com.github.rholder.retry.WaitStrategies;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.newrelic.api.agent.Trace;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientHandlerException;
import com.sun.jersey.api.client.ClientResponse;
import org.apache.curator.framework.CuratorFramework;
import org.apache.zookeeper.KeeperException;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.ws.rs.core.MediaType;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("Convert2Lambda")
public class GroupCaller implements Leader {
    private final static Logger logger = LoggerFactory.getLogger(GroupCaller.class);
    public static final String GROUP_LAST_COMPLETED = "/GroupLastCompleted/";

    private final CuratorFramework curator;
    private final Provider<CallbackQueue> queueProvider;
    private final GroupService groupService;
    private final MetricsTimer metricsTimer;
    private final LastContentKey lastContentKey;
    private final GroupContentKeySet groupInProcess;
    private final GroupError groupError;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicBoolean deleteOnExit = new AtomicBoolean();

    private Group group;
    private CuratorLeader curatorLeader;
    private Client client;
    private ExecutorService executorService;
    private Semaphore semaphore;
    private AtomicBoolean hasLeadership;
    private Retryer<ClientResponse> retryer;
    private CallbackQueue callbackQueue;

    @Inject
    public GroupCaller(CuratorFramework curator, Provider<CallbackQueue> queueProvider,
                       GroupService groupService, MetricsTimer metricsTimer,
                       LastContentKey lastContentKey, GroupContentKeySet groupInProcess, GroupError groupError) {
        this.curator = curator;
        this.queueProvider = queueProvider;
        this.groupService = groupService;
        this.metricsTimer = metricsTimer;
        this.lastContentKey = lastContentKey;
        this.groupInProcess = groupInProcess;
        this.groupError = groupError;
    }

    public boolean tryLeadership(Group group) {
        logger.debug("starting group: " + group);
        this.group = group;
        executorService = Executors.newCachedThreadPool();
        semaphore = new Semaphore(group.getParallelCalls());
        curatorLeader = new CuratorLeader(getLeaderPath(), this, curator);
        curatorLeader.start();
        return true;
    }

    @Override
    public void takeLeadership(AtomicBoolean hasLeadership) {
        this.hasLeadership = hasLeadership;
        retryer = buildRetryer();
        logger.info("taking leadership " + group);
        Optional<Group> foundGroup = groupService.getGroup(group.getName());
        if (!foundGroup.isPresent()) {
            logger.info("group is missing, exiting " + group.getName());
            return;
        }
        this.client = RestClient.createClient(30, 120, true);
        callbackQueue = queueProvider.get();
        try {
            ContentKey startingKey = group.getStartingKey();
            if (null == startingKey) {
                startingKey = new ContentKey();
            }
            ContentKey lastCompletedKey = getLastCompleted(startingKey);
            logger.info("last completed at {} {}", lastCompletedKey, group.getName());
            if (hasLeadership.get()) {
                sendInProcess(lastCompletedKey);
                callbackQueue.start(group, lastCompletedKey);
                while (hasLeadership.get()) {
                    Optional<ContentKey> nextOptional = callbackQueue.next();
                    if (nextOptional.isPresent()) {
                        send(nextOptional.get());
                    }
                }
            }
        } catch (RuntimeInterruptedException | InterruptedException e) {
            logger.info("saw InterruptedException for " + group.getName());
        } finally {
            ContentKey lastCompletedKey = getLastCompleted(ContentKey.NONE);
            logger.info("stopping last completed at {} {}", lastCompletedKey, group.getName());
            closeQueue();
            if (deleteOnExit.get()) {
                delete();
            }
        }
    }

    private void sendInProcess(ContentKey lastCompletedKey) throws InterruptedException {
        Set<ContentKey> inProcessSet = groupInProcess.getSet(group.getName());
        logger.trace("sending in process {} to {}", inProcessSet, group.getName());
        for (ContentKey toSend : inProcessSet) {
            if (toSend.compareTo(lastCompletedKey) < 0) {
                send(toSend);
            } else {
                groupInProcess.remove(group.getName(), toSend);
            }
        }
    }

    private void send(ContentKey key) throws InterruptedException {
        logger.trace("sending {} to {}", key, group.getName());
        semaphore.acquire();
        executorService.submit(new Callable<Object>() {
            @Trace(metricName = "GroupCaller", dispatcher = true)
            @Override
            public Object call() throws Exception {
                groupInProcess.add(group.getName(), key);
                try {
                    makeTimedCall(createResponse(key));
                    lastContentKey.updateIncrease(key, group.getName(), GROUP_LAST_COMPLETED);
                    groupInProcess.remove(group.getName(), key);
                    logger.trace("completed {} call to {} ", key, group.getName());
                } catch (Exception e) {
                    logger.warn("exception sending " + key + " to " + group.getName(), e);
                } finally {
                    semaphore.release();
                }
                return null;
            }
        });
    }

    private ObjectNode createResponse(ContentKey key) {
        ObjectNode response = mapper.createObjectNode();
        response.put("name", group.getName());
        ArrayNode uris = response.putArray("uris");
        uris.add(group.getChannelUrl() + "/" + key.toUrl());
        return response;
    }

    private void makeTimedCall(final ObjectNode response) throws Exception {
        metricsTimer.time("group." + group.getName() + ".post", new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                return metricsTimer.time("group.ALL.post", new Callable<Object>() {
                    @Override
                    public Object call() throws ExecutionException, RetryException {
                        makeCall(response);
                        return null;
                    }
                });
            }
        });
    }

    private void makeCall(final ObjectNode response) throws ExecutionException, RetryException {
        retryer.call(new Callable<ClientResponse>() {
            @Override
            public ClientResponse call() throws Exception {
                if (!hasLeadership.get()) {
                    logger.debug("not leader {} {} {}", group.getCallbackUrl(), group.getName(), response);
                    return null;
                }
                String postId = UUID.randomUUID().toString();
                logger.debug("calling {} {} {}", group.getCallbackUrl(), response, postId);
                return client.resource(group.getCallbackUrl())
                        .type(MediaType.APPLICATION_JSON_TYPE)
                        .header("post-id", postId)
                        .post(ClientResponse.class, response.toString());
            }
        });
    }

    public void exit(boolean delete) {
        String name = group.getName();
        logger.info("exiting group " + name + " deleting " + delete);
        deleteOnExit.set(delete);
        curatorLeader.close();
        closeQueue();
        try {
            executorService.shutdown();
            logger.info("awating termination " + name);
            executorService.awaitTermination(1, TimeUnit.SECONDS);
            logger.info("terminated " + name);
        } catch (InterruptedException e) {
            logger.warn("unable to stop?", e);
        }
    }

    private void closeQueue() {
        try {
            if (callbackQueue != null) {
                callbackQueue.close();
            }
        } catch (Exception e) {
            logger.warn("unable to close callbackQueue", e);
        }
    }

    private String getLeaderPath() {
        return "/GroupLeader/" + group.getName();
    }

    public ContentKey getLastCompleted(ContentKey defaultKey) {
        return lastContentKey.get(group.getName(), defaultKey, GROUP_LAST_COMPLETED);
    }

    private void delete() {
        logger.info("deleting " + group.getName());
        groupInProcess.delete(group.getName());
        lastContentKey.delete(group.getName(), GROUP_LAST_COMPLETED);
        groupError.delete(group.getName());
        logger.info("deleted " + group.getName());
    }

    public boolean deleteIfReady() {
        if (isReadyToDelete()) {
            deleteAnyway();
            return true;
        }
        return false;
    }

    void deleteAnyway() {
        try {
            debugLeaderPath();
            curator.delete().deletingChildrenIfNeeded().forPath(getLeaderPath());
        } catch (Exception e) {
            logger.warn("unable to delete leader path " + group.getName(), e);
        }
        delete();
    }

    private void debugLeaderPath() {
        try {
            String leaderPath = getLeaderPath();
            List<String> children = curator.getChildren().forPath(leaderPath);
            for (String child : children) {
                String path = leaderPath + "/" + child;
                byte[] bytes = curator.getData().forPath(path);
                logger.info("found child {} {} ", new String(bytes), path);
            }
        } catch (KeeperException.NoNodeException ignore) {
            //do nothing
        } catch (Exception e) {
            logger.info("unexpected exception " + group.getName(), e);
        }
    }

    private boolean isReadyToDelete() {
        try {
            List<String> children = curator.getChildren().forPath(getLeaderPath());
            return children.isEmpty();
        } catch (KeeperException.NoNodeException ignore) {
            return true;
        } catch (Exception e) {
            logger.warn("unexpected exception " + group.getName(), e);
            return true;
        }
    }

    private Retryer<ClientResponse> buildRetryer() {
        return RetryerBuilder.<ClientResponse>newBuilder()
                .retryIfException(new Predicate<Throwable>() {
                    @Override
                    public boolean apply(@Nullable Throwable throwable) {
                        if (throwable != null) {
                            groupError.add(group.getName(), new DateTime() + " " + throwable.getMessage());
                            if (throwable.getClass().isAssignableFrom(ClientHandlerException.class)) {
                                logger.info("got ClientHandlerException trying to call client back " + throwable.getMessage());
                            } else {
                                logger.info("got throwable trying to call client back ", throwable);
                            }
                        }
                        return throwable != null;
                    }
                })
                .retryIfResult(new Predicate<ClientResponse>() {
                    @Override
                    public boolean apply(@Nullable ClientResponse response) {
                        if (response == null) return true;
                        try {
                            boolean failure = response.getStatus() != 200;
                            if (failure) {
                                groupError.add(group.getName(), new DateTime() + " " + response.toString());
                                logger.info("unable to send to " + response);
                            }
                            return failure;
                        } finally {
                            close(response);
                        }
                    }

                    private void close(ClientResponse response) {
                        try {
                            response.close();
                        } catch (ClientHandlerException e) {
                            logger.info("exception closing response", e);
                        }
                    }
                })
                .withWaitStrategy(WaitStrategies.exponentialWait(1000, 1, TimeUnit.MINUTES))
                .withStopStrategy(new GroupStopStrategy(hasLeadership))
                .build();
    }

    public List<String> getErrors() {
        return groupError.get(group.getName());
    }

    public List<ContentKey> getInFlight() {
        return new ArrayList<>(new TreeSet<>(groupInProcess.getSet(group.getName())));
    }
}
