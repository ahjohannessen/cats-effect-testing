/*
 * Copyright 2020-2021 Typelevel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cats.effect.testing
package scalatest

import cats.effect.{Async, Deferred, Resource, Sync}
import cats.effect.syntax.all._
import cats.syntax.all._

import org.scalatest.{BeforeAndAfterAll, FixtureAsyncTestSuite, FutureOutcome, Outcome}

import scala.concurrent.Await
import scala.concurrent.duration._

trait CatsResource[F[_], A] extends BeforeAndAfterAll { this: FixtureAsyncTestSuite =>

  def ResourceAsync: Async[F]
  private[this] implicit def _ResourceAsync: Async[F] = ResourceAsync

  def ResourceUnsafeRun: UnsafeRun[F]
  private[this] implicit def _ResourceUnsafeRun: UnsafeRun[F] = ResourceUnsafeRun

  val resource: Resource[F, A]

  protected val ResourceTimeout: Duration = 10.seconds

  // we use the gate to prevent further step execution
  // this isn't *ideal* because we'd really like to block the specs from even starting
  // but it does work on scalajs
  @volatile
  private var gate: Option[Deferred[F, Unit]] = None
  @volatile
  private var value: Option[A] = None
  @volatile
  private var shutdown: F[Unit] = ().pure[F]

  override def beforeAll(): Unit = {
    val toRun = for {
      d <- Deferred[F, Unit]
      _ <- Sync[F] delay {
        gate = Some(d)
      }

      pair <- resource.allocated
      (a, shutdownAction) = pair

      _ <- Sync[F] delay {
        value = Some(a)
        shutdown = shutdownAction
      }

      _ <- d.complete(())
    } yield ()

    val guarded = ResourceTimeout match {
      case fd: FiniteDuration => toRun.timeout(fd)
      case _ => toRun
    }

    UnsafeRun[F].unsafeToFuture(guarded)
    ()
  }

  override def afterAll(): Unit = {
    Await.result(UnsafeRun[F].unsafeToFuture(shutdown), ResourceTimeout)

    gate = None
    value = None
    shutdown = ().pure[F]
  }

  override type FixtureParam = A

  override def withFixture(test: OneArgAsyncTest): FutureOutcome = {
    lazy val toRun: F[Outcome] = Sync[F] defer {
      gate match {
        case Some(g) =>
          g.get *> (Async[F] fromFuture {
            Sync[F] delay {
              withFixture(test.toNoArgAsyncTest(value.getOrElse {
                fail("Resource Not Initialized When Trying to Use")
              })).toFuture
            }
          })

        case None =>
          // just... loop I guess? sometimes we can hit this before scalatest has run the earlier action
          toRun
      }
    }

    new FutureOutcome(UnsafeRun[F].unsafeToFuture(toRun))
  }
}
