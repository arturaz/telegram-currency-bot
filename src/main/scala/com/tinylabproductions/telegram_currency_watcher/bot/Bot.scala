package com.tinylabproductions.telegram_currency_watcher.bot

import java.nio.file.{Files, Path}
import java.time.{DayOfWeek, ZoneId, ZonedDateTime}

import com.softwaremill.quicklens._
import com.tinylabproductions.telegram_currency_watcher.rates.providers.RatesProvider
import com.tinylabproductions.telegram_currency_watcher.rates.{KnownRates, RatePair}
import com.typesafe.scalalogging.Logger
import implicits._
import info.mukel.telegrambot4s.api.declarative.Commands
import info.mukel.telegrambot4s.api.{ChatActions, Polling, TelegramBot}
import info.mukel.telegrambot4s.methods.{ParseMode, SendMessage}
import info.mukel.telegrambot4s.models.Message
import play.api.libs.functional.syntax._
import play.api.libs.json.{Format, JsPath, Json}
import play.api.libs.ws.ahc.StandaloneAhcWSClient

import scala.concurrent.duration._
import scala.util.Try
import scala.util.matching.Regex

object Bot {
  case class RatePairState(
    threshold: BigDecimal,
    lowestRate: BigDecimal,
    lastRate: BigDecimal,
    alertRange: AlertRange
  ) {
    override def toString =
      f"[threshold=$thresholdS, low: $lowestRate%.4f, cur: $lastRate%.4f, cur/low: $percentageS, alert: $alertRange]"

    def thresholdS: String = s"${threshold * 100}%"
    def percentage: BigDecimal = lastRate / lowestRate - 1
    def percentageS = f"${percentage * 100}%.4f%%"
  }
  object RatePairState {
    implicit val format: Format[RatePairState] = Json.format
  }

  case class State(
    pairs: Map[RatePair, RatePairState]
  ) {
    override def toString: String = s"State[\n${pairs.mkString("\n")}\n]"
  }
  object State {
    implicit val format: Format[State] =
      (JsPath \ "pairs").format[Vector[(RatePair, RatePairState)]]
        .inmap(v => apply(v.toMap), _.pairs.toVector)
  }

  type ChatId = Long
  case class BotState(clients: Map[ChatId, State])
  object BotState {
    val empty = apply(Map.empty)

