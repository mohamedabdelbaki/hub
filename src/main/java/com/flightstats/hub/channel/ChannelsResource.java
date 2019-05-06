package com.flightstats.hub.channel;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flightstats.hub.app.HubProvider;
import com.flightstats.hub.app.PermissionsChecker;
import com.flightstats.hub.dao.ChannelService;
import com.flightstats.hub.exception.ConflictException;
import com.flightstats.hub.exception.InvalidRequestException;
import com.flightstats.hub.model.ChannelConfig;
import com.flightstats.hub.rest.Linked;
import lombok.extern.slf4j.Slf4j;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.net.URI;
import java.util.Map;
import java.util.TreeMap;

import static com.flightstats.hub.channel.LinkBuilder.buildChannelConfigResponse;

/**
 * This resource represents the collection of all channels in the Hub.
 */
@SuppressWarnings("WeakerAccess")
@Path("/channel")
@Slf4j
public class ChannelsResource {
    private final static ChannelService channelService = HubProvider.getInstance(ChannelService.class);
    public static final String READ_ONLY_FAILURE_MESSAGE = "attempted to %s against /channels on read-only node %s";

    @Context
    private UriInfo uriInfo;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getChannels() {
        Map<String, URI> mappedUris = new TreeMap<>();
        for (ChannelConfig channelConfig : channelService.getChannels()) {
            String channelName = channelConfig.getDisplayName();
            mappedUris.put(channelName, LinkBuilder.buildChannelUri(channelName, uriInfo));
        }
        Linked<?> result = LinkBuilder.buildLinks(uriInfo, mappedUris, "channels");
        return Response.ok(result).build();
    }

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createChannel(String json) throws InvalidRequestException, ConflictException {
        PermissionsChecker.checkReadOnlyPermission(String.format(READ_ONLY_FAILURE_MESSAGE, "createChannel", json));
        log.debug("post channel {}", json);
        ChannelConfig channelConfig = ChannelConfig.createFromJson(json);
        channelConfig = channelService.createChannel(channelConfig);
        URI channelUri = LinkBuilder.buildChannelUri(channelConfig.getDisplayName(), uriInfo);
        ObjectNode output = buildChannelConfigResponse(channelConfig, uriInfo, channelConfig.getDisplayName());
        return Response.created(channelUri).entity(output).build();
    }
    
}
