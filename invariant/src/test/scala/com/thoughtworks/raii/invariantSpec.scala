package com.thoughtworks.raii

import java.io.{File, FileInputStream}

import org.scalatest._
import com.thoughtworks.raii.invariantSpec.Exceptions.{CanNotCloseResourceTwice, CanNotOpenResourceTwice}
import com.thoughtworks.raii.invariantSpec.FakeResource
import com.thoughtworks.raii.invariant._

import scalaz.syntax.all._
import scalaz._
import scala.collection.mutable
import scala.concurrent.{Await, Promise}
import scala.language.higherKinds
import scalaz.effect.IO
import scalaz.effect.IOInstances

private[raii] object invariantSpec {

  def scoped[Resource <: AutoCloseable](autoCloseable: => Resource): ResourceT[IO, Resource] =
    managedT[IO, Resource](autoCloseable)

  def managedT[F[_]: Applicative, Resource <: AutoCloseable](autoCloseable: => Resource): ResourceT[F, Resource] = {
    ResourceT(
      Applicative[F].point {
        new Releasable[F, Resource] {
          override val value: Resource = autoCloseable

          override def release(): F[Unit] = Applicative[F].point(value.close())
        }
      }
    )
  }

  object Exceptions {

    case class Boom() extends RuntimeException

    case class CanNotCloseResourceTwice() extends RuntimeException

    case class CanNotOpenResourceTwice() extends RuntimeException

    case class CanNotGenerateDataBecauseResourceIsNotOpen() extends RuntimeException

  }

  def createIdGenerator(): () => String = {
    var nextId = 0;
    { () =>
      val id = nextId
      nextId += 1
      id.toString
    }
  }

  final class FakeResource(allOpenedResources: mutable.HashMap[String, FakeResource], idGenerator: () => String)
      extends {
    val id = idGenerator()
  } with AutoCloseable {

    def this(allOpenedResources: mutable.HashMap[String, FakeResource], constantId: String) = {
      this(allOpenedResources, { () =>
        constantId
      })
    }

    if (allOpenedResources.contains(id)) {
      throw CanNotOpenResourceTwice()
    }
    allOpenedResources(id) = this

    override def close(): Unit = {
      val removed = allOpenedResources.remove(id)

      //noinspection OptionEqualsSome
      if (removed != Some(this)) {
        throw CanNotCloseResourceTwice()
      }
    }
  }

}

