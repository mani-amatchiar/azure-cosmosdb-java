/*
 * The MIT License (MIT)
 * Copyright (c) 2018 Microsoft Corporation
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.microsoft.azure.cosmosdb.internal.directconnectivity;

import com.microsoft.azure.cosmosdb.BridgeInternal;
import com.microsoft.azure.cosmosdb.CosmosClientException;
import com.microsoft.azure.cosmosdb.Error;
import com.microsoft.azure.cosmosdb.internal.HttpConstants;
import com.microsoft.azure.cosmosdb.rx.internal.RMResources;
import io.reactivex.netty.protocol.http.client.HttpResponseHeaders;

import java.util.Map;

/**
 * While this class is public, but it is not part of our published public APIs.
 * This is meant to be internally used only by our sdk.
 */
public class ConflictException extends CosmosClientException {

    private static final long serialVersionUID = 1L;

    public ConflictException() {
        this(RMResources.EntityAlreadyExists);
    }

    public ConflictException(Error error, long lsn, String partitionKeyRangeId, Map<String, String> responseHeaders) {
        super(HttpConstants.StatusCodes.CONFLICT, error, responseHeaders);
        BridgeInternal.setLSN(this, lsn);
        BridgeInternal.setPartitionKeyRangeId(this, partitionKeyRangeId);
    }

    public ConflictException(String msg) {
        super(HttpConstants.StatusCodes.CONFLICT, msg);
    }

    public ConflictException(String msg, String resourceAddress) {
        super(msg, null, null, HttpConstants.StatusCodes.CONFLICT, resourceAddress);
    }

    public ConflictException(String message, HttpResponseHeaders headers, String requestUri) {
        this(message, null, headers, requestUri);
    }

    public ConflictException(Exception innerException) {
        this(RMResources.EntityAlreadyExists, innerException, null, null);
    }

    public ConflictException(Error error, Map<String, String> headers) {
        super(HttpConstants.StatusCodes.CONFLICT, error, headers);
    }

    public ConflictException(String message,
                             Exception innerException,
                             HttpResponseHeaders headers,
                             String requestUri) {
        super(String.format("%s: %s", RMResources.EntityAlreadyExists, message),
                innerException,
                HttpUtils.asMap(headers),
                HttpConstants.StatusCodes.CONFLICT,
                requestUri);
    }
}