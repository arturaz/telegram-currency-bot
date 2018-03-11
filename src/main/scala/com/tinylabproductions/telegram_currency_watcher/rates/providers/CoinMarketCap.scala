package com.tinylabproductions.telegram_currency_watcher.rates.providers
import java.time.OffsetDateTime

import com.tinylabproductions.telegram_currency_watcher.rates.{KnownRates, RatePair}
import com.typesafe.scalalogging.Logger
import play.api.libs.json.{JsPath, JsValue, Reads}
import play.api.libs.functional.syntax._
import play.api.libs.ws.{JsonBodyReadables, StandaloneWSClient}

import scala.concurrent.{ExecutionContext, Future}

object CoinMarketCap {
  val Url = "https://api.coinmarketcap.com/v1/ticker/?limit=0"

  case class ResponseEntry(id: String, priceUsd: BigDecimal, priceBtc: BigDecimal)
  object ResponseEntry {
    implicit val reads: Reads[Option[ResponseEntry]] = (
      (JsPath \ "id").read[String] and
      (JsPath \ "price_usd").readNullable[BigDecimal] and
      (JsPath \ "price_btc").readNullable[BigDecimal]
    ) { (id, usdOpt, btcOpt) =>
      (usdOpt, btcOpt) match {
        case (Some(usd), Some(btc)) => Some(apply(id, usd, btc))
        case _ => None
      }
    }
  }
  case class Response(entries: Vector[ResponseEntry])
  object Response {
    implicit val reads: Reads[Response] =
      implicitly[Reads[Vector[Option[ResponseEntry]]]].map(vec => apply(vec.flatten))
  }
}
class CoinMarketCap extends BaseRatesProvider with JsonBodyReadables {
  import CoinMarketCap._

  override def name = "CoinMarketCap"

  override def baseFetch()(
    implicit ws: StandaloneWSClient, ec: ExecutionContext, log: Logger
  ): Future[ProviderData] = {
    ws.url(Url).get().map { response =>
      val responseBody = response.body[JsValue].as[Response]
      ProviderData(
        KnownRates(responseBody.entries.flatMap { entry =>
          Map(
            RatePair(entry.id, "USD") -> entry.priceUsd,
            RatePair(entry.id, "bitcoin") -> entry.priceBtc
          )
        }(collection.breakOut)),
        Some(OffsetDateTime.now())
      )
    }
  }
}
