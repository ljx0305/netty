/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License, version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package io.netty.handler.codec.http2;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http2.internal.hpack.Decoder;
import io.netty.util.internal.ObjectUtil;
import io.netty.util.internal.UnstableApi;

import static io.netty.handler.codec.http2.Http2CodecUtil.DEFAULT_HEADER_LIST_SIZE;
import static io.netty.handler.codec.http2.Http2Error.COMPRESSION_ERROR;
import static io.netty.handler.codec.http2.Http2Exception.connectionError;

@UnstableApi
public class DefaultHttp2HeadersDecoder implements Http2HeadersDecoder, Http2HeadersDecoder.Configuration {
    private static final float HEADERS_COUNT_WEIGHT_NEW = 1 / 5f;
    private static final float HEADERS_COUNT_WEIGHT_HISTORICAL = 1 - HEADERS_COUNT_WEIGHT_NEW;

    private final Decoder decoder;
    private final boolean validateHeaders;
    /**
     * Used to calculate an exponential moving average of header sizes to get an estimate of how large the data
     * structure for storing headers should be.
     */
    private float headerArraySizeAccumulator = 8;

    public DefaultHttp2HeadersDecoder() {
        this(true);
    }

    public DefaultHttp2HeadersDecoder(boolean validateHeaders) {
        this(validateHeaders, DEFAULT_HEADER_LIST_SIZE);
    }

    /**
     * Create a new instance.
     * @param validateHeaders {@code true} to validate headers are valid according to the RFC.
     * @param maxHeaderListSize This is the only setting that can be configured before notifying the peer.
     *  This is because <a href="https://tools.ietf.org/html/rfc7540#section-6.5.1">SETTINGS_MAX_HEADER_LIST_SIZE</a>
     *  allows a lower than advertised limit from being enforced, and the default limit is unlimited
     *  (which is dangerous).
     */
    public DefaultHttp2HeadersDecoder(boolean validateHeaders, long maxHeaderListSize) {
        this(validateHeaders, maxHeaderListSize, 32);
    }

    /**
     * Create a new instance.
     * @param validateHeaders {@code true} to validate headers are valid according to the RFC.
     * @param maxHeaderListSize This is the only setting that can be configured before notifying the peer.
     *  This is because <a href="https://tools.ietf.org/html/rfc7540#section-6.5.1">SETTINGS_MAX_HEADER_LIST_SIZE</a>
     *  allows a lower than advertised limit from being enforced, and the default limit is unlimited
     *  (which is dangerous).
     * @param initialHuffmanDecodeCapacity Size of an intermediate buffer used during huffman decode.
     */
    public DefaultHttp2HeadersDecoder(boolean validateHeaders, long maxHeaderListSize,
                                      int initialHuffmanDecodeCapacity) {
        this(validateHeaders, new Decoder(maxHeaderListSize, initialHuffmanDecodeCapacity));
    }

    /**
     * Exposed Used for testing only! Default values used in the initial settings frame are overriden intentionally
     * for testing but violate the RFC if used outside the scope of testing.
     */
    DefaultHttp2HeadersDecoder(boolean validateHeaders, Decoder decoder) {
        this.decoder = ObjectUtil.checkNotNull(decoder, "decoder");
        this.validateHeaders = validateHeaders;
    }

    @Override
    public void maxHeaderTableSize(long max) throws Http2Exception {
        decoder.setMaxHeaderTableSize(max);
    }

    @Override
    public long maxHeaderTableSize() {
        return decoder.getMaxHeaderTableSize();
    }

    @Override
    public void maxHeaderListSize(long max, long goAwayMax) throws Http2Exception {
        decoder.setMaxHeaderListSize(max, goAwayMax);
    }

    @Override
    public long maxHeaderListSize() {
        return decoder.getMaxHeaderListSize();
    }

    @Override
    public long maxHeaderListSizeGoAway() {
        return decoder.getMaxHeaderListSizeGoAway();
    }

    @Override
    public Configuration configuration() {
        return this;
    }

    @Override
    public Http2Headers decodeHeaders(int streamId, ByteBuf headerBlock) throws Http2Exception {
        try {
            final Http2Headers headers = new DefaultHttp2Headers(validateHeaders, (int) headerArraySizeAccumulator);
            decoder.decode(streamId, headerBlock, headers);
            headerArraySizeAccumulator = HEADERS_COUNT_WEIGHT_NEW * headers.size() +
                                         HEADERS_COUNT_WEIGHT_HISTORICAL * headerArraySizeAccumulator;
            return headers;
        } catch (Http2Exception e) {
            throw e;
        } catch (Throwable e) {
            // Default handler for any other types of errors that may have occurred. For example,
            // the the Header builder throws IllegalArgumentException if the key or value was invalid
            // for any reason (e.g. the key was an invalid pseudo-header).
            throw connectionError(COMPRESSION_ERROR, e, e.getMessage());
        }
    }
}
