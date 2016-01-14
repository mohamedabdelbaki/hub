package com.flightstats.hub.stream;

import com.flightstats.hub.dao.ContentService;
import com.flightstats.hub.model.ChannelContentKey;
import com.flightstats.hub.model.Content;
import com.flightstats.hub.util.HubUtils;
import com.google.common.base.Optional;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.glassfish.jersey.media.sse.EventOutput;
import org.glassfish.jersey.media.sse.OutboundEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class StreamService {

    private final static Logger logger = LoggerFactory.getLogger(StreamService.class);

    @Inject
    private ContentService contentService;
    @Inject
    private HubUtils hubUtils;
    private Map<String, CallbackStream> outputStreamMap = new ConcurrentHashMap<>();

    public void getAndSendData(String uri, String id) {
        logger.trace("got uri {} {}", uri, id);
        ChannelContentKey key = ChannelContentKey.fromUrl(uri);
        if (key != null) {
            Optional<Content> optional = contentService.getValue(key.getChannel(), key.getContentKey());
            if (optional.isPresent()) {
                sendData(id, optional.get());
            }
        }
    }

    private void sendData(String id, Content content) {
        try {
            CallbackStream callbackStream = outputStreamMap.get(id);
            if (callbackStream == null) {
                logger.info("unable to find id {}", id);
                unregister(id);
            } else {
                OutboundEvent.Builder eventBuilder = new OutboundEvent.Builder();
                //todo - gfm - 1/14/16 - add encoding
                eventBuilder.data(byte[].class, content.getData());
                callbackStream.getEventOutput().write(eventBuilder.build());
                logger.trace("sent {} content {}", id, content.getContentKey());
            }
        } catch (Exception e) {
            logger.warn("unable to send " + id + " " + content.getContentKey(), e);
            unregister(id);
        }
    }

    public void register(String channel, EventOutput eventOutput) {
        CallbackStream callbackStream = new CallbackStream(channel, eventOutput, hubUtils);
        logger.info("registering stream {}", callbackStream.getGroupName());
        outputStreamMap.put(callbackStream.getGroupName(), callbackStream);
        callbackStream.start();
    }

    public void unregister(String id) {
        logger.info("unregistering stream {}", id);
        CallbackStream remove = outputStreamMap.remove(id);
        if (null != remove) {
            remove.stop();
        }
    }

}
