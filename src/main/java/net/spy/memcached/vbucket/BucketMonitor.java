/*
 * Copyright (c) 2009, NorthScale, Inc.
 *
 * All rights reserved.
 *
 * info@northscale.com
 *
 */
package net.spy.memcached.vbucket;

import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.handler.codec.http.DefaultHttpRequest;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpVersion;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Observable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.text.ParseException;

import org.jboss.netty.channel.ChannelFactory;
import net.spy.memcached.vbucket.config.Bucket;
import net.spy.memcached.vbucket.config.ConfigurationParser;

/**
 *
 * The BucketMonitor will open an HTTP comet stream to monitor for
 * changes to the list of nodes.  If the list of nodes changes
 *
 * @author Matt Ingenthron <matt.ingenthron@northscale.com>
 */
public class BucketMonitor extends Observable {

    private final URI cometStreamURI;
    private Bucket bucket;
    private final String httpUser;
    private final String httpPass;
    private final ChannelFactory factory;
    private Channel channel;
    private final String host;
    private final int port;
    private ConfigurationParser configParser;
    private BucketUpdateResponseHandler handler;
    /**
     * The specification version which this client meets.  This will be included
     * in requests to the server.
     */
    public static final String CLIENT_SPEC_VER = "1.0";

    /**
     *
     * @param cometStreamURI the URI which will stream node changes
     * @param bucketname the bucket name we are monitoring
     * @param username the username required for HTTP Basic Auth to the restful service
     * @param password the password required for HTTP Basic Auth to the restful service
     */
    public BucketMonitor(URI cometStreamURI,  String bucketname, String username, String password, ConfigurationParser configParser) {
        super();
        if (cometStreamURI == null) {
            throw new IllegalArgumentException("cometStreamURI cannot be NULL");
        }
        String scheme = cometStreamURI.getScheme() == null ? "http" : cometStreamURI.getScheme();
        if (!scheme.equals("http")) {
            // an SslHandler is needed in the pipeline
            //System.err.println("Only HTTP is supported.");
            throw new UnsupportedOperationException("Only http is supported.");
        }

        this.cometStreamURI = cometStreamURI;
        this.httpUser = username;
        this.httpPass = password;
        this.configParser = configParser;
        this.host = cometStreamURI.getHost();
        this.port = cometStreamURI.getPort() == -1 ? 80 : cometStreamURI.getPort();
        factory = new NioClientSocketChannelFactory(Executors.newCachedThreadPool(), Executors.newCachedThreadPool());
    }

    public void startMonitor() {
        if (channel != null) {
            Logger.getLogger(BucketMonitor.class.getName()).log(Level.WARNING,
                    "Bucket monitor is already started.");
            return;
        }
        createChannel();
        this.handler = channel.getPipeline().get(BucketUpdateResponseHandler.class);
        handler.setBucketMonitor(this);
        HttpRequest request = prepareRequest(cometStreamURI, host);
        channel.write(request);
        try {
            String response = this.handler.getLastResponse();
            logFiner("Getting server list returns this last chunked response:\n" + response);
            Bucket bucket = this.configParser.parseBucket(response);
            setBucket(bucket);
        } catch (ParseException ex) {
            Logger.getLogger(BucketMonitor.class.getName()).log(Level.WARNING,
                    "Invalid client configuration received from server.  Staying with existing configuration.", ex);
            Logger.getLogger(BucketMonitor.class.getName()).log(Level.FINE,
                    "Invalid client configuration received:\n" + handler.getLastResponse() + "\n");
        }
    }

    protected void createChannel() {
        // Configure the client.
        ClientBootstrap bootstrap = new ClientBootstrap(factory);

        // Set up the event pipeline factory.
        bootstrap.setPipelineFactory(new BucketMonitorPipelineFactory());

        // Start the connection attempt.
        ChannelFuture future = bootstrap.connect(new InetSocketAddress(host, port));

        // Wait until the connection attempt succeeds or fails.
        channel = future.awaitUninterruptibly().getChannel();
        if (!future.isSuccess()) {
            bootstrap.releaseExternalResources();
            throw new ConnectionException("Could not connect to any pool member.");
        }
        assert(channel != null);
    }

    protected HttpRequest prepareRequest(URI uri, String host) {
        // Send the HTTP request.
        HttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, uri.toASCIIString());
        request.setHeader(HttpHeaders.Names.HOST, host);
        if (getHttpUser() != null) {
            request.setHeader(HttpHeaders.Names.AUTHORIZATION, ConfigurationProviderHTTP.buildAuthHeader(getHttpUser(), getHttpPass()));
        }
        request.setHeader(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE);  // No keep-alives for this
        request.setHeader(HttpHeaders.Names.CACHE_CONTROL, HttpHeaders.Values.NO_CACHE);
        request.setHeader(HttpHeaders.Names.ACCEPT, "application/json");
        request.setHeader(HttpHeaders.Names.USER_AGENT, "spymemcached vbucket client");
        /** @todo get the git describe in here */
        request.setHeader("X-memcachekv-Store-Client-Specification-Version", CLIENT_SPEC_VER);
        return request;
    }

    /**
     * Update the config if it has changed and notify our
     * observers.
     *
     * @param bucket the bucket to set
     */
    private void setBucket(Bucket bucket) {
        if (this.bucket == null || !this.bucket.equals(bucket)) {
            this.bucket = bucket;
            setChanged();
            notifyObservers(this.bucket);
        }
    }

    /**
     * @return the httpUser
     */
    public String getHttpUser() {
        return httpUser;
    }

    /**
     * @return the httpPass
     */
    public String getHttpPass() {
        return httpPass;
    }

    private void logFiner(String msg) {
        Logger.getLogger(BucketMonitor.class.getName()).log(Level.FINER, msg);
    }

    /**
     * Shut down the monitor in a graceful way (and release all resources)
     */
    public void shutdown() {
        shutdown(-1, TimeUnit.MILLISECONDS);
    }

    /**
     * Shut down this monitor in a graceful way
     *
     * @param timeout
     * @param unit
     */
    public void shutdown(long timeout, TimeUnit unit) {
        deleteObservers();
        if (channel != null) {
            channel.close().awaitUninterruptibly(timeout, unit);
        }
        factory.releaseExternalResources();
    }

    protected void invalidate() {
        try {
            String response = handler.getLastResponse();
            Bucket bucket = this.configParser.parseBucket(response);
            setBucket(bucket);
        } catch (ParseException e) {
            Logger.getLogger(BucketMonitor.class.getName()).log(Level.SEVERE,
                    "Invalid client configuration received from server.  Staying with existing configuration.", e);
        }
    }

    public void setConfigParser(ConfigurationParser configParser) {
        this.configParser = configParser;
    }
}
