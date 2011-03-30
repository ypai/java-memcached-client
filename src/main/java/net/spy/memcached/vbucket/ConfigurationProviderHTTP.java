package net.spy.memcached.vbucket;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.net.URI;
import java.net.InetSocketAddress;
import java.net.URLConnection;
import java.net.HttpURLConnection;
import java.net.Authenticator;
import java.net.URL;
import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.text.ParseException;

import net.spy.memcached.AddrUtil;
import net.spy.memcached.vbucket.config.Bucket;
import net.spy.memcached.vbucket.config.Pool;
import net.spy.memcached.vbucket.config.ConfigurationParserJSON;
import net.spy.memcached.vbucket.config.ConfigurationParser;
import net.spy.memcached.vbucket.config.Config;

import javax.naming.ConfigurationException;

/**
 * Configuration management class that provides methods for retrieving vbucket configuration and receiving
 * configuration updates.
 * @author alexander.sokolovsky.a@gmail.com
 */
public class ConfigurationProviderHTTP implements ConfigurationProvider {
    private static final Logger logger = Logger.getLogger(ConfigurationProviderHTTP.class.getName());
    private static final String DEFAULT_POOL_NAME = "default";
    private static final String ANONYMOUS_AUTH_BUCKET = "default";
    /**
     * The specification version which this client meets.  This will be included
     * in requests to the server.
     */
    public static final String CLIENT_SPEC_VER = "1.0";
    private List<URI> baseList;
    private String restUsr;
    private String restPwd;
    private URI loadedBaseUri;
    // map of <bucketname, bucket> currently loaded
    private Map<String, Bucket> buckets = new ConcurrentHashMap<String, Bucket>();

    // map of <poolname, pool> currently loaded
    //private Map<String, Pool> pools = new ConcurrentHashMap<String, Pool>();
    private ConfigurationParser configurationParser = new ConfigurationParserJSON();
    private Map<String, BucketMonitor> monitors = new HashMap<String, BucketMonitor>();

    /**
     * Constructs a configuration provider with disabled authentication for the REST service
     * @param baseList list of urls to treat as base
     * @throws IOException
     */
    public ConfigurationProviderHTTP(List<URI> baseList) throws IOException {
        this(baseList, null, null);
    }

    /**
     * Constructs a configuration provider with a given credentials for the REST service
     * @param baseList list of urls to treat as base
     * @param restUsr username
     * @param restPwd password
     * @throws IOException
     */
    public ConfigurationProviderHTTP(List<URI> baseList, String restUsr, String restPwd) throws IOException {
        this.baseList = baseList;
        this.restUsr = restUsr;
        this.restPwd = restPwd;
    }

    /**
     * Connects to the REST service and retrieves the bucket configuration from the first pool available
     * @param bucketname bucketname
     * @return vbucket configuration
     * @throws ConfigurationException
     */
    public Bucket getBucketConfiguration(final String bucketname) throws ConfigurationException {
        if (bucketname == null || bucketname.isEmpty()) {
            throw new IllegalArgumentException("Bucket name can not be blank.");
        }
        Bucket bucket = this.buckets.get(bucketname);
        if (bucket == null) {
            readPools(bucketname);
        }
        return this.buckets.get(bucketname);
    }

    private void readPools(final String bucketToFind) throws ConfigurationException {
        for (URI baseUri : baseList) {
            try {
                // get and parse the response from the current base uri
                URLConnection baseConnection = urlConnBuilder(null, baseUri);
                String base = readToString(baseConnection);
                if ("".equals(base)) {
                    logger.log(Level.WARNING, "Provided URI {0} has an empty response...skipping", baseUri);
                    continue;
                }
                Map<String, Pool> pools = this.configurationParser.parseBase(base);

                // check for the default pool name
                if (!pools.containsKey(DEFAULT_POOL_NAME)) {
                    logger.log(Level.WARNING, "Provided URI {0} has no default pool...skipping", baseUri);
                    continue;
                }
                // load pools
                for (Pool pool : pools.values()) {
                    URLConnection poolConnection = urlConnBuilder(baseUri, pool.getUri());
                    String sPool = readToString(poolConnection);
                    configurationParser.loadPool(pool, sPool);
                    URLConnection poolBucketsConnection = urlConnBuilder(baseUri, pool.getBucketsUri());
                    String sBuckets = readToString(poolBucketsConnection);
                    Map<String, Bucket> loadedBuckets = configurationParser.parseBuckets(sBuckets);
                    pool.getBuckets().putAll(loadedBuckets);

                }
                // did we find our bucket?
                boolean bucketFound = false;
                for (Pool pool : pools.values()) {
                    if (pool.getBuckets().containsKey(bucketToFind)) {
                        bucketFound = true;
                    }
                }
                if (bucketFound) {
                    // set values
                    for (Pool pool : pools.values()) {
                        for (Map.Entry<String, Bucket> bucketEntry : pool.getBuckets().entrySet()) {
                            this.buckets.put(bucketEntry.getKey(), bucketEntry.getValue());
                        }
                    }
                    this.loadedBaseUri = baseUri;
                    return;
                }
            } catch (ParseException e) {
                logger.log(Level.WARNING, "Provided URI " + baseUri + " has an unparsable response...skipping", e);
            } catch (IOException e) {
                logger.log(Level.WARNING, "Connection problems with URI " + baseUri + " ...skipping", e);
            }
            throw new ConfigurationException("Configuration for bucket " + bucketToFind + " was not found.");
        }
    }

