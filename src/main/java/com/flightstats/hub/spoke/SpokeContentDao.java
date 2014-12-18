package com.flightstats.hub.spoke;

import com.flightstats.hub.dao.ContentDao;
import com.flightstats.hub.model.Content;
import com.flightstats.hub.model.ContentKey;
import com.flightstats.hub.model.DirectionQuery;
import com.flightstats.hub.model.Trace;
import com.flightstats.hub.util.TimeUtil;
import com.google.inject.Inject;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * This is the entry point in the Hub's storage system, Spoke.
 * <p>
 * It is called in-process on the originating Hub server, and this class will
 * call the Spoke servers in the cluster.
 */
public class SpokeContentDao implements ContentDao {

    private final static Logger logger = LoggerFactory.getLogger(SpokeContentDao.class);

    private final RemoteSpokeStore spokeStore;

    @Inject
    public SpokeContentDao(RemoteSpokeStore spokeStore) {
        this.spokeStore = spokeStore;
    }

    @Override
    public ContentKey write(String channelName, Content content) {
        content.getTraces().add(new Trace("SpokeContentDao.start"));
        try {
            byte[] payload = SpokeMarshaller.toBytes(content);
            ContentKey key = new ContentKey();
            logger.trace("writing key {} to channel {}", key, channelName);
            content.setContentKey(key);
            String path = getPath(channelName, key);
            content.getTraces().add(new Trace("SpokeContentDao.marshalled"));
            if (!spokeStore.write(path, payload, content)) {
                logger.warn("failed to write to all for " + path);
            }
            content.getTraces().add(new Trace("SpokeContentDao.end"));
            return key;
        } catch (Exception e) {
            content.getTraces().add(new Trace("SpokeContentDao", "error", e.getMessage()));
            logger.warn("what's up?", e);
            return null;
        }
    }

    private String getPath(String channelName, ContentKey key) {
        return channelName + "/" + key.toUrl();
    }

    @Override
    public Content read(String channelName, ContentKey key) {
        String path = getPath(channelName, key);
        try {
            return spokeStore.read(path, key);
        } catch (Exception e) {
            logger.warn("unable to get data: " + path, e);
            return null;
        }
    }

    @Override
    public Collection<ContentKey> queryByTime(String channelName, DateTime startTime, TimeUtil.Unit unit) {
        String timePath = unit.format(startTime);
        try {
            return spokeStore.readTimeBucket(channelName, timePath);
        } catch (Exception e) {
            logger.warn("what happened? " + channelName + " " + startTime + " " + unit, e);
        }
        return Collections.emptyList();
    }

    @Override
    public Collection<ContentKey> getKeys(DirectionQuery query) {
        Set<ContentKey> orderedKeys = new TreeSet<>();
        ContentKey startKey = query.getContentKey();
        //queryByTime returns oldest to newest
        DateTime time = query.isStable() ? TimeUtil.stable() : TimeUtil.now();
        Collection<ContentKey> contentKeys = new TreeSet<>(queryByTime(query.getChannelName(), time, TimeUtil.Unit.DAYS));
        if (query.isNext()) {
            for (ContentKey contentKey : contentKeys) {
                if (contentKey.compareTo(startKey) > 0) {
                    orderedKeys.add(contentKey);
                    logger.info("adding {}", contentKey);
                    if (orderedKeys.size() == query.getCount()) {
                        return orderedKeys;
                    }
                }
            }
        } else {
            //todo - gfm - 12/17/14 - handle previous too
        }
        return orderedKeys;
    }

    @Override
    public void delete(String channelName) {
        try {
            spokeStore.delete(channelName);
        } catch (Exception e) {
            logger.warn("unable to delete " + channelName, e);
        }
    }

    @Override
    public void initialize() {
        //do anything?
    }
}
