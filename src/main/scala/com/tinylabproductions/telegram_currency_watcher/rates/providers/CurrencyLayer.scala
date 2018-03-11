package com.tinylabproductions.telegram_currency_watcher.rates.providers

import java.time.OffsetDateTime

import com.tinylabproductions.telegram_currency_watcher.rates.{KnownRates, RatePair}
import com.typesafe.scalalogging.Logger
import play.api.libs.json.{Format, JsValue, Json}
import play.api.libs.ws.{JsonBodyReadables, StandaloneWSClient}

import scala.concurrent.{ExecutionContext, Future}

class CurrencyLayer(apiKey: String) extends RatesProvider with JsonBodyReadables {
  case class Response(quotes: Map[String, BigDecimal])
  object Response {
    implicit val format: Format[Response] = Json.format
  }

  val url = s"http://www.apilayer.net/api/live?access_key=$apiKey&format=1"

  private[this] var _knownRates = KnownRates.empty

  override def fetch()(implicit ws: StandaloneWSClient, ec: ExecutionContext, log: Logger) = {
    def run() = ws.url(url).get().map { resp =>
      val parsedStage1 = resp.body[JsValue].as[Response].quotes.flatMap { case (name, price) =>
        val (c1, c2) = name.splitAt(3)
        Map(RatePair(c1, c2) -> price, RatePair(c2, c1) -> (BigDecimal(1) / price))
      }
      val parsed = KnownRates(
        parsedStage1.flatMap { case (pair, price) =>
          val baseMap = Map(pair -> price)
          val extendedMap =
            if (pair.to == "USD") {
              // Calculate other rates through USD rate
              parsedStage1.collect {
                case (RatePair("USD", to), price2) =>
                  RatePair(pair.from, to) -> price * price2
              }
            }
            else Map.empty
          baseMap ++ extendedMap
        },
        Some(OffsetDateTime.now())
      )
      if (_knownRates.rates != parsed.rates) {
        _knownRates = parsed
      }
      else {
        log.info(
          s"Fetched the same rates as before, no good, wasting requests.\n" +
            s"Last fetch was at ${_knownRates.lastUpdate}, now is ${parsed.lastUpdate}"
        )
      }
      parsed
    }

    _knownRates.lastUpdate match {
      // Free tier only refreshes once per hour, so no point in fetching it more often
      // With refreshing every hour, we should have 24 * 31 = 744 requests which is <= 1000
      // that currency layer gives for free
      case Some(lastUpdate) if lastUpdate.getHour == OffsetDateTime.now().getHour =>
        Future.successful(_knownRates)
      // Fetch otherwise
      case _ =>
        run()
    }
  }

  override def knownRates = _knownRates
}