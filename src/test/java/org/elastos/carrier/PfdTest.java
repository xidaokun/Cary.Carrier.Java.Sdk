package org.elastos.carrier;

import org.elastos.portForwarding.PfdAgent;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;

public class PfdTest {

    private static final String localDataPath = System.getProperty("user.dir") + File.separator + "store";

    private static PfdAgent pfdAgent;

    private final String serverId = "192.168.0.1";

    @Test
    public void pairServer() {
        try {
            pfdAgent.pairServer(serverId, "hello");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Test
    public void restfulHttp() {

    }

    @BeforeClass
    public static void setUp() {
        pfdAgent = PfdAgent.singleton(localDataPath);
        pfdAgent.start();
    }
}
