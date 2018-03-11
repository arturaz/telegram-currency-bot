name := "telegram-currency-watcher"

version := "0.3"

scalaVersion := "2.12.4"

libraryDependencies ++= {
  val playWSVersion = "1.1.3"
  Seq(
    "com.typesafe.scala-logging" %% "scala-logging" % "3.7.2",
    "ch.qos.logback" % "logback-classic" % "1.2.3",
    "com.softwaremill.quicklens" %% "quicklens" % "1.4.11",
    "info.mukel" %% "telegrambot4s" % "3.0.14",
    "com.typesafe.play" %% "play-json" % "2.6.7",
    "com.typesafe.play" %% "play-ahc-ws-standalone" % playWSVersion,
    "com.typesafe.play" %% "play-ws-standalone-json" % playWSVersion
  )
}

enablePlugins(JDebPackaging)
enablePlugins(JavaServerAppPackaging)
enablePlugins(LauncherJarPlugin)
enablePlugins(SystemVPlugin)

daemonStdoutLogFile := Some("bot.log")