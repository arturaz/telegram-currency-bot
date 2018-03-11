package com.tinylabproductions.telegram_currency_watcher.rates.providers

import com.tinylabproductions.telegram_currency_watcher.rates.KnownRates
import com.typesafe.scalalogging.Logger
import play.api.libs.ws.StandaloneWSClient

import scala.concurrent.{ExecutionContext, Future}

trait RatesProvider {
  def fetch()(
    implicit ws: StandaloneWSClient, ec: ExecutionContext, log: Logger
  ): Future[KnownRates]

  def knownRates: KnownRates
}

