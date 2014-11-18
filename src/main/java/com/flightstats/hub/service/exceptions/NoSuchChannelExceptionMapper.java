package com.flightstats.hub.service.exceptions;

import com.flightstats.hub.model.exception.NoSuchChannelException;
import com.flightstats.hub.util.AbstractExceptionMapper;
import com.google.inject.Singleton;

import javax.ws.rs.core.Response;
import javax.ws.rs.ext.Provider;

@Singleton
@Provider
public class NoSuchChannelExceptionMapper extends AbstractExceptionMapper<NoSuchChannelException> {

	protected Response.Status getResponseCode() {
		return Response.Status.NOT_FOUND;
	}
}
