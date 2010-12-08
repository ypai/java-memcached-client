package net.spy.memcached;

import java.util.logging.Level;
import java.util.logging.Logger;
import net.spy.memcached.ops.Operation;
import net.spy.memcached.ops.OperationCallback;
import net.spy.memcached.ops.OperationStatus;
import net.spy.memcached.protocol.ascii.ExtensibleOperationImpl;
import java.nio.ByteBuffer;

/**
 *
 * @author Matt Ingenthron <ingenthr@cep.net>
 */
public class TimeoutNowriteTest extends ClientBaseCase {

	@Override
	protected void tearDown() throws Exception {
		// override teardown to avoid the flush phase
		client.shutdown();
	}

	@Override
	protected void initClient() throws Exception {
		client=new MemcachedClient(new DefaultConnectionFactory() {
			@Override
			public long getOperationTimeout() {
				return 1;
			}
			@Override
			public FailureMode getFailureMode() {
				return FailureMode.Retry;
			}},
			AddrUtil.getAddresses("127.0.0.1:11211"));
	}

	private void tryTimeout(String name, Runnable r) {
		try {
			r.run();
			fail("Expected timeout in " + name);
		} catch(OperationTimeoutException e) {
			// pass
		}
	}

	public void testTimeoutDontwrite() {
		Operation op = new ExtensibleOperationImpl(new OperationCallback(){
			public void complete() {
				System.err.println("Complete.");
			}

			public void receivedStatus(OperationStatus s) {
				System.err.println("Received a line.");
			}}) {

			@Override
			public void handleLine(String line) {
				System.out.println("Woo! A line!");
			}

			@Override
			public void initialize() {
				setBuffer(ByteBuffer.wrap("garbage\r\n".getBytes()));
			}};
	try {
	    Thread.sleep(2600);
	} catch (InterruptedException ex) {
	    // don't care
	}
		client.addOp("x", op);
	}

}
