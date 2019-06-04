package fr.pchab.webrtcclient;

import android.opengl.EGLContext;
import android.util.JsonWriter;
import android.util.Log;

import com.github.nkzawa.emitter.Emitter;
import com.github.nkzawa.socketio.client.IO;
import com.github.nkzawa.socketio.client.Socket;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoCapturerAndroid;
import org.webrtc.VideoSource;

import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class WebRtcClient {
    private final static String TAG = WebRtcClient.class.getCanonicalName();
    private final static String NICKNAME = "Streamer8462";
    private final static String ROOM_NAME = "Room3412";
    private final static Lock LOCK_SYNC = new ReentrantLock();
    private final static int MAX_PEER = 2;
    private boolean[] endPoints = new boolean[MAX_PEER];
    private PeerConnectionFactory factory;
    private HashMap<String, Peer> peers = new HashMap<>();
    private LinkedList<PeerConnection.IceServer> iceServers = new LinkedList<>();
    private PeerConnectionParameters pcParams;
    private MediaConstraints pcConstraints = new MediaConstraints();
    private MediaStream localMS;
    private Peer localPeer;
    private VideoSource videoSource;
    private RtcListener mListener;
    private Socket client;
    private String id;
    private boolean offerRecieved = false;

    /**
     * Implement this interface to be notified of events.
     */
    public interface RtcListener {
        void onCallReady(String callId);

        void onStatusChanged(String newStatus);

        void onLocalStream(MediaStream localStream);

        void onAddRemoteStream(MediaStream remoteStream, int endPoint);

        void onRemoveRemoteStream(int endPoint);
    }

    private interface Command {
        void execute(String peerId, JSONObject payload) throws JSONException;
    }

    private class CreateOfferCommand implements Command {
        public void execute(String peerId, JSONObject payload) throws JSONException {
            Log.d(TAG, "CreateOfferCommand");
            Peer peer = peers.get(peerId);
            peer.pc.createOffer(peer, pcConstraints);
        }
    }

    private class CreateAnswerCommand implements Command {
        public void execute(String peerId, JSONObject payload) throws JSONException {
            offerRecieved = true;
            Log.d(TAG, "CreateAnswerCommand");
            Peer peer = peers.get(peerId);
            SessionDescription sdp = new SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(payload.getString("type")),
                    payload.getString("sdp")
            );
            peer.pc.setRemoteDescription(peer, sdp);
            peer.pc.createAnswer(peer, pcConstraints);
            Log.d(TAG, "Sent answer " + pcConstraints.toString());
        }
    }

    private class SetRemoteSDPCommand implements Command {
        public void execute(String peerId, JSONObject payload) throws JSONException {
            Log.d(TAG, "SetRemoteSDPCommand");
            Peer peer = peers.get(peerId);
            SessionDescription sdp = new SessionDescription(
                    SessionDescription.Type.fromCanonicalForm(payload.getString("type")),
                    payload.getString("sdp")
            );
            peer.pc.setRemoteDescription(peer, sdp);
        }
    }

    private class EndOfIceCandidatesCommand implements Command {
        public void execute(String peerId, JSONObject payload) throws JSONException {
            Log.d(TAG, "EndOfIceCandidatesCommand");
        }
    }

    private class AddIceCandidateCommand implements Command {
        public void execute(String peerId, JSONObject payload) throws JSONException {
            Log.d(TAG, "AddIceCandidateCommand");
            PeerConnection pc = peers.get(peerId).pc;
            JSONObject payloadContents = new JSONObject(payload.getString("candidate"));
            String sdpMid =  payloadContents.getString("sdpMid");
            int sdpMlineIndex = payloadContents.getInt("sdpMLineIndex");

            String payloadString = payload.toString();

            if (pc.getRemoteDescription() != null) {
                IceCandidate candidate = new IceCandidate(
                        sdpMid, sdpMlineIndex, payload.toString());
                pc.addIceCandidate(candidate);
            }
        }
    }

    /**
     * Send a message through the signaling server
     *
     * @param to      id of recipient
     * @param type    type of message
     * @param payload payload of message
     * @throws JSONException
     */
    public void sendMessage(String to, String type, JSONObject payload) throws JSONException {
        JSONObject message = new JSONObject();
        message.put("to", to);
        message.put("type", type);
        message.put("payload", payload);
        client.emit("message", message);
    }

    private class MessageHandler {
        private HashMap<String, Command> commandMap;

        private MessageHandler() {
            this.commandMap = new HashMap<>();
            commandMap.put("init", new CreateOfferCommand());
            commandMap.put("offer", new CreateAnswerCommand());
            commandMap.put("answer", new SetRemoteSDPCommand());
            commandMap.put("candidate", new AddIceCandidateCommand());
            commandMap.put("endOfCandidates", new EndOfIceCandidatesCommand());
        }

        private Emitter.Listener onLoggedIn = new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                try {
                    JSONArray data = (JSONArray) args[0];
                    ConfigureId(data.getString(0));
                }
                catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        };

        private Emitter.Listener onMessage = new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                    JSONObject data = (JSONObject) args[0];

                    try {
                        String from = data.getString("from");
                        String type = data.getString("type");
                        JSONObject payload = null;

                        Log.d(TAG, "Message:  " + type + " from " + from);
                        Log.d(TAG, "Data:  " + data.toString());

                        if (!type.equals("init") && !type.equals("endOfCandidates")) {
                            Log.d(TAG, "Fetching payload for " + type);
                            payload = data.getJSONObject("payload");
                        }

                        // if peer is unknown, try to add him
                        if (!peers.containsKey(from)) {
                            // if MAX_PEER is reach, ignore the call
                            int endPoint = findEndPoint();
                            if (endPoint != MAX_PEER) {
                                Peer peer = addPeer(from, endPoint);
                                peer.pc.addStream(localMS);
                                commandMap.get(type).execute(from, payload);
                            }
                        } else {
                            commandMap.get(type).execute(from, payload);
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }

        };

        private Emitter.Listener onId = new Emitter.Listener() {
            @Override
            public void call(Object... args) {
                id = (String) args[0];
                sendInfo();
                client.emit("join", ROOM_NAME);
                mListener.onCallReady(id);
            }
        };
    }

    private void ConfigureId(String id) {
        if(this.id != null) {
            return;  //TODO:  Null ID on disconnect.
        }
        this.id = id;
        sendInfo();
        client.emit("join", ROOM_NAME);
        mListener.onCallReady(id);

        //start("android_test");
    }

    private class Peer implements SdpObserver, PeerConnection.Observer {
        private PeerConnection pc;
        private String id;
        private int endPoint;

        @Override
        public void onCreateSuccess(final SessionDescription sdp) {
            // TODO: modify sdp to use pcParams preferred codecs
            try {
                JSONObject payload = new JSONObject();
                payload.put("type", sdp.type.canonicalForm());
                payload.put("sdp", sdp.description);
                sendMessage(id, sdp.type.canonicalForm(), payload);
                pc.setLocalDescription(Peer.this, sdp);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onSetSuccess() {
        }

        @Override
        public void onCreateFailure(String s) {
        }

        @Override
        public void onSetFailure(String s) {
        }

        @Override
        public void onSignalingChange(PeerConnection.SignalingState signalingState) {
        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
            if (iceConnectionState == PeerConnection.IceConnectionState.DISCONNECTED) {
                removePeer(id);
                mListener.onStatusChanged("DISCONNECTED");
            }
        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
        }

        @Override
        public void onIceCandidate(final IceCandidate candidate) {
            try {
                JSONObject payload = new JSONObject();

                JSONObject candidatePayload = new JSONObject();
                candidatePayload.put("candidate", candidate.sdp);
                candidatePayload.put("label", candidate.sdpMLineIndex);
                candidatePayload.put("id", candidate.sdpMid);
                candidatePayload.put("sdpMid", candidate.sdpMid);
                candidatePayload.put("sdp", candidate.sdp);
                candidatePayload.put("sdpMLineIndex", candidate.sdpMLineIndex);

                payload.put("label", candidate.sdpMLineIndex);
                payload.put("id", candidate.sdpMid);
                payload.put("candidate", candidatePayload);
                sendMessage(id, "candidate", payload);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onAddStream(MediaStream mediaStream) {
            Log.d(TAG, "onAddStream " + mediaStream.label());
            // remote streams are displayed from 1 to MAX_PEER (0 is localStream)
            mListener.onAddRemoteStream(mediaStream, endPoint + 1);
        }

        @Override
        public void onRemoveStream(MediaStream mediaStream) {
            Log.d(TAG, "onRemoveStream " + mediaStream.label());
            removePeer(id);
        }

        @Override
        public void onDataChannel(DataChannel dataChannel) {
        }

        @Override
        public void onRenegotiationNeeded() {

        }

        public Peer(String id, int endPoint) {
            Log.d(TAG, "new Peer: " + id + " " + endPoint);
            this.pc = factory.createPeerConnection(iceServers, pcConstraints, this);
            this.id = id;
            this.endPoint = endPoint;

            pc.addStream(localMS); //, new MediaConstraints()

            mListener.onStatusChanged("CONNECTING");
        }
    }

    private Peer addPeer(String id, int endPoint) {
        Peer peer = new Peer(id, endPoint);
        peers.put(id, peer);

        endPoints[endPoint] = true;
        return peer;
    }

    private void removePeer(String id) {
        Peer peer = peers.get(id);
        mListener.onRemoveRemoteStream(peer.endPoint);
        peer.pc.close();
        peers.remove(peer.id);
        endPoints[peer.endPoint] = false;
    }

    public WebRtcClient(RtcListener listener, String host, PeerConnectionParameters params, EGLContext mEGLcontext) {
        mListener = listener;
        pcParams = params;
        PeerConnectionFactory.initializeAndroidGlobals(listener, true, true,
                params.videoCodecHwAcceleration, mEGLcontext);
        factory = new PeerConnectionFactory();
        MessageHandler messageHandler = new MessageHandler();

        try {
            client = IO.socket(host);
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
        client.on("id", messageHandler.onId);
        client.on("message", messageHandler.onMessage);
        client.on("loggedin", messageHandler.onLoggedIn);
        client.connect();



        iceServers.add(new PeerConnection.IceServer("stun:23.21.150.121"));
        iceServers.add(new PeerConnection.IceServer("stun:stun.l.google.com:19302"));

        pcConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        pcConstraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        pcConstraints.optional.add(new MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"));
    }

    /**
     * Call this method in Activity.onPause()
     */
    public void onPause() {
        if (videoSource != null) videoSource.stop();
    }

    /**
     * Call this method in Activity.onResume()
     */
    public void onResume() {
        if (videoSource != null) videoSource.restart();
    }

    /**
     * Call this method in Activity.onDestroy()
     */
    public void onDestroy() {
        for (Peer peer : peers.values()) {
            peer.pc.dispose();
        }
        if (videoSource != null) {
            videoSource.dispose();
        }
        factory.dispose();
        client.disconnect();
        client.close();
    }

    private int findEndPoint() {
        for (int i = 0; i < MAX_PEER; i++) if (!endPoints[i]) return i;
        return MAX_PEER;
    }

    /**
     * Start the client.
     * <p>
     * Set up the local stream and notify the signaling server.
     * Call this method after onCallReady.
     *
     * @param name client name
     */
    public void start(String name) {
        setCamera();
        try {
            JSONObject message = new JSONObject();
            message.put("name", name);
            client.emit("readyToStream", message);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void sendInfo() {
        try {
            JSONObject message = new JSONObject();
            message.put("nickname", NICKNAME);
            message.put("audEncoder", "opus");
            message.put("id", id);
            message.put("mode", "sender");
            message.put("multicastServer", "");
            message.put("multicastType", "");
            message.put("nickName", NICKNAME);
            message.put("peerType", "cpp");
            message.put("vidBitrate", "500000");
            message.put("enable_audio", "false");
            message.put("vidEncoder", "vp8");
            message.put("turn_password", "");
            message.put("turn_server", "");
            message.put("turn_user", "");


            client.emit("setinfo", message);
        }
        catch (JSONException e) {
            e.printStackTrace();
        }
    }
    private void setCamera() {
        localMS = factory.createLocalMediaStream("ARDAMS");

        if (pcParams.videoCallEnabled) {
            MediaConstraints videoConstraints = new MediaConstraints();
            videoConstraints.mandatory.add(new MediaConstraints.KeyValuePair("maxHeight", Integer.toString(pcParams.videoHeight)));
            videoConstraints.mandatory.add(new MediaConstraints.KeyValuePair("maxWidth", Integer.toString(pcParams.videoWidth)));
            videoConstraints.mandatory.add(new MediaConstraints.KeyValuePair("maxFrameRate", Integer.toString(pcParams.videoFps)));
            videoConstraints.mandatory.add(new MediaConstraints.KeyValuePair("minFrameRate", Integer.toString(pcParams.videoFps)));

            VideoCapturer vc = getVideoCapturer();

            videoSource = factory.createVideoSource(vc, videoConstraints);
            localMS.addTrack(factory.createVideoTrack("ARDAMSv0", videoSource));
        }

        //AudioSource audioSource = factory.createAudioSource(new MediaConstraints());
        //localMS.addTrack(factory.createAudioTrack("ARDAMSa0", audioSource));

        mListener.onLocalStream(localMS);
    }

    private VideoCapturer getVideoCapturer() {
        String frontCameraDeviceName = VideoCapturerAndroid.getNameOfBackFacingDevice();
        return VideoCapturerAndroid.create(frontCameraDeviceName);
    }
}
