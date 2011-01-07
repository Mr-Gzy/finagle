package com.twitter.finagle.integration

import scala.collection.JavaConversions._

import java.net.SocketAddress
import java.util.concurrent.{Executors, TimeUnit}

import org.jboss.netty.bootstrap.ServerBootstrap
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory
import org.jboss.netty.channel._
import org.jboss.netty.buffer._
import org.jboss.netty.channel.group.DefaultChannelGroup
import org.jboss.netty.handler.codec.http._
import org.jboss.netty.util.HashedWheelTimer

import com.twitter.conversions.time._
import com.twitter.ostrich.StatsCollection

import com.twitter.finagle.util.Conversions._
import com.twitter.util.{RandomSocket, Duration}

object EmbeddedServer {
  def apply() = new EmbeddedServer(RandomSocket())
  val executor = Executors.newCachedThreadPool()
  val timer = new HashedWheelTimer(10, TimeUnit.MILLISECONDS)
}

class EmbeddedServer(val addr: SocketAddress) {
  import EmbeddedServer._

  // (Publically accessible) stats covering this server.
  val stats = new StatsCollection

  // Server state:
  private[this] var isApplicationNonresponsive = false
  private[this] var isConnectionNonresponsive = false
  private[this] var isBelligerent = false
  private[this] var latency = 0.seconds

  private[this] val channels = new DefaultChannelGroup

  private[this] val bootstrap = new ServerBootstrap(
    new NioServerSocketChannelFactory(executor, executor))

  bootstrap.setPipelineFactory(new ChannelPipelineFactory {
    def getPipeline = {
      val pipeline = Channels.pipeline()
      pipeline.addLast("transposer", new SimpleChannelDownstreamHandler {
        override def writeRequested(ctx: ChannelHandlerContext, e: MessageEvent) {
          if (!isBelligerent)
            return super.writeRequested(ctx, e)

          // Garble the message a bit.
          val buffer = e.getMessage.asInstanceOf[ChannelBuffer]
          val bytes = new Array[Byte](buffer.readableBytes)
          buffer.getBytes(0, bytes)
          val transposed = bytes map { byte => (byte + 1) toByte }
          val transposedBuffer = ChannelBuffers.wrappedBuffer(transposed)

          Channels.write(ctx, e.getFuture, transposedBuffer)
        }
      })

      pipeline.addLast("decoder", new HttpRequestDecoder)
      pipeline.addLast("encoder", new HttpResponseEncoder)
      pipeline.addLast("logger", new SimpleChannelHandler {
        override def channelClosed(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
          channels.remove(ctx.getChannel)
          stats.incr("closed")
          super.channelClosed(ctx, e)
        }

        override def channelOpen(ctx: ChannelHandlerContext, e: ChannelStateEvent) {
          if (isConnectionNonresponsive)
            ctx.getChannel.setReadable(false)

          channels.add(ctx.getChannel)
          stats.incr("opened")
          super.channelClosed(ctx, e)
        }

        override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
          stats.incr("requests")
          super.messageReceived(ctx, e)
        }

      })

      pipeline.addLast("latency", new SimpleChannelDownstreamHandler {
        override def writeRequested(ctx: ChannelHandlerContext, e: MessageEvent) {
          if (latency != 0.seconds)
            timer(latency) { super.writeRequested(ctx, e) }
          else
            super.writeRequested(ctx, e)
        }
      })

      pipeline.addLast("dots", new SimpleChannelUpstreamHandler {
        override def messageReceived(ctx: ChannelHandlerContext, e: MessageEvent) {
          val response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
          response.setContent(ChannelBuffers.wrappedBuffer("..........".getBytes))
          response.setHeader("Content-Length", "10")
          if (!isApplicationNonresponsive)
            ctx.getChannel.write(response)
        }
      })
      pipeline
    }
  })

  private[this] var serverChannel = bootstrap.bind(addr)

  def stop() {
    if (serverChannel.isOpen)
      serverChannel.close().awaitUninterruptibly()

    channels.close().awaitUninterruptibly()
    channels.clear()
  }

  def start() {
    if (!serverChannel.isOpen)
      serverChannel = bootstrap.bind(addr)
  }

  def becomeApplicationNonresponsive() {
    isApplicationNonresponsive = true
  }

  def becomeConnectionNonresponsive() {
    isConnectionNonresponsive = true
    channels foreach { _.setReadable(false) }
  }

  def becomeBelligerent() {
    isBelligerent = true
  }

  def setLatency(latency: Duration) {
    this.latency = latency
  }

  // TODO: turn responsiveness back on.
}
