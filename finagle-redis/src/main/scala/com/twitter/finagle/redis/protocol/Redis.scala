package com.twitter.finagle.redis

import com.twitter.finagle._
import com.twitter.finagle.redis.protocol._
import com.twitter.finagle.stats.{NullStatsReceiver, StatsReceiver}
import com.twitter.finagle.tracing.{Annotation, Trace}
import com.twitter.naggati.{Codec => NaggatiCodec}
import com.twitter.util.Future
import org.jboss.netty.channel.{ChannelPipelineFactory, Channels}

object Redis {
  def apply(stats: StatsReceiver = NullStatsReceiver) = new Redis(stats)
  def get() = apply()
}

class Redis(stats: StatsReceiver) extends CodecFactory[Command, Reply] {
  def server = Function.const {
    new Codec[Command, Reply] {
      def pipelineFactory = new ChannelPipelineFactory {
        def getPipeline() = {
          val pipeline = Channels.pipeline()
          val commandCodec = new CommandCodec
          val replyCodec = new ReplyCodec

          pipeline.addLast("codec", new NaggatiCodec(commandCodec.decode, replyCodec.encode))

          pipeline
        }
      }
    }
  }

  def client = Function.const {
    new Codec[Command, Reply] {

      def pipelineFactory = new ChannelPipelineFactory {
        def getPipeline() = {
          val pipeline = Channels.pipeline()
          val commandCodec = new CommandCodec
          val replyCodec = new ReplyCodec

          pipeline.addLast("codec", new NaggatiCodec(replyCodec.decode, commandCodec.encode))

          pipeline
        }
      }

      override def prepareConnFactory(underlying: ServiceFactory[Command, Reply]) = {
        new RedisTracingFilter() andThen new RedisLoggingFilter(stats) andThen underlying
      }

    }
  }
}

private class RedisTracingFilter extends SimpleFilter[Command, Reply] {
  override def apply(command: Command, service: Service[Command, Reply]) = Trace.unwind {
    Trace.recordRpcname("redis", command.command)
    Trace.record(Annotation.ClientSend())
    service(command) map { response =>
      Trace.record(Annotation.ClientRecv())
      response
    }
  }
}

private class RedisLoggingFilter(stats: StatsReceiver)
  extends SimpleFilter[Command, Reply] {

  private[this] val error = stats.scope("error")
  private[this] val succ  = stats.scope("success")

  override def apply(command: Command, service: Service[Command, Reply]) = {
    service(command) map { response =>
      response match {
        case StatusReply(_)
          | IntegerReply(_)
          | BulkReply(_)
          | EmptyBulkReply()
          | MBulkReply(_)
          | EmptyMBulkReply()    => succ.counter(command.command).incr()
        case ErrorReply(message) => error.counter(command.command).incr()
        case _                   => error.counter(command.command).incr()
      }
      response
    }
  }
}
