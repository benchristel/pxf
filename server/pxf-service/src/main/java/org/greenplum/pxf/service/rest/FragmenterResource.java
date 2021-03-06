package org.greenplum.pxf.service.rest;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import org.greenplum.pxf.api.model.Fragment;
import org.greenplum.pxf.api.model.FragmentStats;
import org.greenplum.pxf.api.model.Fragmenter;
import org.greenplum.pxf.api.model.RequestContext;
import org.greenplum.pxf.api.utilities.FragmenterFactory;
import org.greenplum.pxf.api.utilities.FragmentsResponse;
import org.greenplum.pxf.api.utilities.FragmentsResponseFormatter;
import org.greenplum.pxf.service.HttpRequestParser;
import org.greenplum.pxf.service.RequestParser;
import org.greenplum.pxf.service.SessionId;
import org.greenplum.pxf.service.utilities.AnalyzeUtils;

import javax.servlet.ServletContext;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;

/**
 * Class enhances the API of the WEBHDFS REST server. Returns the data fragments
 * that a data resource is made of, enabling parallel processing of the data
 * resource. Example for querying API FRAGMENTER from a web client
 * {@code curl -i "http://localhost:51200/pxf/{version}/Fragmenter/getFragments?path=/dir1/dir2/*txt"}
 * <code>/pxf/</code> is made part of the path when there is a webapp by that
 * name in tomcat.
 */
@Path("/" + Version.PXF_PROTOCOL_VERSION + "/Fragmenter/")
public class FragmenterResource extends BaseResource {

    private FragmenterFactory fragmenterFactory;

    public FragmenterResource() {
        this(HttpRequestParser.getInstance(), FragmenterFactory.getInstance());
    }

    FragmenterResource(RequestParser<HttpHeaders> parser, FragmenterFactory fragmenterFactory) {
        super(parser);
        this.fragmenterFactory = fragmenterFactory;
    }

    /**
     * The function is called when
     * {@code http://nn:port/pxf/{version}/Fragmenter/getFragments?path=...} is used.
     *
     * @param servletContext Servlet context contains attributes required by
     *            SecuredHDFS
     * @param headers Holds HTTP headers from request
     * @param path Holds URI path option used in this request
     * @return response object with JSON serialized fragments metadata
     * @throws Exception if getting fragments info failed
     */
    @GET
    @Path("getFragments")
    @Produces("application/json")
    public Response getFragments(@Context final ServletContext servletContext,
                                 @Context final HttpHeaders headers,
                                 @QueryParam("path") final String path)
            throws Exception {

        long startTime = System.currentTimeMillis();
        LOG.debug("FRAGMENTER started for path \"{}\"", path);

        RequestContext context = parseRequest(headers);

        /* Create a fragmenter instance with API level parameters */
        final Fragmenter fragmenter = fragmenterFactory.getPlugin(context);

        List<Fragment> fragments = fragmenter.getFragments();
        fragments = AnalyzeUtils.getSampleFragments(fragments, context);
        FragmentsResponse fragmentsResponse = FragmentsResponseFormatter.formatResponse(fragments, path);

        int numberOfFragments = fragments.size();
        SessionId session = new SessionId(context.getSegmentId(), context.getTransactionId(), context.getUser());
        long elapsedMillis = System.currentTimeMillis() - startTime;
        LOG.info("{} returns {} fragment{} for path {} in {} ms for {} [profile {} filter is{} available]",
                fragmenter.getClass().getSimpleName(), numberOfFragments, numberOfFragments == 1 ? "" : "s",
                path, elapsedMillis, session, context.getProfile(), context.hasFilter() ? "" : " not");

        return Response.ok(fragmentsResponse, MediaType.APPLICATION_JSON_TYPE).build();
    }

    /**
     * The function is called when
     * {@code http://nn:port/pxf/{version}/Fragmenter/getFragmentsStats?path=...} is
     * used.
     *
     * @param servletContext Servlet context contains attributes required by
     *            SecuredHDFS
     * @param headers Holds HTTP headers from request
     * @param path Holds URI path option used in this request
     * @return response object with JSON serialized fragments statistics
     * @throws Exception if getting fragments info failed
     */
    @GET
    @Path("getFragmentsStats")
    @Produces("application/json")
    public Response getFragmentsStats(@Context final ServletContext servletContext,
                                      @Context final HttpHeaders headers,
                                      @QueryParam("path") final String path)
            throws Exception {

        RequestContext context = parseRequest(headers);

        /* Create a fragmenter instance with API level parameters */
        final Fragmenter fragmenter = fragmenterFactory.getPlugin(context);

        FragmentStats fragmentStats = fragmenter.getFragmentStats();
        String response = FragmentStats.dataToJSON(fragmentStats);
        if (LOG.isDebugEnabled()) {
            LOG.debug(FragmentStats.dataToString(fragmentStats, path));
        }

        return Response.ok(response, MediaType.APPLICATION_JSON_TYPE).build();
    }

}
