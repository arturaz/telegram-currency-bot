package com.tinylabproductions.telegram_currency_watcher.bot

import java.time.{DayOfWeek, ZonedDateTime}
import implicits._

object Exchange {
  def withinWorkingHours(time: ZonedDateTime): Boolean =
    time.getDayOfWeek >= DayOfWeek.MONDAY && time.getDayOfWeek <= DayOfWeek.FRIDAY &&
    time.getHour >= 8 && time.getHour <= 16
}
