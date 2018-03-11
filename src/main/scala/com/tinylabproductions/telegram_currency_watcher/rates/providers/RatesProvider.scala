package com.tinylabproductions.telegram_currency_watcher.rates.providers

import com.tinylabproductions.telegram_currency_watcher.rates.{KnownRates, RatePair}
import com.typesafe.scalalogging.Logger
import play.api.libs.ws.StandaloneWSClient

import scala.concurrent.{ExecutionContext, Future}

trait RatesProvider {
  def name: String

  def fetch()(
    implicit ws: StandaloneWSClient, ec: ExecutionContext, log: Logger
  ): Future[ProviderData]

  def data: ProviderData
}

trait BaseRatesProvider extends RatesProvider {
  protected[this] var _data = ProviderData.empty
  override def data = _data

  override def fetch()(
    implicit ws: StandaloneWSClient, ec: ExecutionContext, log: Logger
  ): Future[ProviderData] = {
    baseFetch().map { data =>
      val inverted = KnownRates(data.rates.rates.map { case (RatePair(c1, c2), rate) =>
        RatePair(c2, c1) -> (BigDecimal(1) / rate)
      })
      _data = data.copy(
        // If we had a rate in normally fetched rates, overwrite the inverted one.
        rates = inverted ++ data.rates
      )
      _data
    }
  }

  def baseFetch()(
    implicit ws: StandaloneWSClient, ec: ExecutionContext, log: Logger
  ): Future[ProviderData]
}

