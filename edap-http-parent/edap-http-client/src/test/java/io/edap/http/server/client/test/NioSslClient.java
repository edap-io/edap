package io.edap.http.server.client.test;

import io.edap.log.Logger;
import io.edap.log.LoggerManager;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NioSslClient {

    protected static ByteBuffer myAppData = ByteBuffer.allocate(4096);

    static Logger log = LoggerManager.getLogger(SslEngineTest.class);

    /**
     * Will contain this peer's encrypted data, that will be generated after {@link SSLEngine#wrap(ByteBuffer, ByteBuffer)}
     * is applied on {@link NioSslPeer#myAppData}. It should be initialized using {@link SSLSession#getPacketBufferSize()},
     * which returns the size up to which, SSL/TLS packets will be generated from the engine under a session.
     * All SSLEngine network buffers should be sized at least this large to avoid insufficient space problems when performing wrap and unwrap calls.
     */
    protected static ByteBuffer myNetData = ByteBuffer.allocate(4096);

    protected static ByteBuffer peerAppData = ByteBuffer.allocate(4096);;

    /**
     * Will contain the other peer's encrypted data. The SSL/TLS protocols specify that implementations should produce packets containing at most 16 KB of plaintext,
     * so a buffer sized to this value should normally cause no capacity problems. However, some implementations violate the specification and generate large records up to 32 KB.
     * If the {@link SSLEngine#unwrap(ByteBuffer, ByteBuffer)} detects large inbound packets, the buffer sizes returned by SSLSession will be updated dynamically, so the this peer
     * should check for overflow conditions and enlarge the buffer using the session's (updated) buffer size.
     */
    protected static ByteBuffer peerNetData = ByteBuffer.allocate(4096);;

    protected static ExecutorService executor = Executors.newSingleThreadExecutor();

    SSLEngine engine = null;
    SocketChannel socketChannel = null;

    public boolean connect(String remoteAddress, int port) throws Exception {
        socketChannel = SocketChannel.open();
        socketChannel.configureBlocking(false);
        socketChannel.connect(new InetSocketAddress(remoteAddress, port));
        while (!socketChannel.finishConnect()) {
            // can do something here...
        }
        SSLContext context = SSLContext.getDefault();
        engine = context.createSSLEngine(remoteAddress, port);
        engine.setUseClientMode(true);
        engine.beginHandshake();
        return doHandshake(socketChannel, engine);
    }

    protected static boolean doHandshake(SocketChannel socketChannel, SSLEngine engine) throws IOException {

        //log.debug("About to do handshake...");

        SSLEngineResult result;
        SSLEngineResult.HandshakeStatus handshakeStatus;

        // NioSslPeer's fields myAppData and peerAppData are supposed to be large enough to hold all message data the peer
        // will send and expects to receive from the other peer respectively. Since the messages to be exchanged will usually be less
        // than 16KB long the capacity of these fields should also be smaller. Here we initialize these two local buffers
        // to be used for the handshake, while keeping client's buffers at the same size.
        int appBufferSize = engine.getSession().getApplicationBufferSize();
        ByteBuffer myAppData = ByteBuffer.allocate(appBufferSize);
        ByteBuffer peerAppData = ByteBuffer.allocate(appBufferSize);
        myNetData.clear();
        peerNetData.clear();

        handshakeStatus = engine.getHandshakeStatus();
        while (handshakeStatus != SSLEngineResult.HandshakeStatus.FINISHED && handshakeStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
            switch (handshakeStatus) {
                case NEED_UNWRAP:
                    if (socketChannel.read(peerNetData) < 0) {
                        if (engine.isInboundDone() && engine.isOutboundDone()) {
                            return false;
                        }
                        try {
                            engine.closeInbound();
                        } catch (SSLException e) {
                            log.error("This engine was forced to close inbound, without having received the proper SSL/TLS close notification message from the peer, due to end of stream.");
                        }
                        engine.closeOutbound();
                        // After closeOutbound the engine will be set to WRAP state, in order to try to send a close message to the client.
                        handshakeStatus = engine.getHandshakeStatus();
                        break;
                    }
                    peerNetData.flip();
                    try {
                        result = engine.unwrap(peerNetData, peerAppData);
                        peerNetData.compact();
                        handshakeStatus = result.getHandshakeStatus();
                    } catch (SSLException sslException) {
                        log.error("A problem was encountered while processing the data that caused the SSLEngine to abort. Will try to properly close connection...");
                        engine.closeOutbound();
                        handshakeStatus = engine.getHandshakeStatus();
                        break;
                    }
                    switch (result.getStatus()) {
                        case OK:
                            break;
                        case BUFFER_OVERFLOW:
                            // Will occur when peerAppData's capacity is smaller than the data derived from peerNetData's unwrap.
                            peerAppData = enlargeApplicationBuffer(engine, peerAppData);
                            break;
                        case BUFFER_UNDERFLOW:
                            // Will occur either when no data was read from the peer or when the peerNetData buffer was too small to hold all peer's data.
                            peerNetData = handleBufferUnderflow(engine, peerNetData);
                            break;
                        case CLOSED:
                            if (engine.isOutboundDone()) {
                                return false;
                            } else {
                                engine.closeOutbound();
                                handshakeStatus = engine.getHandshakeStatus();
                                break;
                            }
                        default:
                            throw new IllegalStateException("Invalid SSL status: " + result.getStatus());
                    }
                    break;
                case NEED_WRAP:
                    myNetData.clear();
                    try {
                        result = engine.wrap(myAppData, myNetData);
                        handshakeStatus = result.getHandshakeStatus();
                    } catch (SSLException sslException) {
                        log.error("A problem was encountered while processing the data that caused the SSLEngine to abort. Will try to properly close connection...");
                        engine.closeOutbound();
                        handshakeStatus = engine.getHandshakeStatus();
                        break;
                    }
                    switch (result.getStatus()) {
                        case OK :
                            myNetData.flip();
                            while (myNetData.hasRemaining()) {
                                socketChannel.write(myNetData);
                            }
                            break;
                        case BUFFER_OVERFLOW:
                            // Will occur if there is not enough space in myNetData buffer to write all the data that would be generated by the method wrap.
                            // Since myNetData is set to session's packet size we should not get to this point because SSLEngine is supposed
                            // to produce messages smaller or equal to that, but a general handling would be the following:
                            myNetData = enlargePacketBuffer(engine, myNetData);
                            break;
                        case BUFFER_UNDERFLOW:
                            throw new SSLException("Buffer underflow occured after a wrap. I don't think we should ever get here.");
                        case CLOSED:
                            try {
                                myNetData.flip();
                                while (myNetData.hasRemaining()) {
                                    socketChannel.write(myNetData);
                                }
                                // At this point the handshake status will probably be NEED_UNWRAP so we make sure that peerNetData is clear to read.
                                peerNetData.clear();
                            } catch (Exception e) {
                                log.error("Failed to send server's CLOSE message due to socket channel's failure.");
                                handshakeStatus = engine.getHandshakeStatus();
                            }
                            break;
                        default:
                            throw new IllegalStateException("Invalid SSL status: " + result.getStatus());
                    }
                    break;
                case NEED_TASK:
                    Runnable task;
                    while ((task = engine.getDelegatedTask()) != null) {
                        executor.execute(task);
                    }
                    handshakeStatus = engine.getHandshakeStatus();
                    break;
                case FINISHED:
                    break;
                case NOT_HANDSHAKING:
                    break;
                default:
                    throw new IllegalStateException("Invalid SSL status: " + handshakeStatus);
            }
        }

        return true;

    }

    protected static ByteBuffer enlargePacketBuffer(SSLEngine engine, ByteBuffer buffer) {
        return enlargeBuffer(buffer, engine.getSession().getPacketBufferSize());
    }

    protected static ByteBuffer enlargeApplicationBuffer(SSLEngine engine, ByteBuffer buffer) {
        return enlargeBuffer(buffer, engine.getSession().getApplicationBufferSize());
    }

    /**
     * Compares <code>sessionProposedCapacity<code> with buffer's capacity. If buffer's capacity is smaller,
     * returns a buffer with the proposed capacity. If it's equal or larger, returns a buffer
     * with capacity twice the size of the initial one.
     *
     * @param buffer - the buffer to be enlarged.
     * @param sessionProposedCapacity - the minimum size of the new buffer, proposed by .
     * @return A new buffer with a larger capacity.
     */
    protected static ByteBuffer enlargeBuffer(ByteBuffer buffer, int sessionProposedCapacity) {
        if (sessionProposedCapacity > buffer.capacity()) {
            buffer = ByteBuffer.allocate(sessionProposedCapacity);
        } else {
            buffer = ByteBuffer.allocate(buffer.capacity() * 2);
        }
        return buffer;
    }

    /**
     * Handles {@link SSLEngineResult.Status#BUFFER_UNDERFLOW}. Will check if the buffer is already filled, and if there is no space problem
     * will return the same buffer, so the client tries to read again. If the buffer is already filled will try to enlarge the buffer either to
     * session's proposed size or to a larger capacity. A buffer underflow can happen only after an unwrap, so the buffer will always be a
     * peerNetData buffer.
     *
     * @param buffer - will always be peerNetData buffer.
     * @param engine - the engine used for encryption/decryption of the data exchanged between the two peers.
     * @return The same buffer if there is no space problem or a new buffer with the same data but more space.
     * @throws Exception
     */
    protected static ByteBuffer handleBufferUnderflow(SSLEngine engine, ByteBuffer buffer) {
        if (engine.getSession().getPacketBufferSize() < buffer.limit()) {
            return buffer;
        } else {
            ByteBuffer replaceBuffer = enlargePacketBuffer(engine, buffer);
            buffer.flip();
            replaceBuffer.put(buffer);
            return replaceBuffer;
        }
    }

    public void write(String message) throws IOException {
        write(socketChannel, engine, message);
    }

    /**
     * Implements the write method that sends a message to the server the client is connected to,
     * but should not be called by the user, since socket channel and engine are inner class' variables.
     * {@link NioSslClient#write(String)} should be called instead.
     *
     * @param message - message to be sent to the server.
     * @param engine - the engine used for encryption/decryption of the data exchanged between the two peers.
     * @throws IOException if an I/O error occurs to the socket channel.
     */
    protected void write(SocketChannel socketChannel, SSLEngine engine, String message) throws IOException {

        log.debug("About to write to the server...");

        myAppData.clear();
        myAppData.put(message.getBytes());
        myAppData.flip();
        while (myAppData.hasRemaining()) {
            // The loop has a meaning for (outgoing) messages larger than 16KB.
            // Every wrap call will remove 16KB from the original message and send it to the remote peer.
            myNetData.clear();
            SSLEngineResult result = engine.wrap(myAppData, myNetData);
            switch (result.getStatus()) {
                case OK:
                    myNetData.flip();
                    while (myNetData.hasRemaining()) {
                        socketChannel.write(myNetData);
                    }
                    log.debug("Message sent to the server: " + message);
                    break;
                case BUFFER_OVERFLOW:
                    myNetData = enlargePacketBuffer(engine, myNetData);
                    break;
                case BUFFER_UNDERFLOW:
                    throw new SSLException("Buffer underflow occured after a wrap. I don't think we should ever get here.");
                case CLOSED:
                    closeConnection(socketChannel, engine);
                    return;
                default:
                    throw new IllegalStateException("Invalid SSL status: " + result.getStatus());
            }
        }

    }

    /**
     * Public method to try to read from the server.
     *
     * @throws Exception
     */
    public void read() throws Exception {
        read(socketChannel, engine);
    }

    /**
     * Will wait for response from the remote peer, until it actually gets something.
     * Uses {@link SocketChannel#read(ByteBuffer)}, which is non-blocking, and if
     * it gets nothing from the peer, waits for {@code waitToReadMillis} and tries again.
     * <p/>
     * Just like {@link NioSslClient#read(SocketChannel, SSLEngine)} it uses inner class' socket channel
     * and engine and should not be used by the client. {@link NioSslClient#read()} should be called instead.
     *- message to be sent to the server.
     * @param engine - the engine used for encryption/decryption of the data exchanged between the two peers.
     * @throws Exception
     */
    protected void read(SocketChannel socketChannel, SSLEngine engine) throws Exception  {

        log.debug("About to read from the server...");

        peerNetData.clear();
        int waitToReadMillis = 50;
        boolean exitReadLoop = false;
        while (!exitReadLoop) {
            int bytesRead = socketChannel.read(peerNetData);
            if (bytesRead > 0) {
                peerNetData.flip();
                while (peerNetData.hasRemaining()) {
                    peerAppData.clear();
                    SSLEngineResult result = engine.unwrap(peerNetData, peerAppData);
                    switch (result.getStatus()) {
                        case OK:
                            peerAppData.flip();
                            if (peerAppData.remaining() > 0) {
                                String response = new String(peerAppData.array(), 0, peerAppData.remaining());
                                int index = response.indexOf("\r\n\r\n");
                                log.info("remain:{}, header len:{}",
                                        l -> l.arg(peerAppData.remaining()
                                                ).arg(index + 4));


                                log.debug("Server response: " + response);
                            }
                            exitReadLoop = true;
                            break;
                        case BUFFER_OVERFLOW:
                            peerAppData = enlargeApplicationBuffer(engine, peerAppData);
                            break;
                        case BUFFER_UNDERFLOW:
                            peerNetData = handleBufferUnderflow(engine, peerNetData);
                            break;
                        case CLOSED:
                            closeConnection(socketChannel, engine);
                            return;
                        default:
                            throw new IllegalStateException("Invalid SSL status: " + result.getStatus());
                    }
                }
            } else if (bytesRead < 0) {
                handleEndOfStream(socketChannel, engine);
                return;
            }
            Thread.sleep(waitToReadMillis);
        }
    }

    protected void handleEndOfStream(SocketChannel socketChannel, SSLEngine engine) throws IOException  {
        try {
            engine.closeInbound();
        } catch (Exception e) {
            log.error("This engine was forced to close inbound, without having received the proper SSL/TLS close notification message from the peer, due to end of stream.");
        }
        closeConnection(socketChannel, engine);
    }

    /**
     * Should be called when the client wants to explicitly close the connection to the server.
     *
     * @throws IOException if an I/O error occurs to the socket channel.
     */
    public void shutdown() throws IOException {
        log.debug("About to close connection with the server...");
        closeConnection(socketChannel, engine);
        executor.shutdown();
        log.debug("Goodbye!");
    }

    protected void closeConnection(SocketChannel socketChannel, SSLEngine engine) throws IOException  {
        engine.closeOutbound();
        doHandshake(socketChannel, engine);
        socketChannel.close();
    }
}
