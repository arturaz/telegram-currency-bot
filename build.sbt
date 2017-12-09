name := "telegram-currency-watcher"

version := "0.2"

scalaVersion := "2.12.3"

libraryDependencies ++= {
  val playWSVersion = "1.1.3"
  Seq(
    "com.typesafe.scala-logging" %% "scala-logging" % "3.7.2",
    "org.slf4j" % "slf4j-simple" % "1.7.25",
    "com.softwaremill.quicklens" %% "quicklens" % "1.4.11",
    "info.mukel" %% "telegrambot4s" % "3.0.14",
    "com.typesafe.play" %% "play-json" % "2.6.7",
    "com.typesafe.play" %% "play-ahc-ws-standalone" % playWSVersion,
    "com.typesafe.play" %% "play-ws-standalone-json" % playWSVersion
  )
}

enablePlugins(JDebPackaging)
enablePlugins(JavaServerAppPackaging)
enablePlugins(SystemVPlugin)

daemonStdoutLogFile := Some("bot.log")