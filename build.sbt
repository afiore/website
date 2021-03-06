import Dependencies._

ThisBuild / scalaVersion := "2.13.4"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / organization := "com.github.afiore"
ThisBuild / organizationName := "afiore"

Global / onChangedBuildSource := ReloadOnSourceChanges

lazy val root = (project in file("."))
  .settings(
    name := "code-snippets",
    libraryDependencies ++= Seq(
      catsEffect,
      doobieCore,
      doobieH2,
      h2,
      circeGeneric,
      tapir,
      tapirCirce,
      tapirHttp4s,
      tapirEnumeratum,
      tapirOpenAPI,
      tapirCirceYaml,
      tapirSttpClient,
      http4sDsl,
      http4sCirce,
      enumeratum,
      enumeratumCirce,
      scalaTest
    )
  )

lazy val docs = project // new documentation project
  .in(file("afiore-docs")) // important: it must not be docs/
  .dependsOn(root)
  .settings(
    mdocIn := file("blog"),
    mdocOut := file("website/blog"),
    mdocVariables := Map("VERSION" -> version.value)
  )
  .enablePlugins(MdocPlugin, DocusaurusPlugin)
