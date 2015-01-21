package com.flightstats.hub.dao.dynamo;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.model.*;
import com.flightstats.hub.app.HubServices;
import com.flightstats.hub.dao.ChannelConfigurationDao;
import com.flightstats.hub.model.ChannelConfiguration;
import com.google.common.util.concurrent.AbstractIdleService;
import com.google.inject.Inject;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 *
 */
public class DynamoChannelConfigurationDao implements ChannelConfigurationDao {
    private final static Logger logger = LoggerFactory.getLogger(DynamoChannelConfigurationDao.class);

    private final AmazonDynamoDBClient dbClient;
    private final DynamoUtils dynamoUtils;

    @Inject
    public DynamoChannelConfigurationDao(AmazonDynamoDBClient dbClient, DynamoUtils dynamoUtils) {
        this.dbClient = dbClient;
        this.dynamoUtils = dynamoUtils;
        HubServices.register(new DynamoChannelConfigurationDaoInit());
    }

    @Override
    public ChannelConfiguration createChannel(ChannelConfiguration configuration) {
        updateChannel(configuration);
        return configuration;
    }

    @Override
    public void updateChannel(ChannelConfiguration config) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("key", new AttributeValue(config.getName()));
        item.put("date", new AttributeValue().withN(String.valueOf(config.getCreationDate().getTime())));
        item.put("ttlDays", new AttributeValue().withN(String.valueOf(config.getTtlDays())));
        if (!config.getTags().isEmpty()) {
            item.put("tags", new AttributeValue().withSS(config.getTags()));
        }
        if (StringUtils.isNotEmpty(config.getDescription())) {
            item.put("description", new AttributeValue(config.getDescription()));
        }
        if (StringUtils.isNotEmpty(config.getReplicationSource())) {
            item.put("replicationSource", new AttributeValue(config.getReplicationSource()));
        }
        PutItemRequest putItemRequest = new PutItemRequest()
                .withTableName(getTableName())
                .withItem(item);
        dbClient.putItem(putItemRequest);
    }

    @Override
    public void initialize() {
        createTable();
    }

    private void createTable() {

        CreateTableRequest request = new CreateTableRequest()
                .withTableName(getTableName())
                .withAttributeDefinitions(new AttributeDefinition("key", ScalarAttributeType.S))
                .withKeySchema(new KeySchemaElement("key", KeyType.HASH))
                .withProvisionedThroughput(new ProvisionedThroughput(50L, 10L));
        dynamoUtils.createTable(request);
    }

    @Override
    public boolean channelExists(String channelName) {
        return getChannelConfiguration(channelName) != null;
    }

    @Override
    public ChannelConfiguration getChannelConfiguration(String channelName) {
        HashMap<String, AttributeValue> keyMap = new HashMap<>();
        keyMap.put("key", new AttributeValue().withS(channelName));
        GetItemRequest getItemRequest = new GetItemRequest().withTableName(getTableName()).withKey(keyMap);
        try {
            GetItemResult result = dbClient.getItem(getItemRequest);
            if (result.getItem() == null) {
                return null;
            }
            return mapItem(result.getItem());
        } catch (ResourceNotFoundException e) {
            logger.info("channel not found " + e.getMessage());
            return null;
        }
    }

    private ChannelConfiguration mapItem(Map<String, AttributeValue> item) {
        ChannelConfiguration.Builder builder = ChannelConfiguration.builder()
                .withCreationDate(new Date(Long.parseLong(item.get("date").getN())))
                .withName(item.get("key").getS());
        if (item.get("ttlDays") != null) {
            builder.withTtlDays(Long.parseLong(item.get("ttlDays").getN()));
        }
        if (item.containsKey("description")) {
            builder.withDescription(item.get("description").getS());
        }
        if (item.containsKey("tags")) {
            builder.withTags(item.get("tags").getSS());
        }
        if (item.containsKey("replicationSource")) {
            builder.withReplicationSource(item.get("replicationSource").getS());
        }
        return builder.build();
    }

    @Override
    public Iterable<ChannelConfiguration> getChannels() {
        List<ChannelConfiguration> configurations = new ArrayList<>();
        ScanRequest scanRequest = new ScanRequest()
                .withTableName(getTableName());

        ScanResult result = dbClient.scan(scanRequest);
        mapItems(configurations, result);

        while (result.getLastEvaluatedKey() != null) {
            scanRequest.setExclusiveStartKey(result.getLastEvaluatedKey());
            result = dbClient.scan(scanRequest);
            mapItems(configurations, result);
        }

        return configurations;
    }

    private void mapItems(List<ChannelConfiguration> configurations, ScanResult result) {
        for (Map<String, AttributeValue> item : result.getItems()) {
            configurations.add(mapItem(item));
        }
    }

    @Override
    public void delete(String channelName) {
        Map<String, AttributeValue> key = new HashMap<>();
        key.put("key", new AttributeValue().withS(channelName));
        dbClient.deleteItem(new DeleteItemRequest(getTableName(), key));
    }

    public String getTableName() {
        return dynamoUtils.getTableName("channelMetaData");
    }

    private class DynamoChannelConfigurationDaoInit extends AbstractIdleService {
        @Override
        protected void startUp() throws Exception {
            initialize();
        }

        @Override
        protected void shutDown() throws Exception {
        }

    }
}
