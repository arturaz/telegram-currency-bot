package com.tinylabproductions.telegram_currency_watcher.rates

import implicits._

sealed trait RatePairPath
object RatePairPath {
  case class Direct(pair: RatePair) extends RatePairPath {
    override def toString: String = s"[$pair]"
  }
  case class Indirect(pair1: RatePair, pair2: RatePair) extends RatePairPath {
    override def toString: String = s"[$pair1 -> $pair2]"
  }
}

case class KnownRates(rates: Map[RatePair, BigDecimal]) {
  val currencies: Vector[String] =
    rates.keys.flatMap(p => List(p.from, p.to)).toVector.sorted.distinct
  lazy val map = rates.groupBy(_._1.from).map { case (from, tos) =>
    from -> tos.map { case (RatePair(_, to), rate) => to -> rate }
  }

  def ++(o: KnownRates): KnownRates = KnownRates(rates ++ o.rates)

  def path(pair: RatePair): Option[(RatePairPath, BigDecimal)] = path(pair.from, pair.to)
  def path(from_ : String, to_ : String): Option[(RatePairPath, BigDecimal)] = {
    val from = from_.toUpperCase
    val to = to_.toUpperCase

    val direct = map.get(from).flatMap(_.get(to)).map { rate =>
      RatePairPath.Direct(RatePair(from, to)) -> rate
    }

    direct orElse {
      map.get(from).flatMap { ratesMap =>
        ratesMap.collectFind {
          case (toTransitive, rate1) =>
            map.get(toTransitive).flatMap(_.get(to)).map { rate2 =>
              RatePairPath.Indirect(
                RatePair(from, toTransitive), RatePair(toTransitive, to)
              ) -> (rate1 * rate2)
            }
          case _ => None
        }
      }
    }
  }
}
object KnownRates {
  val empty = apply(Map.empty)
}
