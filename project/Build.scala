import com.typesafe.sbt.packager.docker.DockerPlugin
import sbt.Keys._
import sbt._
import com.zavakid.sbt._

object ProcessorBuild extends Build {

  import com.typesafe.sbt.SbtMultiJvm
  import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys.MultiJvm

  val project = Project(
    id = "root",
    base = file("."),
    settings = Defaults.coreDefaultSettings ++ SbtMultiJvm.multiJvmSettings ++ settings
  ).enablePlugins(SbtOneLog, DockerPlugin) configs (MultiJvm)


override lazy val  settings = super.settings ++ Seq(
    organization := "com.github.kuhnen",
    name := "akka-kafka-processor",
    version := "0.1",
    scalaVersion := "2.11.4",
    resolvers ++= Dependencies.resolvers,
    libraryDependencies ++= Dependencies.libraryDependenciesCore ++ Dependencies.test,
    scalacOptions ++= Seq(
      "-unchecked",
      "-deprecation",
      "-Xlint",
      "-Ywarn-dead-code",
      "-language:_",
      "-target:jvm-1.7",
      "-encoding", "UTF-8",
      "-Yclosure-elim",
      "-Xfatal-warnings",
      "-Yinline",
      "-Xverify",
      "-feature"
    ),
    fork := true
  )

  //scalacOptions ++= Seq(
    //"-Xmax-classfile-name", "150",
   // ,"-language:postfixOps"
  //)

}

object Dependencies {

  import Version._

  lazy val libraryDependenciesCore = Seq(
    "com.typesafe.akka" %% "akka-actor" % akka,
    "com.typesafe.akka" %% "akka-slf4j" % akka,
    "com.typesafe.akka" %% "akka-cluster" % akka,
    "com.typesafe.akka" %% "akka-contrib" %  akka,
    "io.spray" %% "spray-can" % spray,
    "io.spray" %% "spray-util" % spray,
    "io.spray" %% "spray-routing" % spray,
    "org.json4s" %% "json4s-native" % json,
    "org.json4s" %% "json4s-ext" % json,
    "io.kamon" %% "kamon-core" % kamon,
    "io.kamon" %% "kamon-spray" % kamon,
    "io.kamon" %% "kamon-system-metrics" % kamon,
    "io.kamon" %% "kamon-statsd" % kamon,
    "io.kamon" %% "kamon-log-reporter" % kamon,
    //"io.kamon" %% "kamon-newrelic" % kamon,
    "com.sclasen" %% "akka-kafka" % "0.0.9-SNAPSHOT" % "compile" exclude("com.101tec", "zkclient") exclude("io.netty", "netty"),
    "com.typesafe.scala-logging" %% "scala-logging" % "3.1.0",
    "ch.qos.logback" % "logback-classic" % "1.1.2",
    "com.github.nscala-time" %% "nscala-time" % "1.6.0",
    "com.typesafe" % "config" % "1.2.1",
    "org.aspectj" % "aspectjweaver" % "1.8.4",
    "com.101tec"  % "zkclient" % "0.4" intransitive(),
    "org.fusesource" % "sigar" % "1.6.4" classifier("native") classifier("")
  )

  lazy val test = Seq(
    "com.typesafe.akka" %% "akka-testkit" % akka,
    "com.typesafe.akka" %% "akka-multi-node-testkit" % akka,
    "io.spray" %% "spray-testkit" % spray % "test",
    "org.scalamock" %% "scalamock-scalatest-support" % "3.2.1" exclude("org.scalatest", "scalatest_2.11"),
    "org.scalatest" %% "scalatest" % "2.2.2"
  )

  lazy val resolvers = Seq(
    "spray repo" at "http://repo.spray.io",
    "spray nightlies" at "http://nightlies.spray.io",
    Resolver.sonatypeRepo("releases"),
    "Kamon Repository" at "http://repo.kamon.io",
    "Sonatype Snapshots" at "http://oss.sonatype.org/content/repositories/snapshots/",
    "Typesafe Repo" at "http://repo.typesafe.com/typesafe/releases/",
    "Websudos releases" at "http://maven.websudos.co.uk/ext-release-local"
  )


}

object Version {

  val akka = "2.3.8"

  val spray = "1.3.2"

  val kamon = "0.3.5"

  val kafka = "0.8.2-beta"

  val zookeeper = "3.3.4"

  val json = "3.2.11"

}
