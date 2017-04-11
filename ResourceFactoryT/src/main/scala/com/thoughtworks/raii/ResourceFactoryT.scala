package com.thoughtworks.raii

import java.util.concurrent.atomic.AtomicReference

import scala.annotation.tailrec
import scala.collection.immutable.Queue
import scala.language.higherKinds
import scalaz.Free.Trampoline
import scalaz.Leibniz.===
import scalaz._, Id.Id
import scalaz.std.iterable._
import scalaz.syntax.all._

trait ResourceFactoryT[F[_], A] extends Any {

  import ResourceFactoryT._

  def acquire(): F[ResourceT[F, A]]

  private[raii] final def using[B](f: A => F[B])(implicit monad: Bind[F]): F[B] = {
    acquire().flatMap { fa =>
      f(fa.value).flatMap { a: B =>
        fa.release().map { _ =>
          a
        }
      }
    }
  }

  final def run(implicit monad: Bind[F]): F[A] = {
    acquire().flatMap { fa =>
      fa.release().map { _ =>
        fa.value
      }
    }
  }

  private[raii] final def foreach(f: A => Unit)(implicit monad: Bind[F], foldable: Foldable[F]): Unit = {
    this
      .acquire()
      .flatMap { fa =>
        f(fa.value)
        fa.release()
      }
      .sequence_[Id.Id, Unit]
  }

}

private[raii] trait LowPriorityResourceFactoryTInstances3 { this: ResourceFactoryT.type =>

  implicit def resourceFactoryTApplicative[F[_]: Applicative]: Applicative[ResourceFactoryT[F, ?]] =
    new ResourceFactoryTApplicative[F] {
      override private[raii] def typeClass = implicitly
    }

}

private[raii] trait LowPriorityResourceFactoryTInstances2 extends LowPriorityResourceFactoryTInstances3 {
  this: ResourceFactoryT.type =>

  implicit def resourceFactoryTMonad[F[_]: Monad]: Monad[ResourceFactoryT[F, ?]] = new ResourceFactoryTMonad[F] {
    private[raii] override def typeClass = implicitly
  }

}

private[raii] trait LowPriorityResourceFactoryTInstances1 extends LowPriorityResourceFactoryTInstances2 {
  this: ResourceFactoryT.type =>

  implicit def resourceFactoryTNondeterminism[F[_]](
      implicit F0: Nondeterminism[F]): Nondeterminism[ResourceFactoryT[F, ?]] =
    new ResourceFactoryTNondeterminism[F] {
      private[raii] override def typeClass = implicitly
    }
}

private[raii] trait LowPriorityResourceFactoryTInstances0 extends LowPriorityResourceFactoryTInstances1 {
  this: ResourceFactoryT.type =>

  implicit def resourceFactoryTMonadError[F[_], S](
      implicit F0: MonadError[F, S]): MonadError[ResourceFactoryT[F, ?], S] =
    new ResourceFactoryTMonadError[F, S] {
      private[raii] override def typeClass = implicitly
    }
}

object ResourceFactoryT extends LowPriorityResourceFactoryTInstances0 {

  trait ResourceT[F[_], +A] {
    def value: A

    /** Releases all the native resources [[ResourceFactoryT.acquire]]d during creating this [[ResourceT]].
      *
      * @note After [[release]], [[value]] should not be used if:
      *  - [[value]] is a managed native resource,
      *    e.g. a [[com.thoughtworks.raii.sde.raii#managed managed]] `AutoCloseable`,
      *  - or, [[value]] internally uses some managed native resources.
      */
    def release(): F[Unit]
  }

  // implicit conversion of SAM type for Scala 2.10 and 2.11
  implicit final class FunctionResourceFactoryT[F[_], A](val underlying: () => F[ResourceT[F, A]])
      extends AnyVal
      with ResourceFactoryT[F, A] {
    override def acquire(): F[ResourceT[F, A]] = underlying()
  }

  private[raii] trait ResourceFactoryTPoint[F[_]] extends Applicative[ResourceFactoryT[F, ?]] {
    private[raii] implicit def typeClass: Applicative[F]

    override def point[A](a: => A): ResourceFactoryT[F, A] = { () =>
      Applicative[F].point(new ResourceT[F, A] {
        override val value: A = a

        override def release(): F[Unit] = Applicative[F].point(())
      })
    }
  }

