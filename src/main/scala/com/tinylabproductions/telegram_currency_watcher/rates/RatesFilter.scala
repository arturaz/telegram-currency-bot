package com.tinylabproductions.telegram_currency_watcher.rates

sealed trait RatesFilter {
  def matches(pair: RatePair): Boolean
}
case class OneCurrency(c: String) extends RatesFilter {
  override def matches(pair: RatePair) = {
    val cl = c.toLowerCase
    pair.from.toLowerCase.contains(cl) || pair.to.toLowerCase.contains(cl)
  }
}
case class TwoCurrencies(from: String, to: String) extends RatesFilter {
  override def matches(pair: RatePair) =
    pair.from.toLowerCase.contains(from.toLowerCase) &&
      pair.to.toLowerCase.contains(to.toLowerCase)
}
