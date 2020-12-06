import sbt._

object Dependencies {
  object Versions {
    val doobie = "0.9.0"
    val enumeratum = "1.6.1"
    val tapir = "0.17.0-M10"
    val http4s = "0.21.13"
  }

  lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.0.8"
  lazy val h2 = "com.h2database" % "h2" % "1.4.197"
  lazy val doobieCore = "org.tpolecat" %% "doobie-core" % Versions.doobie
  lazy val doobieH2 = "org.tpolecat" %% "doobie-h2" % Versions.doobie
  lazy val catsEffect = "org.typelevel" %% "cats-effect" % "2.1.1"

  lazy val tapir = "com.softwaremill.sttp.tapir" %% "tapir-core" % Versions.tapir
  lazy val tapirCirce = "com.softwaremill.sttp.tapir" %% "tapir-json-circe" % Versions.tapir
  lazy val tapirHttp4s = "com.softwaremill.sttp.tapir" %% "tapir-http4s-server" % Versions.tapir
  lazy val tapirEnumeratum = "com.softwaremill.sttp.tapir" %% "tapir-enumeratum" % Versions.tapir
  lazy val tapirOpenAPI = "com.softwaremill.sttp.tapir" %% "tapir-openapi-docs" % Versions.tapir
  lazy val tapirCirceYaml = "com.softwaremill.sttp.tapir" %% "tapir-openapi-circe-yaml" % Versions.tapir
  lazy val tapirSttpClient = "com.softwaremill.sttp.tapir" %% "tapir-sttp-client" % Versions.tapir

  lazy val http4sDsl = "org.http4s" %% "http4s-dsl" % Versions.http4s
  lazy val http4sCirce = "org.http4s" %% "http4s-circe" % Versions.http4s

  lazy val circeGeneric = "io.circe" %% "circe-generic" % "0.13.0"

  lazy val enumeratum = "com.beachape" %% "enumeratum" % Versions.enumeratum
  lazy val enumeratumCirce = "com.beachape" %% "enumeratum-circe" % Versions.enumeratum
}
