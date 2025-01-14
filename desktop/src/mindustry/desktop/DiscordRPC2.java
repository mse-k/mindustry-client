package mindustry.desktop;

import arc.func.*;
import arc.util.*;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Simple class for handling Discord Rich Presence.
 * Implementation based on https://github.com/jagrosh/DiscordIPC
 * This the only know implementation that is pure Java; on Linux/Mac, this uses Java 16's new Unix sockets.
 * */
public final class DiscordRPC2 {
    private static int pid;
    private static long clientId;
    private static Pipe pipe;

    public static Cons<String> onActivityJoin = secret -> {};
    public static Cons<String> onActivitySpectate = secret -> {};
    public static Cons2<String, User> onActivityJoinRequest = (secret, user) -> {};
    public static Runnable onReady = () -> {};
    public static Cons<Throwable> onDisconnected = error -> {};
    public static Cons<Jval2> onClose = json -> {};

    /** Call before sending any presence updates. */
    public static void connect(long clientId) throws Exception{
        DiscordRPC2.clientId = clientId;
        String version = System.getProperty("java.version");

        int major = version.contains(".") ?
            Strings.parseInt(version.substring(0, version.indexOf('.'))) :
            Strings.parseInt(version);

        //on unix, this is supported on java >= 16 (unix sockets)
        //on windows, this is supported on java >= 9 (ProcessHandle#pid())
        if(!(major >= 16 || (OS.isWindows && major >= 9))){
            throw new Exception("Discord RPC is not supported on < Java " + (OS.isWindows ? "9" : "16") + ". Your version: " + version);
        }

        //use reflection to call Java 9 API
        Class<?> c = Class.forName("java.lang.ProcessHandle");
        Object current = c.getMethod("current").invoke(null);
        pid = ((Long)c.getMethod("pid").invoke(current)).intValue();

        checkConnected(false);
        try{
            pipe = Pipe.openPipe(clientId);
        }catch(Exception e){
            throw new NoDiscordClientException();
        }

        onReady.run();

        //start reading incoming events
        Thread t = new Thread(() -> {
            try{
                Packet p;
                while((p = pipe.read()).op != PacketOp.close){
                    Jval2 json = p.data;
                    if(json.has("cmd") && json.getString("cmd").equals("DISPATCH")){
                        try{
                            Jval2 data = json.get("data");
                            switch(json.getString("evt")){
                                case "ACTIVITY_JOIN":
                                    onActivityJoin.get(data.getString("secret"));
                                    break;
                                case "ACTIVITY_SPECTATE":
                                    onActivitySpectate.get(data.getString("secret"));
                                    break;
                                case "ACTIVITY_JOIN_REQUEST":
                                    Jval2 u = data.get("user");
                                    onActivityJoinRequest.get(data.getString("secret", null),
                                    new User(u.getString("username"), u.getString("discriminator"),
                                    Long.parseLong(u.getString("id")), u.getString("avatar", null))
                                    );
                                    break;
                            }
                        }catch(Exception ignored){
                        }
                    }
                }
                pipe.status = PipeStatus.disconnected;
                onClose.get(p.data);
            }catch(Throwable ex){
                pipe.status = PipeStatus.disconnected;
                onDisconnected.get(ex);
            }
        });

        t.setDaemon(true);
        t.start();
    }

    /** Updates the displayed rich presence. */
    public static void send(RichPresence presence){
        checkConnected(true);
        pipe.send(PacketOp.frame,
            Jval2.newObject()
                .put("cmd", "SET_ACTIVITY")
                .put("args", Jval2.newObject()
                    .put("pid", pid)
                    .put("activity", presence == null ? null : presence.toJson())));
    }

    /** Subscribes to all activity events. */
    public static void subscribe(){
        checkConnected(true);

        for(String value : new String[]{"ACTIVITY_JOIN", "ACTIVITY_SPECTATE", "ACTIVITY_JOIN_REQUEST"}){
            pipe.send(PacketOp.frame,
                Jval2.newObject()
                    .put("cmd", "SUBSCRIBE")
                    .put("evt", value));
        }
    }

    public static PipeStatus getStatus(){
        return pipe == null ? PipeStatus.uninitialized : pipe.status;
    }

