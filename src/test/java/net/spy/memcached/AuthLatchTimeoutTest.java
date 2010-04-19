package net.spy.memcached;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.spy.memcached.ConnectionFactoryBuilder.Protocol;
import net.spy.memcached.auth.AuthDescriptor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.fail;

/**
 * Test to ensure the AuthLatch times out when it takes too much
 * time to complete.
 *
 */
public class AuthLatchTimeoutTest {

    private Listener mockMemcached;


    @Before
    public void setUp() {
	mockMemcached = new Listener();
    }

    @After
    public void tearDown() {
	mockMemcached.interruptIt();
    }

    /**
     * When an operation is canceled, an ExecutionException is thrown as a
     * result of the underlying RuntimeException when an operation is
     * canceled.
     *
     * @throws IOException
     * @throws InterruptedException
     * @throws ExecutionException
     */
    @Test (expected=ExecutionException.class)
    public void testTimeout() throws IOException, InterruptedException, ExecutionException {
	MemcachedClient client = new MemcachedClient(
				new ConnectionFactoryBuilder()
					.setProtocol(Protocol.BINARY)
					.setAuthDescriptor(AuthDescriptor.typical("bogus", "bogus"))
					.build(),
				AddrUtil.getAddresses("localhost:11214"));
	Future<Boolean> result = client.set("failme", 0, "now");
	if (result.get()) {
	    fail("should have timed out");
	}
    }

    private class Listener implements Runnable {
	    private Thread t;
	    public static final int PORT = 11214;
	    ServerSocket sock;
	    Socket clientSock;

	    public Listener() {
		t = new Thread(this, "null listener");
		t.start();
	    }

	    public void run() {
		try {
		    sock = new ServerSocket(PORT);
		    while ((clientSock = sock.accept()) != null) {
			process(clientSock);
		    }
		}
		catch (IOException ex) {
		    System.err.println("IOException during AuthLatchTimeoutTest");
		}
	    }

	    public void interruptIt() {
		t.interrupt();
	    }

	    private void process(Socket s) throws IOException {
	    try {
		InputStream is = s.getInputStream();
		byte[] b = null;
		int bytesRead = is.read(b);
		System.err.println("Read this many bytes" + bytesRead);
		Thread.sleep(5000);
		s.close(); // we're simulating an auth failure here, drop it
	    } catch (InterruptedException ex) {
		Logger.getLogger(AuthLatchTimeoutTest.class.getName()).log(Level.INFO, "Thread interrupted, probably shutting down.", ex);
	    }

	    }

	}

}
