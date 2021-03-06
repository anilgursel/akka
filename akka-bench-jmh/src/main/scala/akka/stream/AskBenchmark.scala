/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

import java.util.concurrent.{ CountDownLatch, TimeUnit }

import akka.NotUsed
import akka.actor.{ Actor, ActorRef, ActorSystem, Props }
import akka.remote.artery.{ BenchTestSource, LatchSink }
import akka.stream.scaladsl._
import akka.stream.testkit.scaladsl.StreamTestKit
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import org.openjdk.jmh.annotations._

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }

object AskBenchmark {
  final val OperationsPerInvocation = 100000
}

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.SECONDS)
@BenchmarkMode(Array(Mode.Throughput))
class AskBenchmark {
  import AskBenchmark._

  val config = ConfigFactory.parseString("""
    akka.actor.default-dispatcher {
      executor = "fork-join-executor"
      fork-join-executor {
        parallelism-factor = 1
      }
    }
    """)

  implicit val system = ActorSystem("MapAsyncBenchmark", config)
  import system.dispatcher

  var materializer: ActorMaterializer = _

  var testSource: Source[java.lang.Integer, NotUsed] = _

  var actor: ActorRef = _

  implicit val timeout = Timeout(10.seconds)

  @Param(Array("1", "4"))
  var parallelism = 0

  @Param(Array("false", "true"))
  var spawn = false

  @Setup
  def setup(): Unit = {
    val settings = ActorMaterializerSettings(system)
    materializer = ActorMaterializer(settings)

    testSource = Source.fromGraph(new BenchTestSource(OperationsPerInvocation))
    actor = system.actorOf(Props(new Actor {
      override def receive = {
        case element => sender() ! element
      }
    }))
  }

  @TearDown
  def shutdown(): Unit = {
    Await.result(system.terminate(), 5.seconds)
  }

  @Benchmark
  @OperationsPerInvocation(OperationsPerInvocation)
  def mapAsync(): Unit = {
    val latch = new CountDownLatch(1)

    testSource.ask[Int](parallelism)(actor).runWith(new LatchSink(OperationsPerInvocation, latch))(materializer)

    awaitLatch(latch)
  }

  private def awaitLatch(latch: CountDownLatch): Unit = {
    if (!latch.await(30, TimeUnit.SECONDS)) {
      StreamTestKit.printDebugDump(materializer.supervisor)
      throw new RuntimeException("Latch didn't complete in time")
    }
  }

}
