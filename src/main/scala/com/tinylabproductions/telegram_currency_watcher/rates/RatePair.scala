package com.tinylabproductions.telegram_currency_watcher.rates

import play.api.libs.json._

case class RatePair(from: String, to: String) {
  override def toString = s"$from/$to"
}
object RatePair {
  def apply(from: String, to: String) = new RatePair(from.toUpperCase, to.toUpperCase)

  implicit val format: Format[RatePair] = Format(
    implicitly[Reads[String]].flatMap {
      case RatePair.parse(pair) => Reads.pure(pair)
      case other => Reads(_ => JsError(s"Can't parse '$other' as rate pair"))
    },
    Writes(v => Json.toJson(v.toString))
  )

  object parse {
    def unapply(s: String): Option[RatePair] = s.split("/", 2) match {
      case Array(c1, c2) => Some(apply(c1, c2))
      case _ => None
    }
  }
}