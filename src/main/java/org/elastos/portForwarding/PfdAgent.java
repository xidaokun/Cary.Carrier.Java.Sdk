package org.elastos.portForwarding;


import org.elastos.carrier.AbstractCarrierHandler;
import org.elastos.carrier.Carrier;
import org.elastos.carrier.ConnectionStatus;
import org.elastos.carrier.FriendInfo;
import org.elastos.carrier.Log;
import org.elastos.carrier.PresenceStatus;
import org.elastos.carrier.UserInfo;
import org.elastos.carrier.exceptions.CarrierException;
import org.elastos.carrier.session.Manager;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PfdAgent extends AbstractCarrierHandler {
    private static String TAG = "PfdAgent";

    public static PfdAgent pfdAgentInst;

    private Carrier mCarrier;
    private Manager mSessionManager;
    private ConnectionStatus mStatus;
    private boolean mReady;

    private PfdServer mCheckedServer;
    private List<PfdServer> mServerList;
    private Map<String, PfdServer> mServerMap;

    private String storePath;

    public static PfdAgent singleton(String storePath) {
        if (pfdAgentInst == null) {
            pfdAgentInst = new PfdAgent(storePath);
        }
        return pfdAgentInst;
    }

    private PfdAgent(String storePath) {
        this.storePath = storePath;
        mStatus = ConnectionStatus.Disconnected;
        mReady = false;

        mServerList = new ArrayList<>();
        mServerMap = new HashMap();
    }

    public void checkLogin() throws CarrierException {
        String elaCarrierPath = storePath + File.separator + "elaCarrier";
        File elaCarrierDir = new File(elaCarrierPath);
        if (!elaCarrierDir.exists()) {
            elaCarrierDir.mkdirs();
        }

        boolean udpEnabled = false;
        List<Carrier.Options.BootstrapNode> bootstraps = new ArrayList<>();

        try {
            String configString = Config.CARRIER_CONFIG;
            JSONObject jsonObject = new JSONObject(configString);

            udpEnabled = jsonObject.getBoolean("udp_enabled");

            JSONArray jsonBootstraps = jsonObject.getJSONArray("bootstraps");
            for (int i = 0, m = jsonBootstraps.length(); i < m; i++) {
                JSONObject jsonBootstrap = jsonBootstraps.getJSONObject(i);
                Carrier.Options.BootstrapNode bootstrap = new Carrier.Options.BootstrapNode();
                String ipv4 = jsonBootstrap.optString("ipv4");
                if (ipv4 != null) {
                    bootstrap.setIpv4(ipv4);
                }
                String ipv6 = jsonBootstrap.optString("ipv6");
                if (ipv4 != null) {
                    bootstrap.setIpv6(ipv6);
                }
                bootstrap.setPort(jsonBootstrap.getString("port"));
                bootstrap.setPublicKey(jsonBootstrap.getString("public_key"));
                bootstraps.add(bootstrap);
            }
        } catch (Exception e) {
            // report exception
        }

        Carrier.Options options = new Carrier.Options();
        options.setPersistentLocation(elaCarrierPath).
                setUdpEnabled(udpEnabled).
                setBootstrapNodes(bootstraps);

        mCarrier = Carrier.createInstance(options, this);
        Log.i(TAG, "Agent elastos carrier instance created successfully");

        mSessionManager = Manager.createInstance(mCarrier);
        Log.i(TAG, "Agent session manager created successfully");
    }

    public boolean isReady() {
        return mReady;
    }

    public void start() {
        try {
            if (mCarrier == null) {
                checkLogin();
            }

            mCarrier.start(50);
        } catch (CarrierException e) {
            Log.i(TAG, String.format("checkLogin error (0x%x)", e.getErrorCode()));
        }
    }

    public void logout() {
//        String elaCarrierPath = mContext.getFilesDir().getAbsolutePath() + "/elaCarrier";
//        File elaCarrierDir = new File(elaCarrierPath);
//        if (elaCarrierDir.exists()) {
//            File[] files = elaCarrierDir.listFiles();
//            for (File file : files) {
//                file.delete();
//            }
//        }

        this.kill();
    }

    public void kill() {
        for (PfdServer server : mServerList) {
            server.close();
        }

        mServerMap.clear();
        mServerList.clear();

        if (mCarrier != null) {
            mSessionManager.cleanup();
            mCarrier.kill();
        }

        pfdAgentInst = null;
    }

    public Manager getSessionManager() {
        return mSessionManager;
    }


    public void setCheckedServer(String serverId) {
        PfdServer server = mServerMap.get(serverId);

        if (server != null && server != mCheckedServer) {
            Log.i(TAG, "Checked server changed to " + serverId);

            if (mCheckedServer != null) {
                mCheckedServer.close();
            }
            mCheckedServer = server;

            if (mStatus == ConnectionStatus.Connected) {
                mCheckedServer.setupPortforwarding();
            }
        }
    }

    public PfdServer getCheckedServer() {
        return mCheckedServer;
    }

    public List<PfdServer> getServerList() {
        return mServerList;
    }

    public PfdServer getServer(String serverId) {
        return mServerMap.get(serverId);
    }

    public void pairServer(String serverId, String password) throws CarrierException {
        if (!mCarrier.isFriend(serverId)) {
            String hello = hash256(password);
            mCarrier.addFriend(serverId, hello);
            Log.i(TAG, "Friend request to portforwarding server " + serverId + " success");
        }
    }

    public void unpairServer(String serverId) throws CarrierException {
        if (mCarrier.isFriend(serverId)) {
            mCarrier.removeFriend(serverId);
            Log.i(TAG, "Removed " + serverId + " friend");
        }
    }

    public UserInfo getInfo() throws CarrierException {
        return mCarrier.getSelfInfo();
    }

    @Override
    public void onConnection(Carrier carrier, ConnectionStatus status) {
        Log.i(TAG, "Agent connection status changed to " + status);
        mStatus = status;
    }

    @Override
    public void onReady(Carrier carrier) {
        try {
            UserInfo info;
            info = carrier.getSelfInfo();

            if (info.getName().isEmpty()) {
                String manufacturer = "MANUFACTURER";
                String name = "MODEL";

                if (!name.startsWith(manufacturer))
                    name = manufacturer + " " + name;
                if (name.length() > UserInfo.MAX_USER_NAME_LEN)
                    name = name.substring(0, UserInfo.MAX_USER_NAME_LEN);

                info.setName(name);

                carrier.setSelfInfo(info);
            }
        } catch (CarrierException e) {
            Log.e(TAG, String.format("Update current user name error (0x%x)", e.getErrorCode()));
            e.printStackTrace();
            return;
        }

        Log.i(TAG, "Elastos carrier instance is ready.");

        if (mCheckedServer == null) {
            for (PfdServer server : mServerList) {
                if (server.isOnline()) {
                    mCheckedServer = server;
                    break;
                }
            }
        }

        mReady = true;
    }

    @Override
    public void onFriends(Carrier carrier, List<FriendInfo> friends) {
        Log.i(TAG, "Client portforwarding agent received friend list: " + friends);

        for (FriendInfo info : friends) {
            String serverId = info.getUserId();
            PfdServer server;

            server = mServerMap.get(serverId);
            if (server == null) {
                server = new PfdServer(this.storePath);
                mServerList.add(server);
                mServerMap.put(serverId, server);
            }

            server.setInfo(info);
            server.setConnectionStatus(info.getConnectionStatus());
            server.setPresenceStatus(info.getPresence());
        }
    }

    @Override
    public void onFriendInfoChanged(Carrier carrier, String friendId, FriendInfo friendInfo) {
        PfdServer server = mServerMap.get(friendId);
        assert (server != null);

        Log.i(TAG, "Server " + friendId + "info changed to " + friendInfo);

        server.setInfo(friendInfo);
    }

    @Override
    public void onFriendConnection(Carrier carrier, String friendId, ConnectionStatus status) {
        PfdServer server = mServerMap.get(friendId);
        assert (server != null);

        Log.i(TAG, "Server " + friendId + " connection status changed to " + status);

        server.setConnectionStatus(status);

        if (server.equals(mCheckedServer)) {
            if (server.isOnline()) {
                server.setupPortforwarding();
            } else {
                server.close();
            }
        }
    }

    @Override
    public void onFriendPresence(Carrier carrier, String friendId, PresenceStatus presence) {
        PfdServer server = mServerMap.get(friendId);
        assert (server != null);

        Log.i(TAG, "Server" + friendId + "presence changed to " + presence);

        server.setPresenceStatus(presence);

        if (server.equals(mCheckedServer)) {
            if (server.isOnline()) {
                server.setupPortforwarding();
            } else {
                server.close();
            }
        }
    }

    @Override
    public void onFriendAdded(Carrier carrier, FriendInfo friendInfo) {
        PfdServer server = new PfdServer(this.storePath);
        server.setInfo(friendInfo);
        server.setConnectionStatus(friendInfo.getConnectionStatus());
        server.setPresenceStatus(friendInfo.getPresence());

        mServerList.add(server);
        mServerMap.put(server.getServerId(), server);

        Log.i(TAG, "Server " + server.getServerId() + " added: " + friendInfo);

        if (mCheckedServer == null) {
            mCheckedServer = server;
        }
    }

    @Override
    public void onFriendRemoved(Carrier carrier, String friendId) {
        PfdServer server = mServerMap.remove(friendId);
        assert (server != null);

        mServerList.remove(server);
        Log.i(TAG, "Portforwarding server " + friendId + "removed");

        if (mCheckedServer.equals(server)) {
            mCheckedServer = null;
            for (PfdServer svr : mServerList) {
                if (svr.isOnline())
                    mCheckedServer = svr;
            }
        }
    }

    private String hash256(String string) {
        MessageDigest md = null;
        String result = null;
        byte[] bt = string.getBytes();
        try {
            md = MessageDigest.getInstance("SHA-256");
            md.update(bt);
            result = bytes2Hex(md.digest()); // to HexString
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
        return result;
    }

    private String bytes2Hex(byte[] bts) {
        String des = "";
        String tmp = null;
        for (int i = 0; i < bts.length; i++) {
            tmp = (Integer.toHexString(bts[i] & 0xFF));
            if (tmp.length() == 1) {
                des += "0";
            }
            des += tmp;
        }
        return des;
    }
}
