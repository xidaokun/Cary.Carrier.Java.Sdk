package org.elastos.portForwarding;

import org.elastos.carrier.ConnectionStatus;
import org.elastos.carrier.FriendInfo;
import org.elastos.carrier.Log;
import org.elastos.carrier.PresenceStatus;
import org.elastos.carrier.exceptions.CarrierException;
import org.elastos.carrier.session.AbstractStreamHandler;
import org.elastos.carrier.session.PortForwardingProtocol;
import org.elastos.carrier.session.Session;
import org.elastos.carrier.session.SessionRequestCompleteHandler;
import org.elastos.carrier.session.Stream;
import org.elastos.carrier.session.StreamState;
import org.elastos.carrier.session.StreamType;

import java.net.ServerSocket;

public class PfdServer extends AbstractStreamHandler implements SessionRequestCompleteHandler {
    private static String TAG = "PfServer";

    private FriendInfo mFriendInfo;
    private Session mSession;
    private String mPort;
    private int mPfId;
    private Stream mStream;
    private StreamState mState = StreamState.Closed;

    private boolean mNeedClosePortforwarding = false;

    private String storePath;

    PfdServer(String storePath) {
        this.storePath = storePath;
    }

    public String getHost() {
        return "127.0.0.1";
    }

    public String getPort() {
        return mPort;
    }

    public String getName() {
        return mFriendInfo.getName();
    }

    public String getServerId() {
        return mFriendInfo.getUserId();
    }

    public void setPort(String port) {
        if (mPort != port) {
            mPort = port;

            if (mState == StreamState.Connected) {
                try {
                    mNeedClosePortforwarding = true;
                    openPortforwarding();
                } catch (CarrierException e) {
                    e.printStackTrace();
                    Log.e(TAG, "Portforwarding to " + getServerId() + " opened error.");
                }
                return;
            }
        }
    }

    public void setInfo(FriendInfo friendInfo) {
        mFriendInfo = friendInfo;
    }

    public void setConnectionStatus(ConnectionStatus connectionStatus) {
        mFriendInfo.setConnectionStatus(connectionStatus);
    }

    public void setPresenceStatus(PresenceStatus presence) {
        mFriendInfo.setPresence(presence);
    }

    public boolean isOnline() {
        return mFriendInfo.getConnectionStatus() == ConnectionStatus.Connected &&
                mFriendInfo.getPresence() == PresenceStatus.None;
    }

    @Override
    public void onCompletion(Session session, int status, String reason, String sdp) {
        if (status != 0) {
            Log.i(TAG, String.format("Session request completion with error (%d:%s", status, reason));
            close();
            return;
        }

        try {
            session.start(sdp);
            Log.i(TAG, "Session started success.");
        } catch (CarrierException e) {
            Log.e(TAG, "Session start error " + e.getErrorCode());
        }
    }

    @Override
    public void onStateChanged(Stream stream, StreamState state) {
        Log.i(TAG, "onStateChanged : " + stream.getStreamId() + "  :  " + state);
        mState = state;
        try {
            switch (state) {
                case Initialized:
                    mSession.request(this);
                    Log.i(TAG, "Session request to " + getServerId() + " sent.");
                    break;

                case TransportReady:
                    Log.i(TAG, "Stream to " + getServerId() + " transport ready");
                    break;

                case Connected:
                    Log.i(TAG, "Stream to " + getServerId() + " connected.");
                    mStream = stream;
                    openPortforwarding();
                    break;

                case Deactivated:
                    Log.i(TAG, "Stream deactived");
                    close();
                    break;
                case Closed:
                    Log.i(TAG, "Stream closed");
                    close();
                    break;
                case Error:
                    Log.i(TAG, "Stream error");
                    close();
                    break;
            }
        } catch (CarrierException e) {
            Log.e(TAG, String.format("Stream error (0x%x)", e.getErrorCode()));
            close();
        }
    }

    private int findFreePort() {
        int port;

        try {
            ServerSocket socket = new ServerSocket(0);
            port = socket.getLocalPort();
            socket.close();

        } catch (Exception e) {
            port = -1;
        }

        return port;
    }

    public void setupPortforwarding() {
        if (!isOnline()) {
            Log.i(TAG, "Friend Offline");
            return;
        }

        if (mState == StreamState.Initialized || mState == StreamState.TransportReady
                || mState == StreamState.Connecting) {
            Log.i(TAG, "Friend Inprogress");
            return;
        } else if (mState == StreamState.Connected) {
            try {
                openPortforwarding();
                Log.i(TAG, "Friend Ready");
            } catch (CarrierException e) {
                e.printStackTrace();

                Log.e(TAG, "Portforwarding to " + getServerId() + " opened error.");
            }
            return;
        } else {
            mState = StreamState.Closed;

            int sopt = Stream.PROPERTY_MULTIPLEXING
                    | Stream.PROPERTY_PORT_FORWARDING
                    | Stream.PROPERTY_RELIABLE;

            try {
                mSession = PfdAgent.singleton(this.storePath).getSessionManager()
                        .newSession(mFriendInfo.getUserId());
                mSession.addStream(StreamType.Application, sopt, this);
            } catch (CarrierException e) {
                e.printStackTrace();

                if (mSession == null) {
                    Log.e(TAG, String.format("New session to %s error (0x%x)",
                            getServerId(), e.getErrorCode()));
                } else {
                    Log.e(TAG, String.format("Add stream error (0x%x)", e.getErrorCode()));
                    mSession.close();
                    mSession = null;
                }
            }
        }
    }

    private void openPortforwarding() throws CarrierException {
        if (mPfId > 0 && !mNeedClosePortforwarding) {
            Log.i(TAG, "Portforwarding to " + getName() + " already opened.");
        } else {
            if (mPfId > 0) {
                mStream.closePortForwarding(mPfId);
                mPfId = -1;
                mNeedClosePortforwarding = false;
            }

            String port = mPort;
            if (port == null || port.isEmpty()) {
                port = String.valueOf(findFreePort());
            }
            mPfId = mStream.openPortForwarding("hivenode", PortForwardingProtocol.TCP,
                    "127.0.0.1", port);

            mPort = port;

            Log.i(TAG, "Portforwarding to " + getServerId() + " opened.");
        }
    }

    public void close() {
        if (mSession != null) {
            mSession.close();
            mSession = null;
            mStream = null;
            mState = StreamState.Closed;
            mPfId = -1;
        }
    }

    public boolean isConnected() {
        if (mPfId > 0) {
            return true;
        } else {
            setupPortforwarding();
            return false;
        }
    }
}
