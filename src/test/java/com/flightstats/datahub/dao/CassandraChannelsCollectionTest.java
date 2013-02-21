package com.flightstats.datahub.dao;

import com.flightstats.datahub.model.ChannelConfiguration;
import com.flightstats.datahub.util.TimeProvider;
import me.prettyprint.cassandra.model.HColumnImpl;
import me.prettyprint.cassandra.serializers.StringSerializer;
import me.prettyprint.hector.api.Keyspace;
import me.prettyprint.hector.api.Serializer;
import me.prettyprint.hector.api.beans.HColumn;
import me.prettyprint.hector.api.mutation.Mutator;
import me.prettyprint.hector.api.query.ColumnQuery;
import me.prettyprint.hector.api.query.QueryResult;
import org.junit.Test;

import java.util.Date;

import static com.flightstats.datahub.dao.CassandraChannelsCollection.CHANNELS_COLUMN_FAMILY_NAME;
import static com.flightstats.datahub.dao.CassandraChannelsCollection.CHANNELS_ROW_KEY;
import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;
import static org.mockito.Mockito.*;

public class CassandraChannelsCollectionTest {

    @Test
    public void testCreateChannel() throws Exception {
        String channelName = "arturo";
        final Date creationDate = new Date(99999);
        ChannelConfiguration expected = new ChannelConfiguration(channelName, creationDate, null);
        HColumn<String, ChannelConfiguration> column = new HColumnImpl<String, ChannelConfiguration>(StringSerializer.get(), mock(Serializer.class));

        CassandraConnector connector = mock(CassandraConnector.class);
        HectorFactoryWrapper hector = mock(HectorFactoryWrapper.class);
        Mutator<String> mutator = mock(Mutator.class);
        Serializer<ChannelConfiguration> valueSerializer = mock(Serializer.class);
        TimeProvider timeProvider = mock(TimeProvider.class);

        when(connector.buildMutator(StringSerializer.get())).thenReturn(mutator);
        when(hector.createColumn(channelName, expected, StringSerializer.get(), valueSerializer)).thenReturn(column);
        when(timeProvider.getDate()).thenReturn(creationDate);

        CassandraChannelsCollection testClass = new CassandraChannelsCollection(connector, valueSerializer, hector, timeProvider);

        ChannelConfiguration result = testClass.createChannel(channelName);

        assertEquals(expected, result);
        verify(connector).createColumnFamilyIfNeeded(CHANNELS_COLUMN_FAMILY_NAME);
        verify(connector).createColumnFamilyIfNeeded(channelName);
        verify(mutator).insert(CHANNELS_ROW_KEY, CHANNELS_COLUMN_FAMILY_NAME, column);
    }

    @Test
    public void testChannelExists() throws Exception {

        String channelName = "foo";
        ChannelConfiguration channelConfiguration = new ChannelConfiguration(channelName, new Date(), null);

        CassandraConnector connector = mock(CassandraConnector.class);
        Keyspace keyspace = mock(Keyspace.class);
        HectorFactoryWrapper hector = mock(HectorFactoryWrapper.class);
        Serializer<ChannelConfiguration> channelConfigSerializer = mock(Serializer.class);
        ColumnQuery<String, String, ChannelConfiguration> query = mock(ColumnQuery.class);
        QueryResult<HColumn<String, ChannelConfiguration>> queryResult = mock(QueryResult.class);
        HColumn<String, ChannelConfiguration> column = mock(HColumn.class);

        when(connector.getKeyspace()).thenReturn(keyspace);
        when(hector.createColumnQuery(keyspace, StringSerializer.get(), StringSerializer.get(), channelConfigSerializer)).thenReturn(query);
        when(query.setName(channelName)).thenReturn(query);
        when(query.setKey(CHANNELS_ROW_KEY)).thenReturn(query);
        when(query.setColumnFamily(CHANNELS_COLUMN_FAMILY_NAME)).thenReturn(query);
        when(query.execute()).thenReturn(queryResult);
        when(queryResult.get()).thenReturn(column);
        when(column.getValue()).thenReturn(channelConfiguration);


        CassandraChannelsCollection testClass = new CassandraChannelsCollection(connector, channelConfigSerializer, hector, null);
        boolean result = testClass.channelExists(channelName);
        assertTrue(result);
    }

    @Test
    public void testGetChannelConfiguration() throws Exception {
        ChannelConfiguration expected = new ChannelConfiguration("thechan", new Date(), null);

        CassandraConnector connector = mock(CassandraConnector.class);
        Keyspace keyspace = mock(Keyspace.class);
        HectorFactoryWrapper hector = mock(HectorFactoryWrapper.class);
        Serializer<ChannelConfiguration> channelConfigSerializer = mock(Serializer.class);
        ColumnQuery<String, String, ChannelConfiguration> columnQuery = mock(ColumnQuery.class);
        QueryResult<HColumn<String, ChannelConfiguration>> queryResult = mock(QueryResult.class);
        HColumn<String, ChannelConfiguration> column = mock(HColumn.class);

        when(columnQuery.setName(anyString())).thenReturn(columnQuery);
        when(columnQuery.setKey(anyString())).thenReturn(columnQuery);
        when(columnQuery.setColumnFamily(anyString())).thenReturn(columnQuery);
        when(connector.getKeyspace()).thenReturn(keyspace);
        when(hector.createColumnQuery(keyspace, StringSerializer.get(), StringSerializer.get(), channelConfigSerializer)).thenReturn(columnQuery);
        when(columnQuery.execute()).thenReturn(queryResult);
        when(queryResult.get()).thenReturn(column);
        when(column.getValue()).thenReturn(expected);

        CassandraChannelsCollection testClass = new CassandraChannelsCollection(connector, channelConfigSerializer, hector, null);

        ChannelConfiguration result = testClass.getChannelConfiguration("thechan");

        assertEquals(expected, result);
    }
}
