package net.spy.memcached;

import java.util.ArrayList;
import java.util.Collection;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import junit.framework.TestCase;

public abstract class ClientBaseCase extends TestCase {

	protected MemcachedClient client = null;

	protected void initClient() throws Exception {

	    	Logger.getLogger("net.spy.memcached").setLevel(Level.FINEST);

	//get the top Logger, create it if it doesn't exist, set to FINEST
	Logger topLogger = java.util.logging.Logger.getLogger("");

	Handler consoleHandler = null;
	for (Handler handler : topLogger.getHandlers()) {
	    if (handler instanceof ConsoleHandler) {
		consoleHandler = handler;
		break;
	    }
	}

	if (consoleHandler == null) {
	    consoleHandler = new ConsoleHandler();
	    topLogger.addHandler(consoleHandler);
	}
	consoleHandler.setLevel(java.util.logging.Level.FINEST);

		initClient(new DefaultConnectionFactory() {
			@Override
			public long getOperationTimeout() {
				return 15000;
			}
			@Override
			public FailureMode getFailureMode() {
				return FailureMode.Retry;
			}
		});
	}

	protected void initClient(ConnectionFactory cf) throws Exception {
		client=new MemcachedClient(cf,
			AddrUtil.getAddresses("127.0.0.1:11211"));
	}

	protected Collection<String> stringify(Collection<?> c) {
		Collection<String> rv=new ArrayList<String>();
		for(Object o : c) {
			rv.add(String.valueOf(o));
		}
		return rv;
	}

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		initClient();
	}

	@Override
	protected void tearDown() throws Exception {
		// Shut down, start up, flush, and shut down again.  Error tests have
		// unpredictable timing issues.
		client.shutdown();
		client=null;
		initClient();
		flushPause();
		assertTrue(client.flush().get());
		client.shutdown();
		client=null;
		super.tearDown();
	}

	protected void flushPause() throws InterruptedException {
		// nothing useful
	}

}