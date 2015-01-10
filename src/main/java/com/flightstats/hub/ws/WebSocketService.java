package com.flightstats.hub.ws;

import com.flightstats.hub.app.HubMain;
import com.flightstats.hub.group.Group;
import com.flightstats.hub.group.GroupService;
import com.google.inject.Injector;
import org.apache.commons.lang3.RandomStringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.websocket.Session;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class WebSocketService {

    private final static Logger logger = LoggerFactory.getLogger(WebSocketService.class);
    private static WebSocketService instance;

    public static synchronized WebSocketService getInstance() {
        if (null == instance) {
            instance = new WebSocketService();
        }
        return instance;
    }

    private final GroupService groupService;
    private final Map<String, Session> sessionMap = new HashMap<>();

    public WebSocketService() {
        Injector injector = HubMain.getInjector();
        groupService = injector.getInstance(GroupService.class);
    }

    public void createCallback(Session session, String channel) {
        String id = session.getId();
        logger.info("creating callback {} {}", channel, id);
        sessionMap.put(id, session);
        String groupName = setGroupName(session, channel);
        //todo - gfm - 1/10/15 - fix these variables
        Group group = Group.builder()
                .channelUrl("http://localhost:9080/channel/" + channel)
                .callbackUrl("http://localhost:9080/ws/" + id)
                .parallelCalls(1)
                .name(groupName)
                .build();
        groupService.upsertGroup(group);
    }

    private String setGroupName(Session session, String channel) {
        Map<String, Object> userProperties = session.getUserProperties();
        String groupName = "WS_" + channel + "_" + RandomStringUtils.randomAlphanumeric(20);
        userProperties.put("groupName", groupName);
        return groupName;
    }

    private String getGroupName(Session session) {
        Map<String, Object> userProperties = session.getUserProperties();
        return (String) userProperties.get("groupName");
    }

    public void call(String id, String uri) {
        Session session = sessionMap.get(id);
        if (session == null) {
            logger.info("attempting to send to missing session {} {}", id, uri);
            return;
        }
        try {
            session.getBasicRemote().sendText(uri);
        } catch (IOException e) {
            logger.warn("unable to send to session " + id + " uri " + uri + " " + e.getMessage());
            close(session);
        } catch (Exception e) {
            logger.warn("unable to send to session " + id + " uri " + uri, e);
            close(session);
        }
    }

    public void close(Session session) {
        String groupName = getGroupName(session);
        logger.info("deleting group {}", groupName);
        groupService.delete(groupName);
        sessionMap.remove(session.getId());
    }
}
