package com.tinylabproductions.telegram_currency_watcher

import java.time.{DayOfWeek, ZonedDateTime}

object Exchange {
  def withinWorkingHours(time: ZonedDateTime): Boolean =
    time.getDayOfWeek >= DayOfWeek.MONDAY && time.getDayOfWeek <= DayOfWeek.FRIDAY &&
    time.getHour >= 8 && time.getHour <= 16
}
