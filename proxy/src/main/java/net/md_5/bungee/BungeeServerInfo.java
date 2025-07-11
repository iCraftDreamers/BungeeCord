package net.md_5.bungee;

import com.google.common.base.Preconditions;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.*;
import lombok.Getter; //import lombok.RequiredArgsConstructor;
import lombok.Synchronized;
import lombok.ToString;
import net.md_5.bungee.api.Callback;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.ServerPing;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.connection.PingHandler;
import net.md_5.bungee.netty.HandlerBoss;
import net.md_5.bungee.netty.PipelineUtils;
import net.md_5.bungee.protocol.DefinedPacket;
import net.md_5.bungee.protocol.packet.PluginMessage;

// CHECKSTYLE:OFF
//@RequiredArgsConstructor
@ToString(of =
{
    "name", "socketAddress", "restricted"
})
// CHECKSTYLE:ON
public class BungeeServerInfo implements ServerInfo
{

    @Getter
    private final String name;
    @Getter
    private SocketAddress socketAddress;
    private final Collection<ProxiedPlayer> players = new ArrayList<>();
    @Getter
    private final String motd;
    @Getter
    private final boolean restricted;
    @Getter
    private final Queue<DefinedPacket> packetQueue = new LinkedList<>();
    private Date lastUpdateDate = new Date();
    private final int ttl = 1000;

    public BungeeServerInfo(String name, SocketAddress socketAddress, String motd, boolean restricted)
    {
        this.name = name;
        this.socketAddress = socketAddress;
        this.motd = motd;
        this.restricted = restricted;
    }

    @Synchronized("players")
    public void addPlayer(ProxiedPlayer player)
    {
        players.add( player );
    }

    @Synchronized("players")
    public void removePlayer(ProxiedPlayer player)
    {
        players.remove( player );
    }

    @Synchronized("players")
    @Override
    public Collection<ProxiedPlayer> getPlayers()
    {
        return Collections.unmodifiableCollection( new HashSet<>( players ) );
    }

    @Override
    public String getPermission()
    {
        return "bungeecord.server." + name;
    }

    @Override
    public boolean canAccess(CommandSender player)
    {
        Preconditions.checkNotNull( player, "player" );
        return !restricted || player.hasPermission( getPermission() );
    }

    @Override
    public boolean equals(Object obj)
    {
        return ( obj instanceof ServerInfo ) && Objects.equals( getAddress(), ( (ServerInfo) obj ).getAddress() );
    }

    @Override
    public int hashCode()
    {
        return socketAddress.hashCode();
    }

    @Override
    public void sendData(String channel, byte[] data)
    {
        sendData( channel, data, true );
    }

    @Override
    public boolean sendData(String channel, byte[] data, boolean queue)
    {
        Preconditions.checkNotNull( channel, "channel" );
        Preconditions.checkNotNull( data, "data" );

        Server server;
        synchronized ( players )
        {
            server = ( players.isEmpty() ) ? null : players.iterator().next().getServer();
        }

        if ( server != null )
        {
            server.sendData( channel, data );
            return true;
        } else if ( queue )
        {
            synchronized ( packetQueue )
            {
                packetQueue.add( new PluginMessage( channel, data, false ) );
            }
        }
        return false;
    }

    private long lastPing;
    private ServerPing cachedPing;

    public void cachePing(ServerPing serverPing)
    {
        if ( ProxyServer.getInstance().getConfig().getRemotePingCache() > 0 )
        {
            this.cachedPing = serverPing;
            this.lastPing = System.currentTimeMillis();
        }
    }

    @Override
    public InetSocketAddress getAddress()
    {
        InetSocketAddress base = (InetSocketAddress) socketAddress;
        String str = base.getHostString();
        boolean isIpAddress = str.charAt( str.length() - 1 ) >= '0' && str.charAt( str.length() - 1 ) <= '9';
        if ( isIpAddress || lastUpdateDate.getTime() > System.currentTimeMillis() - ttl )
        {
            return base;
        }
        lastUpdateDate = new Date();
        InetSocketAddress target = new InetSocketAddress( base.getHostString(), base.getPort() );
        socketAddress = target;
        return target;
    }

    @Override
    public void ping(final Callback<ServerPing> callback)
    {
        ping( callback, ProxyServer.getInstance().getProtocolVersion() );
    }

    public void ping(final Callback<ServerPing> callback, final int protocolVersion)
    {
        Preconditions.checkNotNull( callback, "callback" );

        int pingCache = ProxyServer.getInstance().getConfig().getRemotePingCache();
        if ( pingCache > 0 && cachedPing != null && ( System.currentTimeMillis() - lastPing ) > pingCache )
        {
            cachedPing = null;
        }

        if ( cachedPing != null )
        {
            callback.done( cachedPing, null );
            return;
        }

        ChannelFutureListener listener = new ChannelFutureListener()
        {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception
            {
                if ( future.isSuccess() )
                {
                    future.channel().pipeline().get( HandlerBoss.class ).setHandler( new PingHandler( BungeeServerInfo.this, callback, protocolVersion ) );
                } else
                {
                    callback.done( null, future.cause() );
                }
            }
        };
        new Bootstrap()
                .channel( PipelineUtils.getChannel( socketAddress ) )
                .group( BungeeCord.getInstance().eventLoops )
                .handler( ProxyServer.getInstance().unsafe().getServerInfoChannelInitializer().getChannelInitializer() )
                .option( ChannelOption.CONNECT_TIMEOUT_MILLIS, BungeeCord.getInstance().getConfig().getRemotePingTimeout() )
                .remoteAddress( socketAddress )
                .connect()
                .addListener( listener );
    }
}
