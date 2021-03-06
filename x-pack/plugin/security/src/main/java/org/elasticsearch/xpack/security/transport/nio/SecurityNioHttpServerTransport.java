/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.transport.nio;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.recycler.Recycler;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.common.util.PageCacheRecycler;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.http.nio.HttpReadWriteHandler;
import org.elasticsearch.http.nio.NioHttpChannel;
import org.elasticsearch.http.nio.NioHttpServerChannel;
import org.elasticsearch.http.nio.NioHttpServerTransport;
import org.elasticsearch.nio.BytesChannelContext;
import org.elasticsearch.nio.ChannelFactory;
import org.elasticsearch.nio.InboundChannelBuffer;
import org.elasticsearch.nio.NioSelector;
import org.elasticsearch.nio.NioSocketChannel;
import org.elasticsearch.nio.ServerChannelContext;
import org.elasticsearch.nio.SocketChannelContext;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xpack.core.ssl.SSLConfiguration;
import org.elasticsearch.xpack.core.ssl.SSLService;
import org.elasticsearch.xpack.security.transport.SecurityHttpExceptionHandler;
import org.elasticsearch.xpack.security.transport.filter.IPFilter;

import javax.net.ssl.SSLEngine;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.elasticsearch.xpack.core.XPackSettings.HTTP_SSL_ENABLED;

public class SecurityNioHttpServerTransport extends NioHttpServerTransport {
    private static final Logger logger = LogManager.getLogger(SecurityNioHttpServerTransport.class);

    private final SecurityHttpExceptionHandler securityExceptionHandler;
    private final IPFilter ipFilter;
    private final NioIPFilter nioIpFilter;
    private final SSLService sslService;
    private final SSLConfiguration sslConfiguration;
    private final boolean sslEnabled;

    public SecurityNioHttpServerTransport(Settings settings, NetworkService networkService, BigArrays bigArrays,
                                          PageCacheRecycler pageCacheRecycler, ThreadPool threadPool,
                                          NamedXContentRegistry xContentRegistry, Dispatcher dispatcher, IPFilter ipFilter,
                                          SSLService sslService) {
        super(settings, networkService, bigArrays, pageCacheRecycler, threadPool, xContentRegistry, dispatcher);
        this.securityExceptionHandler = new SecurityHttpExceptionHandler(logger, lifecycle, (c, e) -> super.onException(c, e));
        this.ipFilter = ipFilter;
        this.nioIpFilter = new NioIPFilter(ipFilter, IPFilter.HTTP_PROFILE_NAME);
        this.sslEnabled = HTTP_SSL_ENABLED.get(settings);
        this.sslService = sslService;
        if (sslEnabled) {
            this.sslConfiguration = sslService.getHttpTransportSSLConfiguration();
            if (sslService.isConfigurationValidForServerUsage(sslConfiguration) == false) {
                throw new IllegalArgumentException("a key must be provided to run as a server. the key should be configured using the " +
                    "[xpack.security.http.ssl.key] or [xpack.security.http.ssl.keystore.path] setting");
            }
        } else {
            this.sslConfiguration = null;
        }
    }

    @Override
    protected void doStart() {
        super.doStart();
        ipFilter.setBoundHttpTransportAddress(this.boundAddress());
    }

    protected SecurityHttpChannelFactory channelFactory() {
        return new SecurityHttpChannelFactory();
    }

    class SecurityHttpChannelFactory extends ChannelFactory<NioHttpServerChannel, NioHttpChannel> {

        private SecurityHttpChannelFactory() {
            super(new RawChannelFactory(tcpNoDelay, tcpKeepAlive, reuseAddress, tcpSendBufferSize, tcpReceiveBufferSize));
        }

        @Override
        public NioHttpChannel createChannel(NioSelector selector, SocketChannel channel) throws IOException {
            NioHttpChannel httpChannel = new NioHttpChannel(channel);
            Supplier<InboundChannelBuffer.Page> pageSupplier = () -> {
                Recycler.V<byte[]> bytes = pageCacheRecycler.bytePage(false);
                return new InboundChannelBuffer.Page(ByteBuffer.wrap(bytes.v()), bytes::close);
            };
            HttpReadWriteHandler httpHandler = new HttpReadWriteHandler(httpChannel,SecurityNioHttpServerTransport.this,
                handlingSettings, corsConfig);
            InboundChannelBuffer buffer = new InboundChannelBuffer(pageSupplier);
            Consumer<Exception> exceptionHandler = (e) -> securityExceptionHandler.accept(httpChannel, e);

            SocketChannelContext context;
            if (sslEnabled) {
                SSLEngine sslEngine;
                boolean hostnameVerificationEnabled = sslConfiguration.verificationMode().isHostnameVerificationEnabled();
                if (hostnameVerificationEnabled) {
                    InetSocketAddress address = (InetSocketAddress) channel.getRemoteAddress();
                    // we create the socket based on the name given. don't reverse DNS
                    sslEngine = sslService.createSSLEngine(sslConfiguration, address.getHostString(), address.getPort());
                } else {
                    sslEngine = sslService.createSSLEngine(sslConfiguration, null, -1);
                }
                SSLDriver sslDriver = new SSLDriver(sslEngine, false);
                context = new SSLChannelContext(httpChannel, selector, exceptionHandler, sslDriver, httpHandler, buffer, nioIpFilter);
            } else {
                context = new BytesChannelContext(httpChannel, selector, exceptionHandler, httpHandler, buffer, nioIpFilter);
            }
            httpChannel.setContext(context);

            return httpChannel;
        }

        @Override
        public NioHttpServerChannel createServerChannel(NioSelector selector, ServerSocketChannel channel) {
            NioHttpServerChannel httpServerChannel = new NioHttpServerChannel(channel);
            Consumer<Exception> exceptionHandler = (e) -> onServerException(httpServerChannel, e);
            Consumer<NioSocketChannel> acceptor = SecurityNioHttpServerTransport.this::acceptChannel;
            ServerChannelContext context = new ServerChannelContext(httpServerChannel, this, selector, acceptor, exceptionHandler);
            httpServerChannel.setContext(context);

            return httpServerChannel;
        }
    }
}
