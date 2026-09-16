package dev.goodrich.althalt.fixture;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import net.minecraft.SharedConstants;

/** Completes a real Minecraft status request and ping/pong on the loopback listener. */
public final class StatusPingFixture {
    private StatusPingFixture() {}

    public static CompletableFuture<Void> serveOnce(ServerSocket listener) throws IOException {
        listener.setSoTimeout(3000);
        return CompletableFuture.runAsync(() -> {
            try (var socket = listener.accept()) {
                socket.setSoTimeout(3000);
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();
                byte[] handshake = readPacket(input);
                if (handshake.length == 0 || handshake[handshake.length - 1] != 1) {
                    throw new IOException("Expected a status handshake.");
                }
                byte[] request = readPacket(input);
                if (request.length != 1 || request[0] != 0) throw new IOException("Expected a status request.");
                byte[] json = ("{\"version\":{\"name\":\"26.2\",\"protocol\":"
                        + SharedConstants.getCurrentVersion().protocolVersion()
                        + "},\"players\":{\"max\":20,\"online\":3},\"description\":{\"text\":\"AltHalt ping test\"}}")
                        .getBytes(StandardCharsets.UTF_8);
                ByteArrayOutputStream status = new ByteArrayOutputStream();
                status.write(0);
                writeVarInt(status, json.length);
                status.write(json);
                writePacket(output, status.toByteArray());
                byte[] ping = readPacket(input);
                if (ping.length != 9 || ping[0] != 1) throw new IOException("Expected a ping request.");
                writePacket(output, ping);
            } catch (IOException error) {
                throw new java.util.concurrent.CompletionException(error);
            }
        });
    }

    private static byte[] readPacket(InputStream input) throws IOException {
        int length = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int value = input.read();
            if (value < 0) throw new IOException("Unexpected end of packet.");
            length |= (value & 127) << shift;
            if ((value & 128) == 0) {
                if (length < 0 || length > 4096) throw new IOException("Invalid packet size.");
                byte[] packet = input.readNBytes(length);
                if (packet.length != length) throw new IOException("Incomplete packet.");
                return packet;
            }
        }
        throw new IOException("Invalid packet length.");
    }

    private static void writePacket(OutputStream output, byte[] packet) throws IOException {
        writeVarInt(output, packet.length);
        output.write(packet);
        output.flush();
    }

    private static void writeVarInt(OutputStream output, int value) throws IOException {
        do {
            int part = value & 127;
            value >>>= 7;
            output.write(value == 0 ? part : part | 128);
        } while (value != 0);
    }
}