    public List<InetSocketAddress> getServerList(final String bucketname) throws ConfigurationException {
        Bucket bucket = getBucketConfiguration(bucketname);
        List<String> servers = bucket.getConfig().getServers();
        StringBuilder serversString = new StringBuilder();
        for (String server : servers) {
            serversString.append(server).append(" ");
        }
        return AddrUtil.getAddresses(serversString.toString());
    }

    /**
     * Subscribes for configuration updates
     * @param bucketName bucket name to receive configuration for
     * @param rec reconfigurable that will receive updates
     * @throws ConfigurationException
     */
    public void subscribe(final String bucketName, final Reconfigurable rec) throws ConfigurationException {

        Bucket bucket = getBucketConfiguration(bucketName);

        ReconfigurableObserver obs = new ReconfigurableObserver(rec);
        BucketMonitor monitor = this.monitors.get(bucketName);
        if (monitor == null) {
            URI streamingURI = bucket.getStreamingURI();
            monitor = new BucketMonitor(this.loadedBaseUri.resolve(streamingURI), bucketName, this.restUsr, this.restPwd, configurationParser);
            this.monitors.put(bucketName, monitor);
            monitor.addObserver(obs);
            monitor.startMonitor();
        } else {
            monitor.addObserver(obs);
        }
    }

    /**
     * Unsubscribe from updates on a given bucket and given reconfigurable
     * @param vbucketName bucket name
     * @param rec reconfigurable
     */
    public void unsubscribe(String vbucketName, Reconfigurable rec) {
        BucketMonitor monitor = this.monitors.get(vbucketName);
        if (monitor != null) {
            monitor.deleteObserver(new ReconfigurableObserver(rec));
        }
    }

    public Config getLatestConfig(String bucketname) throws ConfigurationException {
        Bucket bucket = getBucketConfiguration(bucketname);
        return bucket.getConfig();
    }

    public String getAnonymousAuthBucket() {
        return ANONYMOUS_AUTH_BUCKET;
    }

    /**
     * Shutdowns a monitor connections to the REST service
     */
    public void shutdown() {
        for (BucketMonitor monitor : this.monitors.values()) {
            monitor.shutdown();
        }
    }

    /**
     * Create a URL which has the appropriate headers to interact with the
     * service.  Most exception handling is up to the caller.
     *
     * @param resource the URI either absolute or relative to the base for this ClientManager
     * @return
     * @throws java.io.IOException
     */
    private URLConnection urlConnBuilder(URI base, URI resource) throws IOException {
        if (!resource.isAbsolute() && base != null) {
            resource = base.resolve(resource);
        }
        URL specURL = resource.toURL();
        URLConnection connection = specURL.openConnection();
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("user-agent", "spymemcached vbucket client");
        connection.setRequestProperty("X-memcachekv-Store-Client-Specification-Version", CLIENT_SPEC_VER);
	if (restUsr != null) {
	    connection.setRequestProperty("Authorization", buildAuthHeader(restUsr, restPwd));
	}

        return connection;

    }

    /**
     * Helper method that reads content from url connection to the string
     * @param connection a given url connection
     * @return content string
     * @throws IOException
     */
    private String readToString(URLConnection connection) throws IOException {
        InputStream inStream = connection.getInputStream();
        if (connection instanceof java.net.HttpURLConnection) {
            HttpURLConnection httpConnection = (HttpURLConnection) connection;
            if (httpConnection.getResponseCode() == 403) {
                throw new IOException("Service does not accept the authentication credentials: "
                        + httpConnection.getResponseCode() + httpConnection.getResponseMessage());
            } else if (httpConnection.getResponseCode() >= 400) {
                throw new IOException("Service responded with a failure code: "
                        + httpConnection.getResponseCode() + httpConnection.getResponseMessage());
            }
        } else {
            throw new IOException("Unexpected URI type encountered");
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(inStream));
        String str;
        StringBuffer buffer = new StringBuffer();
        while ((str = reader.readLine()) != null) {
            buffer.append(str);
        }
        reader.close();
        return buffer.toString();
    }

    /**
     * Oddly, lots of things that do HTTP seem to not know how to do this and
     * Authenticator caches for the process.  Since we only need Basic at the
     * moment simply, add the header.
     *
     * @return a value for an HTTP Basic Auth Header
     */
    protected static String buildAuthHeader(String username, String password) {
        // apparently netty isn't familiar with HTTP Basic Auth
        StringBuilder clearText = new StringBuilder(username);
        clearText.append(':');
        if (password != null) {
            clearText.append(password);
        }
        // and apache base64 codec has extra \n\l we have to strip off
        String encodedText = org.apache.commons.codec.binary.Base64.encodeBase64String(clearText.toString().getBytes());
        char[] encodedWoNewline = new char[encodedText.length() - 2];
        encodedText.getChars(0, encodedText.length() - 2, encodedWoNewline, 0);
        String authVal = "Basic " + new String(encodedWoNewline);

        return authVal;
    }

}
