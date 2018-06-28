package coop.rchain.node.diagnostics

import cats.Monad
import cats.data.EitherT
import cats.implicits._
import coop.rchain.catscontrib.MonadTrans
import coop.rchain.catscontrib.Catscontrib._
import coop.rchain.metrics.Metrics
import coop.rchain.node.model.diagnostics._

trait StoreMetrics[F[_]] {
  def storeUsage: F[StoreUsage]
}

object StoreMetrics extends StoreMetricsInstances {
  def apply[F[_]](implicit M: StoreMetrics[F]): StoreMetrics[F] = M

  def forTrans[F[_]: Monad, T[_[_], _]: MonadTrans](
      implicit C: StoreMetrics[F]): StoreMetrics[T[F, ?]] =
    new StoreMetrics[T[F, ?]] {
      def storeUsage: T[F, StoreUsage] = C.storeUsage.liftM[T]
    }

  def report[F[_]: Monad: StoreMetrics: Metrics]: F[Unit] = {
    val m      = Metrics[F]
    val storem = StoreMetrics[F]

    def g(name: String, value: Long): F[Unit] =
      m.setGauge(name, value)

    def cm(name: String, value: Option[RSpaceUsageMetric]): F[Unit] =
      for {
        _ <- m.setGauge(name + "-count", value.map(_.count).getOrElse(0))
        _ <- m.setGauge(name + "-peak-rate", value.map(_.peakRate.toLong).getOrElse(0))
        _ <- m.setGauge(name + "-current-rate", value.map(_.currentRate.toLong).getOrElse(0))
      } yield ()

    def pc(name: String, value: Option[RSpaceUsageMetric]): F[Unit] =
      for {
        _ <- cm(name, value)
        _ <- m.setGauge(name + "-avg-ms", value.map(_.avgMilliseconds.toLong).getOrElse(0))
      } yield ()

    def reportStoreSize(storeUsage: StoreUsage): List[F[Unit]] =
      List(
        g("total-size-on-disk", storeUsage.totalSizeOnDisk),
        g("rspace-size-on-disk", storeUsage.rspaceSizeOnDisk),
        g("rspace-data-entries", storeUsage.rspaceDataEntries),
        pc("rspace-consumes", storeUsage.rspace.flatMap(_.consumes)),
        pc("rspace-produces", storeUsage.rspace.flatMap(_.produces)),
        cm("rspace-consumes-COMM", storeUsage.rspace.flatMap(_.consumesComm)),
        cm("rspace-produces-COMM", storeUsage.rspace.flatMap(_.producesComm)),
        cm("rspace-install-COMM", storeUsage.rspace.flatMap(_.installComm)),
        pc("replayrspace-consumes", storeUsage.replayRSpace.flatMap(_.consumes)),
        pc("replayrspace-produces", storeUsage.replayRSpace.flatMap(_.produces)),
        cm("replayrspace-consumes-COMM", storeUsage.replayRSpace.flatMap(_.consumesComm)),
        cm("replayrspace-produces-COMM", storeUsage.replayRSpace.flatMap(_.producesComm)),
        cm("replayrspace-install-COMM", storeUsage.replayRSpace.flatMap(_.installComm))
      )

    def join(tasks: Seq[F[Unit]]*): F[List[Unit]] =
      tasks.toList.flatten.sequence

    for {
      storeSize <- storem.storeUsage
      _         <- join(reportStoreSize(storeSize))
    } yield ()
  }
}

sealed abstract class StoreMetricsInstances {
  implicit def eitherTStoreMetrics[E, F[_]: Monad: StoreMetrics[?[_]]]
    : StoreMetrics[EitherT[F, E, ?]] =
    StoreMetrics.forTrans[F, EitherT[?[_], E, ?]]
}
