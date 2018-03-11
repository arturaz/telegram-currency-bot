package com.tinylabproductions.telegram_currency_watcher.rates.providers

import java.time.OffsetDateTime

import com.tinylabproductions.telegram_currency_watcher.rates.KnownRates

case class ProviderData(rates: KnownRates, lastUpdate: Option[OffsetDateTime])
object ProviderData {
  val empty = apply(KnownRates.empty, None)
}