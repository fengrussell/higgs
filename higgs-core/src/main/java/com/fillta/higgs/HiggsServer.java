package com.fillta.higgs;

import com.fillta.functional.Function;
import com.fillta.higgs.sniffing.HttpDetector;
import com.fillta.higgs.sniffing.ProtocolDetector;
import com.fillta.higgs.sniffing.ProtocolSniffer;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public abstract class HiggsServer<T, OM, IM, SM> extends EventProcessor<T, OM, IM, SM> {

	private int port;
	protected ServerBootstrap bootstrap = new ServerBootstrap();
	public Channel channel;
	//set of protocol sniffers
	private final Set<ProtocolDetector> detectors =
			Collections.newSetFromMap(new ConcurrentHashMap<ProtocolDetector, Boolean>());
	private boolean sniffProtocol;
	private boolean enableGZip;
	private boolean enableSSL;

	public HiggsServer(int port) {
		this.port = port;
	}

	/**
	 * Set the server's port. Only has any effect if the server is not already bound to a port.
	 *
	 * @param port
	 */
	public void setPort(final int port) {
		this.port = port;
	}

	public void bind() {
		bind(new Function() {
			public void apply() {
				//NO-OP
			}
		});
	}

	public void bind(Function function) {
		try {
			bootstrap.group(parentGroup(), childGroup())
					.channel(channelClass())
					.localAddress(port);
			if (!sniffProtocol) {
				bootstrap.childHandler(initializer());
			} else {
				final EventProcessor me = this;
				bootstrap.childHandler(new ChannelInitializer<SocketChannel>() {
					public void initChannel(SocketChannel ch) throws Exception {
						ChannelPipeline pipeline=ch.pipeline();
						beforeProtocolSniffer(pipeline);
						pipeline.addLast(new ProtocolSniffer(detectors, me, enableSSL, enableGZip));
					}
				});
			}
			channel = bootstrap.bind().sync().channel();
			if (function != null) {
				function.apply();
			}
		} catch (InterruptedException ie) {
		}
	}

	public void beforeProtocolSniffer(ChannelPipeline pipeline) {
	}

	/**
	 * Adds an {@link HttpDetector} to the pipeline allowing the provided events processor to receive
	 * HTTP requests.
	 *
	 * @param events
	 */
	public void enableHTTPDetection(EventProcessor<String, HttpResponse, HttpRequest, Object> events) {
		if (events == null)
			throw new NullPointerException("Null HTTP Event processor not acceptable");
		addProtocolDetector(new HttpDetector(events));
	}

	public void setEnableGZip(final boolean enableGZip) {
		this.enableGZip = enableGZip;
	}

	public void setEnableSSL(final boolean enableSSL) {
		this.enableSSL = enableSSL;
	}

	/**
	 * Enables or disables protocol sniffing
	 * If sniffing is enabled, {@link #initializer()} is not called
	 * And GZip + SSL detection is automatically done.
	 *
	 * @param sniff
	 */
	public void setSniffProtocol(boolean sniff) {
		sniffProtocol = sniff;
		setEnableGZip(sniff);
		setEnableSSL(sniff);
	}

	/**
	 * Adds a protocol detector that is used to modify request pipelines automatically
	 * Automatically enables sniffing and disables use of {@link #initializer()}
	 *
	 * @param detector The detector to be added
	 * @param <T>
	 */
	public <T extends ProtocolDetector> void addProtocolDetector(T detector) {
		if (detector == null)
			throw new NullPointerException("Null detectors not acceptable");
		setSniffProtocol(true);
		detectors.add(detector);
	}

	public ChannelInitializer<SocketChannel> initializer() {
		throw new UnsupportedOperationException("Protocol detection is disabled," +
				" you must override public ChannelInitializer<SocketChannel> initializer()");
	}

	public EventLoopGroup parentGroup() {
		return new NioEventLoopGroup();
	}

	public EventLoopGroup childGroup() {
		return new NioEventLoopGroup();
	}

	public Class<? extends Channel> channelClass() {
		return NioServerSocketChannel.class;
	}
}
