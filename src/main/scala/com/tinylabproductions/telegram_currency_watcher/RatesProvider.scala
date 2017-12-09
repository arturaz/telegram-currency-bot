package com.tinylabproductions.telegram_currency_watcher

import java.time.OffsetDateTime

import com.typesafe.scalalogging.Logger
import play.api.libs.json._
import play.api.libs.ws.JsonBodyReadables._
import play.api.libs.ws.StandaloneWSClient

import scala.concurrent.{ExecutionContext, Future}

case class RatePair(from: String, to: String) {
  override def toString = s"$from/$to"
}
object RatePair {
  def apply(from: String, to: String) = new RatePair(from.toUpperCase, to.toUpperCase)

  implicit val format: Format[RatePair] = Format(
    implicitly[Reads[String]].flatMap {
      case RatePair(pair) => Reads.pure(pair)
      case other => Reads(_ => JsError(s"Can't parse '$other' as rate pair"))
    },
    Writes(v => Json.toJson(v.toString))
  )

  def unapply(s: String): Option[RatePair] = s.split("/", 2) match {
    case Array(c1, c2) => Some(apply(c1, c2))
    case _ => None
  }
}

case class KnownRates(rates: Map[RatePair, BigDecimal]) {
  val currencies: Vector[String] =
    rates.keys.flatMap(p => List(p.from, p.to)).toVector.sorted.distinct
}
object KnownRates {
  val empty = apply(Map.empty)
}

trait RatesProvider {
  def fetch()(
    implicit ws: StandaloneWSClient, ec: ExecutionContext, log: Logger
  ): Future[KnownRates]

  def knownRates: KnownRates
}

class CurrencyLayer(apiKey: String) extends RatesProvider {
  val url = s"http://www.apilayer.net/api/live?access_key=$apiKey&format=1"

  private[this] var lastResponse = Option.empty[LastResponse]

  override def fetch()(implicit ws: StandaloneWSClient, ec: ExecutionContext, log: Logger) = {
    def run() = ws.url(url).get().map { resp =>
      val parsedStage1 = resp.body[JsValue].as[Response].quotes.flatMap { case (name, price) =>
        val (c1, c2) = name.splitAt(3)
        Map(RatePair(c1, c2) -> price, RatePair(c2, c1) -> (BigDecimal(1) / price))
      }
      val parsed = KnownRates(parsedStage1.flatMap { case (pair, price) =>
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
      })
      val data = LastResponse(parsed, OffsetDateTime.now())
      if (!lastResponse.exists(_.rates == parsed)) {
        lastResponse = Some(data)
      }
      else {
        log.info(
          s"Fetched the same rates as before, no good, wasting requests.\n" +
            s"Last fetch was at ${lastResponse.map(_.fetchedAt)}, now is ${data.fetchedAt}"
        )
      }
      data.rates
    }

    lastResponse match {
      // Free tier only refreshes once per hour, so no point in fetching it more often
      // With refreshing every hour, we should have 24 * 31 = 744 requests which is <= 1000
      // that currency layer gives for free
      case Some(resp) if resp.fetchedAt.getHour == OffsetDateTime.now().getHour =>
        Future.successful(resp.rates)
      // Fetch otherwise
      case _ =>
        run()
    }
  }

  override def knownRates = lastResponse match {
    case Some(resp) => resp.rates
    case None => KnownRates.empty
  }

  case class LastResponse(rates: KnownRates, fetchedAt: OffsetDateTime)
  case class Response(quotes: Map[String, BigDecimal])
  object Response {
    implicit val format: Format[Response] = Json.format
  }
}

//object Fixer extends EurUsdProvider {
//  def url(base: String) = s"http://api.fixer.io/latest?base=$base"
//
//  def fetch(base: String)(implicit ws: StandaloneWSClient, ec: ExecutionContext): Future[Response] = {
//    ws.url(url(base)).get().map(_.body[JsValue].as[Response])
//  }
//
//  override def fetch()(implicit ws: StandaloneWSClient, ec: ExecutionContext) =
//    fetch("EUR").map(_.rates("USD"))
//
//  case class Response(rates: Map[String, BigDecimal])
//  object Response {
//    implicit val format: Format[Response] = Json.format
//  }
//}

