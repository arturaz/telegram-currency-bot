package com.tinylabproductions.telegram_currency_watcher

import java.nio.file.{Files, Path}
import java.time.ZonedDateTime

import com.softwaremill.quicklens._
import com.typesafe.scalalogging.Logger
import info.mukel.telegrambot4s.api.declarative.Commands
import info.mukel.telegrambot4s.api.{ChatActions, Polling, TelegramBot}
import info.mukel.telegrambot4s.methods.{ParseMode, SendMessage}
import info.mukel.telegrambot4s.models.Message
import play.api.libs.functional.syntax._
import play.api.libs.json.{Format, Json}
import play.api.libs.ws.ahc.StandaloneAhcWSClient

import scala.concurrent.duration._
import scala.util.Try

class Bot(
  val token: String, stateFilePath: Path, provider: RatesProvider
) extends TelegramBot with Polling with Commands with ChatActions
{
  implicit val log: Logger = Logger("Bot")

  case class RatePairState(
    pair: RatePair,
    lowestRate: BigDecimal,
    lastRate: BigDecimal
  ) {
    override def toString = f"[$pair low: $lowestRate%.4f, cur: $lastRate%.4f, cur/low: $percentageS]"

    def percentage = lastRate / lowestRate - 1
    def percentageS = f"${percentage * 100}%.4f%%"
  }
  object RatePairState {
    implicit val format: Format[RatePairState] = Json.format
  }

  case class State(
    threshold: BigDecimal,
    ratePairState: RatePairState
  ) {
    def thresholdS: String = s"${threshold * 100}%"
    override def toString: String = s"State[\n  threshold=$thresholdS\n  $ratePairState\n]"
  }
  object State {
    implicit val format: Format[State] = Json.format
  }

  type ChatId = Long
  case class BotState(clients: Map[ChatId, State])
  object BotState {
    val empty = apply(Map.empty)

    implicit val format: Format[BotState] =
      implicitly[Format[Vector[(ChatId, State)]]]
      .inmap(v => apply(v.toMap), _.clients.toVector)
  }
  private[this] var __botState: BotState =
    Try(Json.parse(Files.readAllBytes(stateFilePath)).as[BotState])
    .getOrElse(BotState.empty)
  log.info(s"Initial state: ${__botState}")

  def botState = __botState

  def updateState(f: BotState => BotState): Unit = this.synchronized {
    __botState = f(botState)
    Files.write(stateFilePath, Json.toBytes(Json.toJson(botState)))
    log.debug(botState.toString)
  }

  def setClientState(state: State)(implicit msg: Message): Unit =
    updateState { botState =>
      botState.modify(_.clients).using(_.updated(msg.chat.id, state))
    }

  def clientState(implicit msg: Message): Option[State] = botState.clients.get(msg.chat.id)

  implicit val ws = StandaloneAhcWSClient()
  system.scheduler.schedule(0.seconds, 1.minute) {
    log.debug("Fetching...")
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
        updateState { botState =>
          botState.modify(_.clients).using(_.flatMap { case (id, state) =>
            rates.rates.get(state.ratePairState.pair) match {
              case Some(lastRate) => Some(
                id -> state
                  .modify(_.ratePairState.lowestRate).setTo(state.ratePairState.lowestRate min lastRate)
                  .modify(_.ratePairState.lastRate).setTo(lastRate)
              )
              case None =>
                request(SendMessage(
                  id, s"Rate provider removed ${state.ratePairState.pair} from their rates, unsubscribing!"
                ))
                None
            }
          })
        }

        botState.clients.foreach { case (chatId, state) =>
          log.debug(s"chat = $chatId, t = ${state.threshold}")
          if (
            Exchange.withinWorkingHours(ZonedDateTime.now())
            && state.ratePairState.percentage >= state.threshold
          ) {
            request(SendMessage(
              chatId,
              s"""!!! Threshold ${state.thresholdS} has been hit !!!
                 |
                 |${state.ratePairState}
               """.stripMargin
            ))
          }
      }
    }
  }

  val CmdHelp = "/help"
  val CmdRates = "/rates"
  val CmdSubscribe = "/subscribe"
  val CmdUnsubscribe = "/unsubscribe"
  val CmdStatus = "/status"
  val CmdSetLowestRate = "/setLowestRate"

  onCommand(CmdHelp) { implicit msg =>
    reply(
      s"""Commands:
        |
        |$CmdRates [currency1] [currency2] - show known rates
        |$CmdSubscribe eur/usd 0.5% - sets watching threshold for eur/usd pair at 0.5% (from the lowest point)
        |$CmdUnsubscribe - clear currently set threshold
        |$CmdStatus - report state
        |$CmdSetLowestRate [price]
        |   Set lowest rate to specified value.
        |   If no rate is specified, set to last known rate.
        |
      """.stripMargin
    )
  }

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

  onCommand(CmdRates) { implicit msg =>
    withArgs { args =>
      val filter = args match {
        case Seq(c) => Some(OneCurrency(c))
        case Seq(from, to) => Some(TwoCurrencies(from, to))
        case _ => None
      }

      val knownRates = provider.knownRates
      val rates = knownRates.rates.filter { case (pair, _) =>
        filter.fold(true)(_.matches(pair))
      }

      val MaxRates = 30
      if (rates.size > MaxRates) {
        reply(
          s"""More than $MaxRates rates found (${rates.size}). Narrow your search.
             |
             |Known currencies: ${knownRates.currencies.mkString(", ")}
           """.stripMargin)
      }
      else if (rates.isEmpty) {
        reply(s"No rates found for filter $filter")
      }
      else {
        val ratesV = rates.toVector.sortBy(_._1.toString).map { case (pair, rate) =>
          f"$pair%-10s $rate%.6f"
        }
        reply(
          ratesV.mkString("```\n", "\n", s"\n\nLast update: ${knownRates.lastUpdate}\n```"),
          parseMode = Some(ParseMode.Markdown)
        )
      }
    }
  }

  val ThresholdRe = """^([\d\.]+)%$""".r
  onCommand(CmdSubscribe) { implicit msg =>
    withArgs {
      case Seq(RatePair(pair), ThresholdRe(BigDecimalE(threshold))) =>
        provider.knownRates.rates.get(pair) match {
          case Some(rate) =>
            setClientState(State(threshold / 100, RatePairState(pair, rate, rate)))
            reply(s"Subscribed to $pair at $threshold%")
          case None =>
            reply(s"Unknown rate pair: $pair")
        }
      case _ =>
        reply(s"Unknown arguments! See $CmdHelp")
    }
  }

  onCommand(CmdUnsubscribe) { implicit msg =>
    updateState { botState =>
      botState.modify(_.clients).using(_ - msg.chat.id)
    }
    reply("Unsubscribed.")
  }

  onCommand(CmdStatus) { implicit msg =>
    reply(
      clientState.fold("Not subscribed.") { s =>
        s"```\n$s\n\nLast update: ${provider.knownRates.lastUpdate}\n```"
      },
      parseMode = Some(ParseMode.Markdown)
    )
  }

  onCommand(CmdSetLowestRate) { implicit msg =>
    clientState match {
      case None => reply("Not subscribed.")
      case Some(state) =>
        withArgs { args =>
          val newRateOpt = args match {
            case Seq(BigDecimalE(rateToSet)) => Some(rateToSet)
            case Seq() => Some(state.ratePairState.lastRate)
            case _ => None
          }
          newRateOpt match {
            case Some(newRate) =>
              val newState = state.modify(_.ratePairState.lowestRate).setTo(newRate)
              setClientState(newState)
              reply(s"Lowest rate set to $newRate.\n\n$newState")
            case None =>
              reply(s"Unknown syntax, see $CmdHelp for help.")
          }
        }
    }
  }
}