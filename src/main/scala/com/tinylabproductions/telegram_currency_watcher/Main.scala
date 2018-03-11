package com.tinylabproductions.telegram_currency_watcher

import java.nio.file.Paths

import com.tinylabproductions.telegram_currency_watcher.bot.Bot
import com.tinylabproductions.telegram_currency_watcher.rates.providers.{CoinMarketCap, CurrencyLayer}

object Main {
  def main(args: Array[String]): Unit = {
    val either = for {
      apiKey <- env("TELEGRAM_API_KEY")
      stateFile <- env("STATE_FILE_PATH")
      currencyLayerApiKey <- env("CURRENCY_LAYER_API_KEY")
    } yield new Bot(
      apiKey, Paths.get(stateFile), Vector(new CurrencyLayer(currencyLayerApiKey), new CoinMarketCap)
    )

    either match {
      case Left(error) =>
        Console.err.println(error)
        Console.err.println("Aborting.")
      case Right(bot) =>
        bot.run()
    }
//    io.StdIn.readLine("Bot is running... Press any key to exit.")
  }

  def env(name: String): Either[String, String] =
    Option(System.getenv(name)).toRight(s"Can't get environment variable '$name'!")
}
