package agc.bench;

import org.geysermc.mcprotocollib.network.ClientSession;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.ConnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSwingPacket;

import java.util.BitSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCProtocolLib-backed bot against the verified 26.2-SNAPSHOT API.
 *
 * <p>Signatures confirmed via javap against protocol-26.2-SNAPSHOT:</p>
 * <ul>
 *   <li>{@code ClientNetworkSessionFactory.factory().setAddress(h,p).setProtocol(mp).create()}</li>
 *   <li>{@code new ServerboundMovePlayerPosPacket(onGround, horizontalCollision, x, y, z)}</li>
 *   <li>{@code new ServerboundMovePlayerPosRotPacket(onGround, horizontalCollision, x, y, z, yaw, pitch)}</li>
 *   <li>{@code new ServerboundSwingPacket(Hand)}</li>
 *   <li>{@code new ServerboundChatPacket(msg, timestamp, salt, signature, offset, acknowledged, checksum)}</li>
 *   <li>{@code new ServerboundChatCommandPacket(command)}</li>
 * </ul>
 *
 * ALL third-party protocol usage stays in this file so future version bumps are cheap.
 */
public final class BotClient implements BotHandle {

    private final String host;
    private final int port;
    private final String username;

    private volatile ClientSession session;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicLong packetsReceived = new AtomicLong();

    // Client-side kinematics: the server trusts these client-authoritative packets.
    private volatile double x = 64.5, y = -60.0, z = 64.5;
    private volatile float yaw, pitch;

    public BotClient(final String host, final int port, final String username) {
        this.host = host;
        this.port = port;
        this.username = username;
    }

    @Override
    public void connect() {
        final MinecraftProtocol protocol = new MinecraftProtocol(this.username);
        final ClientSession s = ClientNetworkSessionFactory.factory()
            .setAddress(this.host, this.port)
            .setProtocol(protocol)
            .create();
        s.addListener(new SessionAdapter() {
            @Override
            public void connected(final ConnectedEvent event) {
                // Play-phase reached once the server completes join; conservative flag set here.
                connected.set(true);
            }

            @Override
            public void packetReceived(final Session session, final Packet packet) {
                packetsReceived.incrementAndGet();
            }

            @Override
            public void disconnected(final DisconnectedEvent event) {
                connected.set(false);
            }
        });
        s.connect();
        this.session = s;
    }

    @Override
    public boolean isConnected() {
        final ClientSession s = this.session;
        return this.connected.get() && s != null && s.isConnected();
    }

    @Override
    public void moveToward(final double tx, final double tz) {
        final double cx = this.x, cz = this.z;
        final double dx = tx - cx;
        final double dz = tz - cz;
        final double dist = Math.hypot(dx, dz);
        if (dist > 0.01) {
            final double step = Math.min(0.215, dist); // ~walking speed per tick
            this.x = cx + dx / dist * step;
            this.z = cz + dz / dist * step;
            this.yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        }
        send(new ServerboundMovePlayerPosPacket(true, false, this.x, this.y, this.z));
    }

    @Override
    public void setLook(final float yaw, final float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
        send(new ServerboundMovePlayerPosRotPacket(true, false,
            this.x, this.y, this.z, this.yaw, this.pitch));
    }

    @Override
    public void swingArm() {
        send(new ServerboundSwingPacket(Hand.MAIN_HAND));
    }

    @Override
    public void chat(final String message) {
        // Unsigned offline chat: null signature, empty acknowledgement set.
        send(new ServerboundChatPacket(message, System.currentTimeMillis(), 0L, null, 0, new BitSet(), 0));
    }

    @Override
    public void runCommand(final String commandWithoutSlash) {
        send(new ServerboundChatCommandPacket(commandWithoutSlash));
    }

    @Override
    public void disconnect() {
        this.connected.set(false);
        final ClientSession s = this.session;
        if (s != null) {
            try {
                s.disconnect("bench done");
            } catch (final Throwable ignored) {}
        }
    }

    public long packetsReceived() {
        return this.packetsReceived.get();
    }

    private void send(final Packet packet) {
        final ClientSession s = this.session;
        if (!this.connected.get() || s == null) {
            return;
        }
        try {
            s.send(packet);
        } catch (final Throwable t) {
            this.connected.set(false);
        }
    }
}
