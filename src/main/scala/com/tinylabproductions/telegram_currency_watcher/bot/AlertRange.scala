package com.tinylabproductions.telegram_currency_watcher.bot

import java.time.{DayOfWeek, ZoneId, ZonedDateTime}

import implicits._
import play.api.libs.json.{Format, Json}

import scala.Ordering.Implicits._
import scala.util.Try

case class Time(hour: Int, minute: Int) {
  override def toString: String = f"$hour%02d:$minute%02d"

  def toSeconds: Int = hour * 3600 + minute * 60
}
object Time {
  implicit val format: Format[Time] = Json.format
  implicit val ordering: Ordering[Time] = Ordering.by(_.toSeconds)
}

case class TimeRange(from: Time, to: Time, timezone: ZoneId) {

  override def toString: String = s"$from-${to}T[$timezone]"

  def matches(time: ZonedDateTime): Boolean = {
    val recalculated = time.withZoneSameInstant(timezone)
    val t = Time(recalculated.getHour, recalculated.getMinute)
    from <= t && t <= to
  }
}
object TimeRange {
  val default = apply(Time(8, 0), Time(16, 0), ZoneId.of("Europe/Vilnius"))
  implicit val format: Format[TimeRange] = Json.format

  object parse {
    private[this] val Re = """(\d{1,2}):(\d{1,2})-(\d{1,2}):(\d{1,2})(T\[(.+?)\])?""".r

    def unapply(s: String): Option[TimeRange] = s match {
      case Re(fromHourS, fromMinuteS, toHourS, toMinuteS, _, zoneIdSMaybeNull) =>
        (
          for {
            zoneId <- Try(Option(zoneIdSMaybeNull).fold(default.timezone)(ZoneId.of))
            from <- Try(Time(fromHourS.toInt, fromMinuteS.toInt))
            to <- Try(Time(toHourS.toInt, toMinuteS.toInt))
          } yield apply(from, to, zoneId)
        ).toOption
      case _ => None
    }
  }
}

case class DayRange(days: Set[DayOfWeek]) {
  override def toString: String = s"Days(${days.map(_.getValue).toVector.sorted.mkString(",")})"
}
object DayRange {
  val default = apply(range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY))
  implicit val format: Format[DayRange] = Json.format

  def range(from: DayOfWeek, to: DayOfWeek): Set[DayOfWeek] =
    DayOfWeek.values().filter(d => d >= from && d <= to).toSet

  object parse {
    def unapply(s: String): Option[DayRange] = {
      Some(apply(s.split(",").flatMap { part =>
        part.split("-", 2) match {
          case Array(IntE(DayOfWeekE(from)), IntE(DayOfWeekE(to))) =>
            range(from, to)
          case Array(IntE(DayOfWeekE(day))) =>
            Set(day)
          case _ =>
            return None
        }
      }(collection.breakOut)))
    }
  }
}

case class AlertRange(time: TimeRange, day: DayRange) {
  override def toString: String = s"AlertRange[$time; $day]"

  def matches(time: ZonedDateTime): Boolean =
    day.days.contains(time.getDayOfWeek) && this.time.matches(time)
}
object AlertRange {
  val default = apply(TimeRange.default, DayRange.default)
  implicit val format: Format[AlertRange] = Json.format
}