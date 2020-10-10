package org.elastos.carrier;


import org.elastos.carrier.common.TestOptions;
import org.elastos.carrier.exceptions.CarrierException;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

public class GetInstanceTest extends AbstractCarrierHandler {
	private static final String TAG = "GetInstanceTest";

	private String getAppPath() {
		return System.getProperty("user.dir");
	}

	class TestHandler extends AbstractCarrierHandler {
		@Override
		public void onReady(Carrier carrier) {
			synchronized(carrier) {
				carrier.notify();
			}
		}
	}

	@Test
	public void testCarrier() {
		TestOptions options = new TestOptions(getAppPath());
		TestHandler handler = new TestHandler();

		try {
			Carrier carrier = Carrier.createInstance(options, handler);
			assertNotNull(carrier);

			carrier.start(0);
			synchronized(carrier) {
				carrier.wait();
			}
			assertEquals(carrier.getNodeId(), carrier.getUserId());

			carrier.kill();
//			assertNull(Carrier.getInstance());
		} catch (CarrierException | InterruptedException e) {
			e.printStackTrace();
			fail();
		}
	}
}
