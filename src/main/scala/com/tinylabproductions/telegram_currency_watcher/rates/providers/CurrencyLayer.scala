package com.tinylabproductions.telegram_currency_watcher.rates.providers

import java.time.OffsetDateTime

import com.tinylabproductions.telegram_currency_watcher.rates.{KnownRates, RatePair, providers}
import com.typesafe.scalalogging.Logger
import play.api.libs.json.{Format, JsValue, Json}
import play.api.libs.ws.{JsonBodyReadables, StandaloneWSClient}

import scala.concurrent.{ExecutionContext, Future}

object CurrencyLayer {
  case class Response(quotes: Map[String, BigDecimal])
  object Response {
    implicit val format: Format[Response] = Json.format
  }
}
class CurrencyLayer(apiKey: String) extends BaseRatesProvider with JsonBodyReadables {
  import CurrencyLayer._

  val url = s"http://www.apilayer.net/api/live?access_key=$apiKey&format=1"

  override def name = "CurrencyLayer"

  override def baseFetch()(
    implicit ws: StandaloneWSClient, ec: ExecutionContext, log: Logger
  ): Future[ProviderData] = {
    def run() = ws.url(url).get().map { resp =>
      val parsed = ProviderData(
        KnownRates(resp.body[JsValue].as[Response].quotes.map { case (name, price) =>
          val (c1, c2) = name.splitAt(3)
          RatePair(c1, c2) -> price
        }),
        Some(OffsetDateTime.now())
      )
      if (_data.rates == parsed.rates) {
        log.info(
          s"Fetched the same rates as before, no good, wasting requests.\n" +
          s"Last fetch was at ${_data.lastUpdate}, now is ${parsed.lastUpdate}"
        )
      }
      parsed
    }

    _data.lastUpdate match {
      // Free tier only refreshes once per hour, so no point in fetching it more often
      // With refreshing every hour, we should have 24 * 31 = 744 requests which is <= 1000
      // that currency layer gives for free
      case Some(lastUpdate) if lastUpdate.getHour == OffsetDateTime.now().getHour =>
        Future.successful(_data)
      // Fetch otherwise
      case _ =>
        run()
    }
  }
}