    /**
     * Attempts to close an open connection to Discord.<br>
     * @throws IllegalStateException If a connection was not made prior to invoking
     * this method.
     */
    public static void close(){
        checkConnected(true);

        try{
            pipe.close();
        }catch(IOException ignored){
        }
    }

    /**
     * Makes sure that the client is connected (or not) depending on if it should
     * for the current state.
     * @param connected Whether to check in the context of the IPCClient being
     * connected or not.
     */
    private static void checkConnected(boolean connected){
        if(connected && getStatus() != PipeStatus.connected)
            throw new IllegalStateException(String.format("IPCClient (ID: %d) is not connected!", clientId));
        if(!connected && getStatus() == PipeStatus.connected)
            throw new IllegalStateException(String.format("IPCClient (ID: %d) is already connected!", clientId));
    }

    public static class Packet{
        public final PacketOp op;
        public final Jval2 data;

        public Packet(PacketOp op, Jval2 data){
            this.op = op;
            this.data = data;
        }

        public byte[] toBytes(){
            byte[] d = data.toString().getBytes();
            ByteBuffer packet = ByteBuffer.allocate(d.length + 2 * Integer.BYTES);
            packet.putInt(Integer.reverseBytes(op.ordinal()));
            packet.putInt(Integer.reverseBytes(d.length));
            packet.put(d);
            return packet.array();
        }

        @Override
        public String toString(){
            return "Pkt:" + op + data.toString();
        }
    }

    public enum PacketOp{
        handshake, frame, close, ping, pong
    }

    public static class NoDiscordClientException extends RuntimeException{

    }

    public static class User{
        public final String name;
        public final String discriminator;
        public final long id;
        @Nullable
        public final String avatar;

        public User(String name, String discriminator, long id, String avatar){
            this.name = name;
            this.discriminator = discriminator;
            this.id = id;
            this.avatar = avatar;
        }
    }

    public static class RichPresence{
        public String state;
        public String details;
        public long startTimestamp;
        public long endTimestamp;
        public String largeImageKey;
        public String largeImageText;
        public String smallImageKey;
        public String smallImageText;
        public String partyId;
        public int partySize;
        public int partyMax;
        public String matchSecret;
        public String joinSecret;
        public String spectateSecret;
        public String label1;
        public String url1;
        public String label2;
        public String url2;
        public boolean instance;

        public Jval2 toJson(){
            boolean useButtons = joinSecret == null && matchSecret == null && spectateSecret == null;

            return Jval2.newObject()
                .put("state", state)
                .put("details", details)
                .put("timestamps", Jval2.newObject()
                    .put("start", startTimestamp == 0 ? null : startTimestamp)
                    .put("end", endTimestamp == 0 ? null : endTimestamp))
                .put("assets", Jval2.newObject()
                    .put("large_image", largeImageKey)
                    .put("large_text", largeImageText)
                    .put("small_image", smallImageKey)
                    .put("small_text", smallImageText))
                .put("party", partyId == null ? null : Jval2.newObject()
                    .put("id", partyId)
                    .put("size", Jval2.newArray().add(partySize).add(partyMax)))
                .put("secrets", useButtons ? null : Jval2.newObject()
                    .put("join", joinSecret)
                    .put("spectate", spectateSecret)
                    .put("match", matchSecret))
                .put("buttons", useButtons ? buttons() : null)
                .put("instance", instance);
        }

        Jval2 buttons(){
            Jval2 buttons = Jval2.newArray();
            if (label1 != null && url1 != null) buttons.add(Jval2.newObject().put("label", label1).put("url", url1));
            if (label2 != null && url2 != null) buttons.add(Jval2.newObject().put("label", label2).put("url", url2));
            return buttons;
        }
    }

    public enum PipeStatus{
        uninitialized, connecting, connected, closed, disconnected
    }

    public abstract static class Pipe{
        private static final int version = 1;
        // a list of system property keys to get IPC file from different unix systems.
        private final static String[] unixPaths = {"XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP"};

        public PipeStatus status = PipeStatus.connecting;

