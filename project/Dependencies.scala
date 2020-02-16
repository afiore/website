import sbt._

object Dependencies {
  object Versions {
    val doobie = "0.8.4"
  }

  lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.0.8"
  lazy val h2 =  "com.h2database" % "h2" % "1.4.197"
  lazy val doobieCore = "org.tpolecat" %% "doobie-core" % Versions.doobie
  lazy val doobieH2 = "org.tpolecat" %% "doobie-h2" % Versions.doobie
  lazy val catsEffect = "org.typelevel" %% "cats-effect" % "2.1.1"
}