/**
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
final class invariantSpec extends AsyncFreeSpec with Matchers with Inside {

  import invariantSpec._

  import Exceptions._

  import scalaz.syntax.all._
  import com.thoughtworks.raii.invariant.ResourceT._

  "must acquire and release" in {
    val allOpenedResources = mutable.HashMap.empty[String, FakeResource]
    val mr0 = scoped(new FakeResource(allOpenedResources, "r0"))
    allOpenedResources.keys shouldNot contain("r0")

    val ioResource: ResourceT[IO, FakeResource] = mr0.map { r0 =>
      allOpenedResources("r0") should be(a[FakeResource])
      r0
    }

    ioResource.run.unsafePerformIO()

    allOpenedResources.keys shouldNot contain("r0")
  }

  "must release when error occur" ignore {

    val events = mutable.Buffer.empty[String]

    var seed = 0

    def nextId() = {
      val result = seed
      seed += 1
      result
    }

    class MyResource extends AutoCloseable {
      val id = nextId()
      events += s"acquire $id"

      override def close(): Unit = {
        val _ = events += s"release $id"
      }
    }

    val mr = scoped(new MyResource)

    try {
      val ioResource: ResourceT[IO, MyResource] = mr.map { r =>
        val _ = events += "error is coming"
        throw Boom()
        r
      }
      ioResource.run.unsafePerformIO()
    } catch {
      case _: Boom =>
    }

    events should be(mutable.Buffer("acquire 0", "error is coming", "release 0"))
  }

  "must throw IllegalStateException when release throw exception" ignore {

    val events = mutable.Buffer.empty[String]

    var seed = 0

    def nextId() = {
      val result = seed
      seed += 1
      result
    }

    class MyResource extends AutoCloseable {
      val id = nextId()
      events += s"acquire $id"

      override def close(): Unit = {
        val _ = events += s"release $id"
        throw Boom()
      }
    }

    val mr = scoped(new MyResource)

    intercept[IllegalStateException] {
      val ioResource: ResourceT[IO, MyResource] = mr.map { r =>
        events += "error is coming"
        r
      }
      ioResource.run.unsafePerformIO()
    }

    events should be(mutable.Buffer("acquire r", "error is coming", "release r"))
  }

  "must throw Exception when release throw exception and another error occur" ignore {
    val events = mutable.Buffer.empty[String]

    var seed = 0

    def nextId() = {
      val result = seed
      seed += 1
      result
    }

    class MyResource extends AutoCloseable {
      val id = nextId()
      events += s"acquire $id"

      override def close(): Unit = {
        val _ = events += s"release $id"
        throw Boom()
      }
    }

    val mr = scoped(new MyResource)

    intercept[Boom] {
      val ioResource: ResourceT[IO, MyResource] = mr.map { r =>
        events += "error is coming"
        throw Boom()
        r
      }
      ioResource.run.unsafePerformIO()
    }

    events should be(mutable.Buffer("acquire 0", "error is coming", "release 0"))
  }

  "must extract value" ignore {

    val events = mutable.Buffer.empty[String]

    var seed = 0

    def nextId() = {
      val result = seed
      seed += 1
      result
    }

    class MyResource extends AutoCloseable {
      val id = nextId()
      events += s"acquire $id"

      override def close(): Unit = {
        val _ = events += s"release $id"
      }

      def generateData() = Some(math.random)

    }

    val mr = scoped(new MyResource)

    val ioResource: ResourceT[IO, MyResource] = mr.map { r =>
      r
    }
    ioResource.run.unsafePerformIO()

    events should be(mutable.Buffer("acquire 0", "release 0"))
  }

  import com.thoughtworks.raii.invariant.ResourceT._

  "both of resources must acquire and release" in {

    val allOpenedResources = mutable.HashMap.empty[String, FakeResource]
    val mr0 = scoped(new FakeResource(allOpenedResources, "mr0"))
    val mr1 = scoped(new FakeResource(allOpenedResources, "mr1"))
    allOpenedResources.keys shouldNot contain("mr0")
    allOpenedResources.keys shouldNot contain("mr1")

    val ioResource0: ResourceT[IO, FakeResource] = mr0.flatMap { r0 =>
      allOpenedResources("mr0") should be(a[FakeResource])
      mr1.map { r1 =>
        allOpenedResources("mr1") should be(a[FakeResource])
        r1
      }
    }
    ioResource0.run.unsafePerformIO()

    allOpenedResources.keys shouldNot contain("mr0")
    allOpenedResources.keys shouldNot contain("mr1")
  }

  "must acquire and release twice" in {

    val allOpenedResources = mutable.HashMap.empty[String, FakeResource]
    val idGenerator = invariantSpec.createIdGenerator()
    val mr = scoped(new FakeResource(allOpenedResources, idGenerator))
    allOpenedResources.keys shouldNot contain("0")
    allOpenedResources.keys shouldNot contain("1")

    val ioResource0: ResourceT[IO, FakeResource] = mr.flatMap { r0 =>
      allOpenedResources("0") should be(a[FakeResource])
      mr.map { r1 =>
        allOpenedResources("1") should be(a[FakeResource])
        r1
      }
    }
    ioResource0.run.unsafePerformIO()

    allOpenedResources.keys shouldNot contain("0")
    allOpenedResources.keys shouldNot contain("1")

  }

  "must could be shared" in {
    val allOpenedResources = mutable.HashMap.empty[String, FakeResource]
    val idGenerator = invariantSpec.createIdGenerator()
    val mr = scoped(new FakeResource(allOpenedResources, idGenerator))
    allOpenedResources.keys shouldNot contain("0")
    allOpenedResources.keys shouldNot contain("1")

    val ioResource0: ResourceT[IO, FakeResource] = mr.flatMap { r0 =>
      allOpenedResources("0") should be(a[FakeResource])
      mr.map { r1 =>
        allOpenedResources("0") should be(a[FakeResource])
        allOpenedResources("1") should be(a[FakeResource])
        r1
      }
    }
    ioResource0.run.unsafePerformIO()

    allOpenedResources.keys shouldNot contain("0")
    allOpenedResources.keys shouldNot contain("1")
  }

  "reference count test without shared" in {
    val allOpenedResources = mutable.HashMap.empty[String, FakeResource]
    val mr0 = scoped(new FakeResource(allOpenedResources, "r0"))
    allOpenedResources.keys shouldNot contain("r0")
    intercept[CanNotOpenResourceTwice] {

      val ioResource0: ResourceT[IO, FakeResource] = mr0.flatMap { r0 =>
        mr0.map { r1 =>
          r1
        }
      }
      ioResource0.run.unsafePerformIO()
    }
    allOpenedResources.keys should contain("r0")
  }
}
