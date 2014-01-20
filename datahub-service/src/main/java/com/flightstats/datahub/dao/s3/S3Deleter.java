package com.flightstats.datahub.dao.s3;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * This is a first go, does not handle restarts
 */
public class S3Deleter implements Runnable {
    private final static Logger logger = LoggerFactory.getLogger(S3Deleter.class);

    private String channelName;
    private String bucketName;
    private AmazonS3 s3Client;
    private long deleted = 0;

    public S3Deleter(String channelName, String bucketName, AmazonS3 s3Client) {
        this.channelName = channelName + "/";
        this.bucketName = bucketName;
        this.s3Client = s3Client;
    }

    @Override
    public void run() {
        while (delete()) {
            logger.debug("deleting more from " + channelName);
        }
        delete();
        logger.info("completed deletion of " + channelName + " deleting " + deleted + " items");
    }

    private boolean delete() {
        ListObjectsRequest request = new ListObjectsRequest();
        request.withBucketName(bucketName);
        request.withPrefix(channelName);
        ObjectListing listing = s3Client.listObjects(request);
        List<DeleteObjectsRequest.KeyVersion> keys = new ArrayList<>();
        for (S3ObjectSummary objectSummary : listing.getObjectSummaries()) {
            keys.add(new DeleteObjectsRequest.KeyVersion(objectSummary.getKey()));
        }
        if (keys.isEmpty()) {
            return false;
        }
        DeleteObjectsRequest multiObjectDeleteRequest = new DeleteObjectsRequest(bucketName);
        multiObjectDeleteRequest.setKeys(keys);
        try {
            s3Client.deleteObjects(multiObjectDeleteRequest);
            deleted += keys.size();
        } catch (MultiObjectDeleteException e) {
            logger.info("what happened? " + channelName, e);
            return true;
        }
        return listing.isTruncated();
    }
}