        public static Pipe openPipe(long clientId) throws Exception{

            for(int i = 0; i < 10; i++){
                try{
                    String location = getPipeLocation(i);
                    Pipe pipe;
                    if(OS.isWindows){
                        pipe = new WindowsPipe(location);
                    }else if(OS.isLinux || OS.isMac){
                        pipe = new UnixPipe(location);
                    }else{
                        throw new RuntimeException("Unsupported OS");
                    }
                    pipe.send(PacketOp.handshake, Jval2.newObject().put("v", version).put("client_id", Long.toString(clientId)));
                    pipe.status = PipeStatus.connected;

                    //discard read packet, for some reason this fails to complete and blocks forever sometimes, so we need a future...
                    new FutureTask<>(pipe::read).get(1, TimeUnit.SECONDS);

                    return pipe;
                }catch(IOException ignored){
                }
            }

            throw new Exception("No Discord client found.");
        }

        private static String getPipeLocation(int i){
            if(System.getProperty("os.name").contains("Win"))
                return "\\\\?\\pipe\\discord-ipc-" + i;
            String tmppath = null;
            for(String str : unixPaths){
                tmppath = System.getenv(str);
                if(tmppath != null)
                    break;
            }
            if(tmppath == null)
                tmppath = "/tmp";
            return tmppath + "/discord-ipc-" + i;
        }

        public void send(PacketOp op, Jval2 data){
            try{
                write(new Packet(op, data.put("nonce", UUID.randomUUID().toString())).toBytes());
            }catch(IOException ex){
                status = PipeStatus.disconnected;
            }
        }

        public abstract Packet read() throws Exception;

        public abstract void write(byte[] b) throws IOException;

        public abstract void close() throws IOException;
    }

    public static class UnixPipe extends Pipe{
        private final SocketChannel socket;
        private final ByteBuffer buffer = ByteBuffer.allocate(1024 * 32);

        UnixPipe(String location) throws Exception{
            //this is java 16+ API, so needs reflection
            Method method = SocketChannel.class.getMethod("open", SocketAddress.class);
            Class<?> addressc = Class.forName("java.net.UnixDomainSocketAddress");
            Method construct = addressc.getMethod("of", String.class);

            socket = (SocketChannel)method.invoke(null, construct.invoke(null, location));
            socket.configureBlocking(true);
        }

        @Override
        public Packet read() throws Exception{
            buffer.position(0);

            while(socket.read(buffer) <= 0 && status == PipeStatus.connected){
                try{
                    Thread.sleep(100);
                }catch(InterruptedException ignored){
                }
            }

            buffer.position(0);

            if(status == PipeStatus.disconnected) throw new IOException("Disconnected!");
            if(status == PipeStatus.closed) return new Packet(PacketOp.close, null);

            PacketOp op = PacketOp.values()[Integer.reverseBytes(buffer.getInt())];
            byte[] data = new byte[Integer.reverseBytes(buffer.getInt())];
            buffer.get(data);

            return new Packet(op, Jval2.read(data));
        }

        @Override
        public void write(byte[] b) throws IOException{
            socket.write(ByteBuffer.wrap(b));
        }

        @Override
        public void close() throws IOException{
            send(PacketOp.close, Jval2.newObject());
            status = PipeStatus.closed;
            socket.close();
        }
    }

    public static class WindowsPipe extends Pipe{
        private final RandomAccessFile file;

        WindowsPipe(String location) throws Exception{
            this.file = new RandomAccessFile(location, "rw");
        }

        @Override
        public void write(byte[] b) throws IOException{
            file.write(b);
        }

        @Override
        public Packet read() throws Exception{
            while(file.length() == 0 && status == PipeStatus.connected){
                try{
                    Thread.sleep(100);
                }catch(InterruptedException ignored){
                }
            }

            if(status == PipeStatus.disconnected) throw new IOException("Disconnected!");
            if(status == PipeStatus.closed) return new Packet(PacketOp.close, null);

            PacketOp op = PacketOp.values()[Integer.reverseBytes(file.readInt())];
            int len = Integer.reverseBytes(file.readInt());
            byte[] d = new byte[len];

            file.readFully(d);
            return new Packet(op, Jval2.read(d));
        }

        @Override
        public void close() throws IOException{
            send(PacketOp.close, Jval2.newObject());
            status = PipeStatus.closed;
            file.close();
        }
    }
}
