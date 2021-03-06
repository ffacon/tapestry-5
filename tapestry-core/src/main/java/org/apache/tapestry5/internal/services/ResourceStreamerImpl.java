// Copyright 2006-2013 The Apache Software Foundation
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.apache.tapestry5.internal.services;

import org.apache.tapestry5.SymbolConstants;
import org.apache.tapestry5.internal.InternalConstants;
import org.apache.tapestry5.internal.services.assets.ResourceChangeTracker;
import org.apache.tapestry5.ioc.IOOperation;
import org.apache.tapestry5.ioc.OperationTracker;
import org.apache.tapestry5.ioc.Resource;
import org.apache.tapestry5.ioc.annotations.Symbol;
import org.apache.tapestry5.services.Request;
import org.apache.tapestry5.services.Response;
import org.apache.tapestry5.services.assets.CompressionStatus;
import org.apache.tapestry5.services.assets.StreamableResource;
import org.apache.tapestry5.services.assets.StreamableResourceProcessing;
import org.apache.tapestry5.services.assets.StreamableResourceSource;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Set;

public class ResourceStreamerImpl implements ResourceStreamer
{
    static final String IF_MODIFIED_SINCE_HEADER = "If-Modified-Since";

    private static final String QUOTE = "\"";

    private final Request request;

    private final Response response;

    private final StreamableResourceSource streamableResourceSource;

    private final boolean productionMode;

    private final OperationTracker tracker;

    private final ResourceChangeTracker resourceChangeTracker;

    public ResourceStreamerImpl(Request request,

                                Response response,

                                StreamableResourceSource streamableResourceSource,

                                OperationTracker tracker,

                                @Symbol(SymbolConstants.PRODUCTION_MODE)
                                boolean productionMode,

                                ResourceChangeTracker resourceChangeTracker)
    {
        this.request = request;
        this.response = response;
        this.streamableResourceSource = streamableResourceSource;

        this.tracker = tracker;
        this.productionMode = productionMode;
        this.resourceChangeTracker = resourceChangeTracker;
    }

    public boolean streamResource(final Resource resource, final String providedChecksum, final Set<Options> options) throws IOException
    {
        if (!resource.exists())
        {
            // TODO: Or should we just return false here and not send back a specific error with the (eventual) 404?

            response.sendError(HttpServletResponse.SC_NOT_FOUND, String.format("Unable to locate asset '%s' (the file does not exist).", resource));

            return true;
        }

        final boolean compress = providedChecksum.startsWith("z");

        return tracker.perform(String.format("Streaming %s%s", resource, compress ? " (compressed)" : ""), new IOOperation<Boolean>()
        {
            public Boolean perform() throws IOException
            {
                StreamableResourceProcessing processing = compress
                        ? StreamableResourceProcessing.COMPRESSION_ENABLED
                        : StreamableResourceProcessing.COMPRESSION_DISABLED;

                StreamableResource streamable = streamableResourceSource.getStreamableResource(resource, processing, resourceChangeTracker);

                return streamResource(streamable, compress ? providedChecksum.substring(1) : providedChecksum, options);
            }
        });
    }

    public boolean streamResource(StreamableResource streamable, String providedChecksum, Set<Options> options) throws IOException
    {
        assert streamable != null;
        assert providedChecksum != null;
        assert options != null;

        String actualChecksum = streamable.getChecksum();

        if (providedChecksum.length() > 0 && !providedChecksum.equals(actualChecksum))
        {
            return false;
        }

        long lastModified = streamable.getLastModified();

        long ifModifiedSince;

        try
        {
            ifModifiedSince = request.getDateHeader(IF_MODIFIED_SINCE_HEADER);
        } catch (IllegalArgumentException ex)
        {
            // Simulate the header being missing if it is poorly formatted.

            ifModifiedSince = -1;
        }

        if (ifModifiedSince > 0 && ifModifiedSince >= lastModified)
        {
            response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            return true;
        }

        // ETag should be surrounded with quotes.
        String token = QUOTE + actualChecksum + QUOTE;

        // Even when sending a 304, we want the ETag associated with the request.
        // In most cases (except JavaScript modules), the checksum is also embedded into the URL.
        // However, E-Tags are also useful for enabling caching inside intermediate servers, CDNs, etc.
        response.setHeader("ETag", token);

        // If the client can send the correct ETag token, then its cache already contains the correct
        // content.
        String providedToken = request.getHeader("If-None-Match");

        if (token.equals(providedToken))
        {
            response.setStatus(HttpServletResponse.SC_NOT_MODIFIED);
            return true;
        }

        // Prevent the upstream code from compressing when we don't want to.

        response.disableCompression();

        response.setDateHeader("Last-Modified", lastModified);


        if (productionMode && !options.contains(Options.OMIT_EXPIRATION))
        {
            // Starting in 5.4, this is a lot less necessary; any change to a Resource will result
            // in a new asset URL with the changed checksum incorporated into the URL.
            response.setDateHeader("Expires", lastModified + InternalConstants.TEN_YEARS);
        }

        // This is really for modules, which can not have a content hash code in the URL; therefore, we want
        // the browser to re-validate the resources on each new page render; because of the ETags, that will
        // mostly result in quick SC_NOT_MODIFIED responses.
        if (options.contains(Options.OMIT_EXPIRATION))
        {
            response.setHeader("Cache-Control", "max-age=0, must-revalidate");
        }

        response.setContentLength(streamable.getSize());

        if (streamable.getCompression() == CompressionStatus.COMPRESSED)
        {
            response.setHeader(InternalConstants.CONTENT_ENCODING_HEADER, InternalConstants.GZIP_CONTENT_ENCODING);
        }

        OutputStream os = response.getOutputStream(streamable.getContentType());

        streamable.streamTo(os);

        os.close();

        return true;
    }

}