    implicit val format: Format[BotState] =
      implicitly[Format[Vector[(ChatId, State)]]]
        .inmap(v => apply(v.toMap), _.clients.toVector)
  }
}
class Bot(
  val token: String, stateFilePath: Path, providers: Vector[RatesProvider]
) extends TelegramBot with Polling with Commands with ChatActions
{
  import Bot._
  implicit def log: Logger = logger

  private[this] var __botState: BotState =
    Try(Json.parse(Files.readAllBytes(stateFilePath)).as[BotState])
    .getOrElse(BotState.empty)
  log.info(s"Initial state: ${__botState}")

  def botState: BotState = __botState

  def updateState(f: BotState => BotState): Unit = this.synchronized {
    __botState = f(botState)
    Files.write(stateFilePath, Json.toBytes(Json.toJson(botState)))
    log.debug(botState.toString)
  }

  def setClientState(state: State)(implicit msg: Message): Unit =
    updateState { botState =>
      botState.modify(_.clients).using(_.updated(msg.chat.id, state))
    }

  def updateClientState(f: Option[State] => Option[State])(implicit msg: Message): Unit =
    updateState { botState =>
      botState.modify(_.clients).using { states =>
        f(states.get(msg.chat.id)) match {
          case None => states - msg.chat.id
          case Some(newState) => states.updated(msg.chat.id, newState)
        }
      }
    }

  def clientState(implicit msg: Message): Option[State] = botState.clients.get(msg.chat.id)

  implicit val ws: StandaloneAhcWSClient = StandaloneAhcWSClient()
  system.scheduler.schedule(0.seconds, 1.minute) {
    // Rates update loop
    def withProvider(provider: RatesProvider): Unit = {
      log.debug(s"Fetching from $provider...")
      provider.fetch().onComplete {
        case util.Failure(err) =>
          log.error("Fetching currency data failed", err)
          __botState.clients.foreach { case (chatId, _) =>
            request(SendMessage(
              chatId,
              s"Fetching currency data failed:\n\n$err\n\n${err.getStackTrace.mkString("\n")}"
            ))
          }
        case util.Success(rates) =>
          log.debug(s"Rates fetched from $provider")
      }
    }

    providers.foreach(withProvider)
  }
  system.scheduler.schedule(30.seconds, 1.minute) {
    // Alerting loop.
    val rates = joinedRates
    updateState { botState =>
      botState.modify(_.clients).using(_.map { case (id, state) =>
        id -> state.modify(_.pairs).using { pairs =>
          pairs.flatMap { case (pair, pairState) =>
            rates.path(pair) match {
              case Some((_, lastRate)) =>
                val newState =
                  pairState
                    .modify(_.lowestRate).using(_ min lastRate)
                    .modify(_.lastRate).setTo(lastRate)
                Some(pair -> newState)
              case None =>
                request(SendMessage(
                  id, s"Rate providers removed $pair from their rates, unsubscribing!"
                ))
                None
            }
          }
        }
      })
    }

    botState.clients.foreach { case (chatId, state) =>
      state.pairs.foreach { case (pair, pairState) =>
        if (
          pairState.percentage >= pairState.threshold
          && pairState.alertRange.matches(ZonedDateTime.now())
        ) {
          request(SendMessage(
            chatId,
            s"""!!! Threshold ${pairState.thresholdS} has been hit !!!
               |
               |$pair
               |$pairState
               """.stripMargin
          ))
        }
      }
    }
  }

  def joinedRates: KnownRates =
    providers.foldLeft(KnownRates.empty)(_ ++ _.data.rates)

  val CmdHelp = "/help"
  val CmdCurrencies = "/currencies"
  val CmdRate = "/rate"
  val CmdSubscribe = "/subscribe"
  val CmdUnsubscribe = "/unsubscribe"
  val CmdStatus = "/status"
  val CmdSetLowestRate = "/setLowestRate"

  onCommand(CmdHelp) { implicit msg =>
    reply(
      s"""Commands:
        |
        |```
        |$CmdCurrencies [currency_filter] - show known currencies
        |$CmdRate currency1 currency2 - show rate for currency pair
        |$CmdSubscribe eur/usd 0.5% [hours] [days]
        |  Sets watching threshold for eur/usd pair at 0.5% (from the lowest point)
        |  hours:
        |    hours when alerts should be sent, in format 00:00-24:00 or 00:00-24:00T[timezone id]
        |    timezone data: https://garygregory.wordpress.com/2013/06/18/what-are-the-java-timezone-ids/
        |    default: 08:00-16:00T[Europe/Vilnius]
        |  days: days when alerts should be sent, in format 1-3,4,6
        |    default: 1-5 (monday to friday)
        |$CmdUnsubscribe [pair]
        |  clear currently set threshold for a pair.
        |  If pair is not specified, clears for all.
        |$CmdStatus - report state
        |$CmdSetLowestRate eur/usd [price]
        |   Set lowest rate to specified value.
        |   If no rate is specified, set to last known rate.
        |```
      """.stripMargin,
      parseMode = Some(ParseMode.Markdown)
    )
  }

  onCommand(CmdCurrencies) { implicit msg =>
    withArgs { args =>
      val filter = args.headOption match {
        case Some(f) =>
          val fUpper = f.toUpperCase
          (s: String) => s.contains(fUpper)
        case None => (s: String) => true
      }

      providers.foreach { provider =>
        val currencies = provider.data.rates.currencies.filter(filter)
        val chopped = currencies.foldLeft(Vector.empty[String]) {
          case (Vector(), currency) => Vector(currency)
          case (nonEmpty, currency) =>
            val lastCurrent = nonEmpty.last
            if (lastCurrent.length < 4000) nonEmpty.updated(nonEmpty.size - 1, s"$lastCurrent, $currency")
            else nonEmpty :+ currency
        }

        reply(
          s"""Provider: ${provider.name}
             |Last update: ${provider.data.lastUpdate}
             |
             |Currencies (${currencies.size}):
             |${if (currencies.isEmpty) "none" else chopped.head}
           """.stripMargin
        )
        if (chopped.size > 1) chopped.tail.foreach { tail => reply(tail) }
      }
    }
  }

  onCommand(CmdRate) { implicit msg =>
    withArgs { args =>
      val filter = args match {
        case Seq(from, to) =>
          val knownRates = joinedRates
          knownRates.path(from, to) match {
            case Some((path, rate)) =>
              reply(s"$path: $rate")
            case None =>
              reply(s"Can't find $from/$to rate.")
          }

        case _ =>
          reply(s"Invalid arguments: $args")
      }
    }
  }

  val ThresholdRe: Regex = """^([\d\.]+)%$""".r
  onCommand(CmdSubscribe) { implicit msg =>
    def run(
      pair: RatePair, threshold: BigDecimal,
      timeRange: TimeRange = TimeRange.default,
      dayRange: DayRange = DayRange.default
    ): Unit = {
      joinedRates.path(pair) match {
        case Some((path, rate)) =>
          val pairState = pair -> RatePairState(threshold / 100, rate, rate, AlertRange(timeRange, dayRange))
          updateClientState {
            case None => Some(State(Map(pairState)))
            case Some(state) => Some(state.modify(_.pairs).using(_ + pairState))
          }
          reply(s"Subscribed to $pair via path $path at $threshold%")
        case None =>
          reply(s"Unknown rate pair: $pair")
      }
    }

    withArgs {
      case Seq(
        RatePair.parse(pair), ThresholdRe(BigDecimalE(threshold)),
        TimeRange.parse(time), DayRange.parse(day)
      ) =>
        run(pair, threshold, time, day)
      case Seq(RatePair.parse(pair), ThresholdRe(BigDecimalE(threshold)), TimeRange.parse(time)) =>
        run(pair, threshold, time)
      case Seq(RatePair.parse(pair), ThresholdRe(BigDecimalE(threshold))) =>
        run(pair, threshold)
      case _ =>
        reply(s"Unknown arguments! See $CmdHelp")
    }
  }

  onCommand(CmdUnsubscribe) { implicit msg =>
    withArgs {
      case Seq(RatePair.parse(pair)) =>
        updateClientState {
          case None =>
            reply("Not subscribed.")
            None
          case Some(state) =>
            reply(s"Unsubscribed from $pair")
            Some(state.modify(_.pairs).using(_ - pair))
        }
      case Seq() =>
        updateState { botState =>
          botState.modify(_.clients).using(_ - msg.chat.id)
        }
        reply("Unsubscribed.")
      case other =>
        reply(s"Invalid args: $other")
    }
  }

  onCommand(CmdStatus) { implicit msg =>
    val lastUpdates =
      "Last updates:\n" +
      providers.map(p => s"- ${p.name}: ${p.data.lastUpdate}").mkString("\n")
    reply(
      clientState.fold(s"```\nNot subscribed.\n\n$lastUpdates```") { s =>
        s"```\n$s\n\n$lastUpdates```"
      },
      parseMode = Some(ParseMode.Markdown)
    )
  }

  onCommand(CmdSetLowestRate) { implicit msg =>
    def run(pair: RatePair, rateOpt: Option[BigDecimal]): Unit = {
      clientState match {
        case None => reply("Not subscribed.")
        case Some(state) =>
          val newRateOpt = rateOpt orElse state.pairs.get(pair).map(_.lastRate)
          newRateOpt match {
            case Some(newRate) =>
              val newState = state.modify(_.pairs.at(pair).lowestRate).setTo(newRate)
              setClientState(newState)
              reply(s"Lowest rate set to $newRate.\n\n$newState")
            case None =>
              reply(s"Not subscribed to $pair.")
          }
      }
    }

    withArgs {
      case Seq(RatePair.parse(pair), BigDecimalE(rateToSet)) =>
        run(pair, Some(rateToSet))
      case Seq(RatePair.parse(pair)) =>
        run(pair, None)
      case other =>
        reply(s"Invalid arguments: $other")
    }
  }
}