  private[raii] trait ResourceFactoryTApplicative[F[_]]
      extends Applicative[ResourceFactoryT[F, ?]]
      with ResourceFactoryTPoint[F] {

    override def ap[A, B](fa: => ResourceFactoryT[F, A])(f: => ResourceFactoryT[F, (A) => B]): ResourceFactoryT[F, B] = {
      () =>
        Applicative[F].apply2(fa.acquire(), f.acquire()) { (releasableA, releasableF) =>
          new ResourceT[F, B] {
            override val value: B = releasableF.value(releasableA.value)

            override def release(): F[Unit] = {
              Applicative[F].apply2(releasableA.release(), releasableF.release()) { (_: Unit, _: Unit) =>
                ()
              }
            }
          }
        }
    }
  }

  private[raii] trait ResourceFactoryTMonad[F[_]]
      extends ResourceFactoryTApplicative[F]
      with Monad[ResourceFactoryT[F, ?]] {
    private[raii] implicit override def typeClass: Monad[F]

    override def bind[A, B](fa: ResourceFactoryT[F, A])(f: (A) => ResourceFactoryT[F, B]): ResourceFactoryT[F, B] = {
      () =>
        for {
          releasableA <- fa.acquire()
          releasableB <- f(releasableA.value).acquire()
        } yield {
          new ResourceT[F, B] {
            override def value: B = releasableB.value

            override def release(): F[Unit] = {
              releasableB.release() >> releasableA.release()
            }
          }
        }
    }

  }

  private def catchError[F[_]: MonadError[?[_], S], S, A](fa: F[A]): F[S \/ A] = {
    fa.map(_.right[S]).handleError(_.left[A].point[F])
  }

  private[raii] trait ResourceFactoryTMonadError[F[_], S]
      extends MonadError[ResourceFactoryT[F, ?], S]
      with ResourceFactoryTPoint[F] {
    private[raii] implicit def typeClass: MonadError[F, S]

    override def raiseError[A](e: S): ResourceFactoryT[F, A] = { () =>
      typeClass.raiseError[ResourceT[F, A]](e)
    }

    override def handleError[A](fa: ResourceFactoryT[F, A])(f: (S) => ResourceFactoryT[F, A]): ResourceFactoryT[F, A] = {
      () =>
        fa.acquire().handleError { s =>
          f(s).acquire()
        }
    }

    override def bind[A, B](fa: ResourceFactoryT[F, A])(f: A => ResourceFactoryT[F, B]): ResourceFactoryT[F, B] = {
      () =>
        catchError(fa.acquire()).flatMap {
          case \/-(releasableA) =>
            catchError(f(releasableA.value).acquire()).flatMap[ResourceT[F, B]] {
              case \/-(releasableB) =>
                new ResourceT[F, B] {
                  override def value: B = releasableB.value
                  override def release(): F[Unit] = {
                    catchError(releasableB.release()).flatMap {
                      case \/-(()) =>
                        releasableA.release()
                      case -\/(s) =>
                        releasableA.release().flatMap { _ =>
                          typeClass.raiseError[Unit](s)
                        }
                    }
                  }
                }.point[F]
              case -\/(s) =>
                releasableA.release().flatMap { _ =>
                  typeClass.raiseError[ResourceT[F, B]](s)
                }
            }
          case either @ -\/(s) =>
            typeClass.raiseError[ResourceT[F, B]](s)
        }
    }
  }

  private[raii] trait ResourceFactoryTNondeterminism[F[_]]
      extends ResourceFactoryTMonad[F]
      with Nondeterminism[ResourceFactoryT[F, ?]] {
    private[raii] implicit override def typeClass: Nondeterminism[F]

    override def chooseAny[A](
        head: ResourceFactoryT[F, A],
        tail: Seq[ResourceFactoryT[F, A]]): ResourceFactoryT[F, (A, Seq[ResourceFactoryT[F, A]])] = { () =>
      typeClass.chooseAny(head.acquire(), tail.map(_.acquire())).map {
        case (fa, residuals) =>
          new ResourceT[F, (A, Seq[ResourceFactoryT[F, A]])] {
            override val value: (A, Seq[ResourceFactoryT[F, A]]) =
              (fa.value, residuals.map { residual: F[ResourceT[F, A]] =>
                { () =>
                  residual
                }: ResourceFactoryT[F, A]
              })

            override def release(): F[Unit] = fa.release()
          }

      }

    }
  }

  implicit val resourceFactoryTMonadTrans = new MonadTrans[ResourceFactoryT] {

    override def liftM[F[_]: Monad, A](fa: F[A]): ResourceFactoryT[F, A] = { () =>
      fa.map { a =>
        new ResourceT[F, A] {
          override val value: A = a

          override def release(): F[Unit] = Applicative[F].point(())
        }
      }
    }

    override def apply[F[_]: Monad]: Monad[ResourceFactoryT[F, ?]] = resourceFactoryTMonad

  }

}
