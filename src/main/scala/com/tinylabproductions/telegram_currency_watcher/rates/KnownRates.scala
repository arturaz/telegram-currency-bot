package com.tinylabproductions.telegram_currency_watcher.rates

import java.time.OffsetDateTime

case class KnownRates(rates: Map[RatePair, BigDecimal], lastUpdate: Option[OffsetDateTime]) {
  val currencies: Vector[String] =
    rates.keys.flatMap(p => List(p.from, p.to)).toVector.sorted.distinct
}
object KnownRates {
  val empty = apply(Map.empty, None)
}