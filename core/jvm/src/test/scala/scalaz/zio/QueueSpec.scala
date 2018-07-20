package scalaz.zio

import org.specs2.Specification
import org.specs2.concurrent.ExecutionEnv
import org.specs2.specification.AroundTimeout

import scala.collection.immutable.Range
import scala.concurrent.duration._

class QueueSpec(implicit ee: ExecutionEnv) extends Specification with AroundTimeout with RTS {

  def is =
    "QueueSpec".title ^ s2"""
    Make a Queue and
    add values then call
      `take` to retrieve them in correct order. ${upTo(1.second)(e1)}
      `interruptTake`to interrupt fiber which is suspended on `take`. ${upTo(1.second)(e2)}
      `interruptPutter`to interrupt fiber which is suspended on `offer`. ${upTo(1.second)(e3)}
    `take` is called by fiber waiting on values to be added to the queue and join the fiber to get the added values correctly. ${upTo(
      1.second
    )(e4)}
    fork 10 takers and offer 10 values, the values must be correct after join those fibers ${upTo(1.second)(e5)}
    fork 10 putters and offer for each one 10 values then take the values 100 times, the values must be correct after join those fibers ${upTo(
      1.second
    )(e6)}
    make capacity = 10, then put 20 values then check if the size is 10 ${upTo(1.second)(e7)}
    the order is preserved even if we exceed the capacity of the queue ${upTo(1.second)(e8)}
    take can be interrupted and all resources are released ${upTo(1.second)(e9)}
    offer can be interrupted and all resources are released ${upTo(1.second)(e10)}
    make an unbounded queue, add and retrieve values in correct order ${upTo(1.second)(e11)}
    """

  def e1 = unsafeRun(
    for {
      queue <- Queue.bounded[Void, Int](100)
      _     <- queue.offer(10)
      v1    <- queue.take
      _     <- queue.offer(20)
      v2    <- queue.take
    } yield (v1 must_=== 10) and (v2 must_=== 20)
  )
  def e2 = unsafeRun(
    for {
      queue <- Queue.bounded[Void, Int](100)
      _     <- queue.take[Void].fork[Void, Void, Int]
      check <- (queue.interruptTake[Void](new Exception("interrupt take in e2")) <* IO.sleep(1.millis)).doWhile(!_)
      _     <- queue.offer(25)
      v     <- queue.take
    } yield (check must beTrue) and (v must_== 25)
  )

  def e3 = unsafeRun(
    for {
      queue <- Queue.bounded[Void, Int](0)
      _     <- queue.offer(14).fork[Void, Void, Unit]
      check <- (queue.interruptOffer[Void](new Exception("interrupt offer in e3")) <* IO.sleep(1.millis)).doWhile(!_)
      _     <- queue.offer[Void](12)
      v     <- queue.take
    } yield (check must beTrue) and (v must_=== 12)
  )

  def e4 = unsafeRun(
    for {
      queue <- Queue.bounded[Void, String](100)
      f1 <- queue
             .take[Void]
             .zipWith(queue.take[Void])(_ + _)
             .fork[Void, Void, String]
      _ <- queue.offer[Void]("don't ") *> queue.offer[Void]("give up :D")
      v <- f1.join
    } yield v must_=== "don't give up :D"
  )

  import scala.concurrent.duration._

  def e5 =
    unsafeRun(for {
      queue <- Queue.bounded[Void, Int](10)
      _     <- IO.forkAll(List.fill(10)(queue.take[Void].toUnit))
      _     <- waitForSize(queue, -10)
      _     <- Range.inclusive(1, 10).map(queue.offer[Void]).foldLeft[IO[Void, Unit]](IO.unit)(_ *> _)
      _     <- queue.offer(37)
      v     <- queue.take
    } yield v must_=== 37)

  def e6 =
    unsafeRun(for {
      queue <- Queue.bounded[Void, Int](10)
      order = Range.inclusive(1, 10).toList
      _     <- IO.forkAll(order.map(queue.offer[Void]))
      _     <- waitForSize(queue, 10)
      l     <- queue.take.repeatNFold[List[Int]](10)(List.empty[Int], (l, i) => i :: l)
    } yield l.toSet must_=== order.toSet)

  def e7 =
    unsafeRun(for {
      queue <- Queue.bounded[Void, Int](10)
      _     <- queue.offer[Void](1).repeatN(20).fork[Void, Void, Unit]
      _     <- waitForSize(queue, 11)
      size  <- queue.size
    } yield size must_=== 11)

  def e8 =
    unsafeRun(for {
      queue  <- Queue.bounded[Void, Int](5)
      orders = Range.inclusive(1, 10).toList
      _      <- IO.forkAll(orders.map(n => waitForSize(queue, n - 1) *> queue.offer(n)))
      _      <- waitForSize(queue, 10)
      l      <- queue.take.repeatNFold[List[Int]](10)(List.empty[Int], (l, i) => i :: l)
    } yield l.reverse must_=== orders)

  def e9 = unsafeRun(
    for {
      queue <- Queue.bounded[Void, Int](100)
      f     <- queue.take[Void].fork[Void, Void, Int]
      _     <- f.interrupt(new Exception("interrupt fiber in e9"))
      size  <- queue.size[Void]
    } yield size must_=== 0
  )

  def e10 = unsafeRun(
    for {
      queue <- Queue.bounded[Void, Int](0)
      f     <- queue.offer[Void](1).fork[Void, Void, Unit]
      _     <- f.interrupt(new Exception("interrupt fiber in e10"))
      size  <- queue.size[Void]
    } yield size must_=== 0
  )

  def e11 = unsafeRun(
    for {
      queue <- Queue.unbounded[Void, Int]
      _     <- queue.offer(1)
      _     <- queue.offer(2)
      _     <- queue.offer(3)
      v1    <- queue.take
      v2    <- queue.take
      v3    <- queue.take
    } yield (v1 must_=== 1) and (v2 must_=== 2) and (v3 must_=== 3)
  )

  private def waitForSize[A](queue: Queue[A], size: Int): IO[Void, Int] =
    (queue.size[Void] <* IO.sleep(1.millis)).doWhile(_ != size)
}
